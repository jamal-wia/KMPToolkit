package io.github.jamal_wia.kmptoolkit.notification

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The iOS call order under real threads: a frame is handed to the center under the post lock and marked
 * shown at once, and the center's verdict arrives later, on another thread, in any order. The model
 * center applies requests in call order, so the tray ends up showing the last request it accepted.
 * Once every verdict is in, no baseline may stand for a frame that is not on the tray.
 */
class ProgressCoalescerIosProtocolStormAdversarialTest {

    /** The tray of the model center: bucket of the frame shown per id, [TERMINAL] for a non-progress frame. */
    private val tray = ConcurrentHashMap<String, Int>()

    @Test
    fun `delayed verdicts in any order never leave a baseline for a frame that is not on the tray`() {
        val ids: List<String> = listOf("a", "b")
        val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(3) { runnable: Runnable ->
            Thread(runnable, "kmptoolkit-adversarial-center").apply { isDaemon = true }
        }
        try {
            repeat(ROUNDS) { round: Int ->
                tray.clear()
                val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
                val postLock = ReentrantLock()
                val barrier = CyclicBarrier(WORKERS)
                val failures: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
                val workers: List<Thread> = List(WORKERS) { index: Int ->
                    thread(name = "kmptoolkit-adversarial-ios-$round-$index") {
                        try {
                            val random = Random(round * 1000 + index)
                            barrier.await(10, TimeUnit.SECONDS)
                            repeat(OPS) {
                                val id: String = ids[random.nextInt(ids.size)]
                                when (val dice: Int = random.nextInt(100)) {
                                    in 0..4 -> postLock.withLock { coalescer.forget(id); tray.remove(id) }
                                    5 -> postLock.withLock { coalescer.clear(); tray.clear() }
                                    else -> {
                                        val progress: NotificationProgress? = when {
                                            dice < 12 -> null
                                            dice < 16 -> NotificationProgress.Determinate(100)
                                            else -> NotificationProgress.Determinate(random.nextInt(0, 100))
                                        }
                                        post(coalescer, postLock, executor, random, id, progress)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            failures += "worker $index: ${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
                workers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
                assertTrue(workers.none { it.isAlive }, "round $round: a worker is stuck")
                assertEquals(emptyList(), failures.toList(), "round $round")

                val posted: Map<String, ProgressCoalescer.Post> = readField(coalescer, "posted")
                posted.forEach { (id: String, record: ProgressCoalescer.Post) ->
                    assertEquals(
                        record.bucket,
                        tray[id],
                        "round $round: '$id' keeps a baseline at bucket ${record.bucket} (shown=${record.shown}, refused=${record.refused}) but the tray shows ${tray[id]}",
                    )
                }
                val inFlight: Map<String, List<*>> = readField(coalescer, "inFlight")
                assertEquals(0, inFlight.values.sumOf { it.size }, "round $round: frames still in flight after every verdict")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    /** One iOS-ordered post: prepare, issue under the lock, verdict later from another thread. */
    private fun post(
        coalescer: ProgressCoalescer,
        postLock: ReentrantLock,
        executor: ScheduledExecutorService,
        random: Random,
        id: String,
        progress: NotificationProgress?,
    ) {
        val ticket: ProgressCoalescer.PostTicket = coalescer.prepare(id, progress, "content") ?: return
        val accepted: Boolean = random.nextInt(100) >= REFUSAL_PERCENT
        val delayMicros: Long = random.nextLong(0, 1500)
        val verdict = CountDownLatch(1)
        val issued: Boolean = postLock.withLock {
            if (!coalescer.isLive(ticket)) return@withLock false
            if (accepted) tray[id] = bucketOf(progress)
            coalescer.complete(ticket)
            executor.schedule({ verdict.countDown() }, delayMicros, TimeUnit.MICROSECONDS)
            true
        }
        if (!issued) return
        check(verdict.await(10, TimeUnit.SECONDS)) { "the center never answered" }
        if (accepted) coalescer.acknowledge(ticket) else coalescer.rollback(ticket)
    }

    private fun bucketOf(progress: NotificationProgress?): Int = when {
        progress is NotificationProgress.Determinate && progress.percent < 100 -> (progress.percent / 10) * 10
        else -> TERMINAL
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(coalescer: ProgressCoalescer, name: String): T {
        val field = ProgressCoalescer::class.java.getDeclaredField(name).also { it.isAccessible = true }
        return (field.get(coalescer) as Map<*, *>).let { HashMap(it) as T }
    }

    private companion object {
        const val TERMINAL: Int = -1
        const val ROUNDS: Int = 20
        const val WORKERS: Int = 8
        const val OPS: Int = 300
        const val REFUSAL_PERCENT: Int = 35
        const val JOIN_SECONDS: Long = 45L
    }
}
