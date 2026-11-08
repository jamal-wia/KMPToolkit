package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Many real threads logging through one factory whose sinks throw, block and get interrupted, with the
 * overlay buffer in the middle and a reader thread watching every snapshot. Every wait is bounded.
 */
class OverlayLoggerStormTest {

    private fun runThreads(count: Int, body: (Int) -> Unit): List<Throwable> {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads: List<Thread> = List(count) { index ->
            thread(name = "overlay-storm-$index", isDaemon = true) {
                start.await(5, TimeUnit.SECONDS)
                try { body(index) } catch (t: Throwable) { failures += t }
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "a storm thread is stuck")
        return failures.toList()
    }

    private fun sinksAround(state: LogOverlayState): List<LogSink> = listOf(
        LogSink { _, _, _, _ -> throw IllegalStateException("broken sink") },
        state.asLogSink(),
        LogSink { _, _, message, _ -> if (message.hashCode() % 7 == 0) Thread.sleep(1) },
        LogSink { _, _, _, _ -> throw AssertionError("assertion sink") },
    )

    @Test
    fun `twelve threads through throwing and blocking sinks keep every event once and in per thread order`() {
        val state = LogOverlayState(maxRecords = 20_000)
        val factory = createLoggerFactory(sinks = sinksAround(state))
        val perThread = 400
        val failures = runThreads(12) { t ->
            val logger = factory.logger("t$t")
            for (i in 0 until perThread) logger.log(LogLevel.INFO, null) { "$t:$i" }
        }
        assertEquals(emptyList(), failures.map { it.toString() })
        val records = state.records.value
        assertEquals(12 * perThread, records.size)
        assertEquals((1L..records.size.toLong()).toList(), records.map { it.id }, "ids are not 1..n in list order")
        for (t in 0 until 12) {
            val own = records.filter { it.tag == "t$t" }.map { it.message.substringAfter(':').toInt() }
            assertEquals((0 until perThread).toList(), own, "thread $t's events lost their order")
        }
    }

    @Test
    fun `a reader never sees a torn snapshot while writers log and another thread clears`() {
        val state = LogOverlayState(maxRecords = 50)
        val factory = createLoggerFactory(sinks = sinksAround(state))
        val stop = AtomicBoolean(false)
        val problems = ConcurrentLinkedQueue<String>()
        val reader = thread(isDaemon = true, name = "overlay-reader") {
            var snapshots = 0
            while (!stop.get() && snapshots < 400_000) {
                val list = state.records.value
                snapshots++
                if (list.size > 50) problems += "size ${list.size}"
                for (i in 1 until list.size) if (list[i].id != list[i - 1].id + 1) {
                    problems += "ids not consecutive: ${list[i - 1].id}, ${list[i].id}"; break
                }
                if (list.map { it.id }.toSet().size != list.size) problems += "duplicate ids"
            }
        }
        val failures = runThreads(10) { t ->
            val logger = factory.logger("t$t")
            for (i in 0 until 500) {
                logger.log(LogLevel.WARN, null) { "$t:$i" }
                if (t == 0 && i % 37 == 0) state.clear()
            }
        }
        stop.set(true)
        reader.join(10_000)
        assertEquals(emptyList(), failures.map { it.toString() })
        assertEquals(emptyList(), problems.toList().take(5))
    }
}
