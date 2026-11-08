package io.github.jamal_wia.kmptoolkit.logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A sink whose blocking write is woken by the caller's interrupt but rethrows the `InterruptedException`
 * wrapped (`RuntimeException(e)`, an `ExecutionException` out of a future) is the same event as the bare
 * one: the throw cleared the caller's interrupt flag, and the worker that logs must still see its interrupt.
 */
class WrappedInterruptedSinkTest {

    private fun flagAfterLogging(sink: LogSink): Boolean {
        val stillInterrupted = AtomicBoolean(false)
        val done = CountDownLatch(1)
        thread(name = "wrapped-interrupt", isDaemon = true) {
            try {
                val logger: Logger = createLoggerFactory(sinks = listOf(sink)).logger("T")
                Thread.currentThread().interrupt()
                logger.log(LogLevel.INFO, null) { "hello" }
                stillInterrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker did not finish")
        return stillInterrupted.get()
    }

    @Test
    fun `an InterruptedException wrapped in a RuntimeException still restores the flag`() {
        val sink = LogSink { _, _, _, _ ->
            try { Thread.sleep(5_000) } catch (e: InterruptedException) { throw RuntimeException("write failed", e) }
        }
        assertTrue(flagAfterLogging(sink), "the wrapped interrupt cleared the caller's interrupt flag for good")
    }

    @Test
    fun `an InterruptedException inside an ExecutionException still restores the flag`() {
        val sink = LogSink { _, _, _, _ ->
            try { Thread.sleep(5_000) } catch (e: InterruptedException) { throw ExecutionException(e) }
        }
        assertTrue(flagAfterLogging(sink), "the wrapped interrupt cleared the caller's interrupt flag for good")
    }

    @Test
    fun `a caller that was not interrupted is not interrupted by another thread's wrapped interrupt`() {
        val stillInterrupted = AtomicBoolean(true)
        val done = CountDownLatch(1)
        thread(name = "not-interrupted", isDaemon = true) {
            try {
                val sink = LogSink { _, _, _, _ -> throw ExecutionException(InterruptedException()) }
                createLoggerFactory(sinks = listOf(sink)).logger("T").log(LogLevel.INFO, null) { "hello" }
                stillInterrupted.set(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker did not finish")
        assertTrue(!stillInterrupted.get(), "the logger planted an interrupt the caller never had")
    }
}
