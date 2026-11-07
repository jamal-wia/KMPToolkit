package io.github.jamal_wia.kmptoolkit.logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A caller that is being interrupted (an executor worker told to shut down, a blocking section of a
 * cancelled job) must still see its interrupt after logging, even when a sink's blocking write is the
 * thing the interrupt woke up. Runs on a private thread; bounded joins.
 */
class InterruptedSinkTest {

    @Test
    fun `an interrupt that wakes a blocking sink is still pending on the caller afterwards`() {
        val stillInterrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        val laterSinkSaw = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        val worker: Thread = thread(name = "interrupted-sink", isDaemon = true) {
            try {
                val blocking = LogSink { _, _, _, _ -> Thread.sleep(5_000) } // throws at once: flag is set
                val counting = LogSink { _, _, _, _ -> laterSinkSaw.incrementAndGet() }
                val logger: Logger = createLoggerFactory(sinks = listOf(blocking, counting)).logger("T")
                Thread.currentThread().interrupt()
                logger.log(LogLevel.INFO, null) { "hello" }
                stillInterrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker did not finish")
        worker.join(5_000)
        assertEquals(1, laterSinkSaw.get(), "the sink after the interrupted one lost the event")
        assertTrue(stillInterrupted.get(), "the sink's InterruptedException cleared the caller's interrupt flag")
    }
}
