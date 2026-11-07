package io.github.jamal_wia.kmptoolkit.logging

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One factory shared by 16 real threads while sinks throw, block and re-enter. Plain JUnit on the
 * host; only custom sinks, never the platform sink. Every wait is bounded.
 */
class LoggerThreadStormTest {

    private class CountingSink(val seen: AtomicInteger = AtomicInteger()) : LogSink {
        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            seen.incrementAndGet()
        }
    }

    private fun runThreads(count: Int, body: (Int) -> Unit): List<Throwable> {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads: List<Thread> = List(count) { index ->
            thread(name = "logger-storm-$index", isDaemon = true) {
                start.await(5, TimeUnit.SECONDS)
                try {
                    body(index)
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }
        assertTrue(threads.none { it.isAlive }, "a logging thread is stuck")
        return failures.toList()
    }

    @Test
    fun `sixteen threads through a cancelling sink and a briefly blocking sink lose no event for any sink`() {
        val before = CountingSink()
        val cancelling = AtomicInteger()
        val blocking = AtomicInteger()
        val after = CountingSink()
        val factory: LoggerFactory = createLoggerFactory(
            sinks = listOf(
                before,
                LogSink { _, _, _, _ ->
                    cancelling.incrementAndGet()
                    throw CancellationException("cancelled sink")
                },
                LogSink { _, _, _, _ ->
                    blocking.incrementAndGet()
                    Thread.sleep(0, 200_000)
                },
                LogSink { _, _, _, _ -> throw IllegalStateException("broken sink") },
                after,
            ),
        )
        val evaluations = AtomicInteger()

        val failures: List<Throwable> = runThreads(THREADS) { index ->
            val logger: Logger = factory.logger("t$index")
            repeat(PER_THREAD) {
                try {
                    logger.log(LogLevel.INFO, null) {
                        evaluations.incrementAndGet()
                        "m"
                    }
                    throw AssertionError("the cancellation of a sink was swallowed")
                } catch (expected: CancellationException) {
                    // rethrown after every sink was offered the event
                }
            }
        }

        assertEquals(emptyList(), failures)
        val total: Int = THREADS * PER_THREAD
        assertEquals(total, before.seen.get())
        assertEquals(total, cancelling.get())
        assertEquals(total, blocking.get())
        assertEquals(total, after.seen.get(), "a sink after the cancelling one lost events")
        assertEquals(total, evaluations.get(), "the message lambda ran more than once per event")
    }

    @Test
    fun `a sink that logs back into its own logger ends in a StackOverflowError at the caller and never hangs`() {
        lateinit var logger: Logger
        val depth = AtomicInteger()
        logger = createLoggerFactory(
            sinks = listOf(LogSink { _, _, _, _ ->
                depth.incrementAndGet()
                logger.log(LogLevel.INFO, null) { "echo" }
            }),
        ).logger("tag")

        var overflow: Throwable? = null
        val worker = thread(isDaemon = true) {
            try {
                logger.log(LogLevel.INFO, null) { "first" }
            } catch (t: Throwable) {
                overflow = t
            }
        }
        worker.join(20_000)

        assertTrue(!worker.isAlive, "re-entrant sink hung")
        assertTrue(overflow is StackOverflowError, "re-entrancy neither overflowed nor returned: $overflow")
    }

    @Test
    fun `a re-entrancy guarded sink logs once and the other sinks see both events`() {
        lateinit var logger: Logger
        val guard = ThreadLocal.withInitial { false }
        val other = CountingSink()
        logger = createLoggerFactory(
            sinks = listOf(
                LogSink { _, _, message, _ ->
                    if (!guard.get() && message == "first") {
                        guard.set(true)
                        try {
                            logger.log(LogLevel.INFO, null) { "second" }
                        } finally {
                            guard.set(false)
                        }
                    }
                },
                other,
            ),
        ).logger("tag")

        logger.log(LogLevel.INFO, null) { "first" }

        assertEquals(2, other.seen.get())
    }

    @Test
    fun `several cancelling sinks rethrow one cancellation and still deliver to everyone`() {
        val last = CountingSink()
        val logger: Logger = createLoggerFactory(
            sinks = listOf(
                LogSink { _, _, _, _ -> throw CancellationException("first") },
                LogSink { _, _, _, _ -> throw CancellationException("second") },
                last,
            ),
        ).logger("tag")

        val thrown: CancellationException? = try {
            logger.log(LogLevel.INFO, null) { "m" }
            null
        } catch (e: CancellationException) {
            e
        }

        assertEquals("first", thrown?.message)
        assertEquals(1, last.seen.get())
    }

    @Test
    fun `a sink that rethrows an Error does not starve the sinks after it`() {
        val after = CountingSink()
        val logger: Logger = createLoggerFactory(
            sinks = listOf(LogSink { _, _, _, _ -> throw AssertionError("sink assertion") }, after),
        ).logger("tag")

        runCatching { logger.log(LogLevel.INFO, null) { "m" } }

        assertEquals(1, after.seen.get(), "a sink after one that threw an Error never saw the event")
    }

    @Test
    fun `a sink that throws NotImplementedError does not crash the caller`() {
        val logger: Logger = createLoggerFactory(
            sinks = listOf(LogSink { _, _, _, _ -> TODO("sink not written yet") }),
        ).logger("tag")

        logger.log(LogLevel.INFO, null) { "m" }
    }

    private companion object {
        const val THREADS = 16
        const val PER_THREAD = 400
    }
}
