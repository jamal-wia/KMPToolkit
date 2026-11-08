package io.github.jamal_wia.kmptoolkit.logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Edge inputs for the documented sink-failure and interrupt guarantees, each on its own bounded thread. */
class LoggerContractEdgeTest {

    private fun onThread(body: () -> Unit): Throwable? {
        val failure = AtomicReference<Throwable?>(null)
        val t = thread(isDaemon = true, name = "logger-edge") {
            try { body() } catch (e: Throwable) { failure.set(e) }
        }
        t.join(20_000)
        assertFalse(t.isAlive, "the logging thread is stuck")
        return failure.get()
    }

    @Test
    fun `an interrupt that arrives while an interrupting and a cancelling sink both run is still pending afterwards`() {
        val flag = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        onThread {
            val factory = createLoggerFactory(sinks = listOf(
                LogSink { _, _, _, _ -> throw InterruptedException("woken") },
                LogSink { _, _, _, _ -> throw java.util.concurrent.CancellationException("cancelled") },
                LogSink { _, _, _, _ -> delivered.set(true) },
            ))
            try { factory.logger("x").i { "m" } } catch (e: java.util.concurrent.CancellationException) { cancelled.set(true) }
            flag.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(cancelled.get() && delivered.get())
        assertTrue(flag.get(), "the interrupt flag was not restored on the cancellation path")
    }

    @Test
    fun `a pre-interrupted caller stays interrupted through sinks that throw ordinary exceptions`() {
        val flag = AtomicBoolean(false)
        onThread {
            Thread.currentThread().interrupt()
            val factory = createLoggerFactory(sinks = listOf(
                LogSink { _, _, _, _ -> throw IllegalStateException("x") },
                LogSink { _, _, _, _ -> },
            ))
            factory.logger("x").e(RuntimeException("e")) { "m" }
            flag.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(flag.get())
    }

    @Test
    fun `a message lambda that throws leaves a pending interrupt alone and reaches no sink`() {
        val reached = AtomicBoolean(false)
        val flag = AtomicBoolean(false)
        val thrown = AtomicReference<Throwable?>(null)
        onThread {
            Thread.currentThread().interrupt()
            val factory = createLoggerFactory(sinks = listOf(LogSink { _, _, _, _ -> reached.set(true) }))
            try { factory.logger("x").i { error("lazy") } } catch (e: IllegalStateException) { thrown.set(e) }
            flag.set(Thread.currentThread().isInterrupted)
        }
        assertEquals("lazy", thrown.get()?.message)
        assertFalse(reached.get())
        assertTrue(flag.get())
    }

    @Test
    fun `a sink blocked in a queue put is woken by the interrupt and the event still reaches the next sink`() {
        val ready = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val flag = AtomicBoolean(false)
        val workerRef = AtomicReference<Thread?>(null)
        val worker = thread(isDaemon = true, name = "logger-blocked") {
            workerRef.set(Thread.currentThread())
            val queue = java.util.concurrent.ArrayBlockingQueue<String>(1).also { it.put("full") }
            val factory = createLoggerFactory(sinks = listOf(
                LogSink { _, _, m, _ -> ready.countDown(); queue.put(m) },
                LogSink { _, _, _, _ -> delivered.set(true) },
            ))
            factory.logger("x").i { "m" }
            flag.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        Thread.sleep(50)
        workerRef.get()!!.interrupt()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertTrue(delivered.get())
        assertTrue(flag.get())
    }
}
