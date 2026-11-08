package io.github.jamal_wia.kmptoolkit.logging

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterruptLostOnErrorTest {

    private fun onThread(body: () -> Unit) {
        val t = thread(isDaemon = true, name = "logger-edge") { try { body() } catch (e: Throwable) { } }
        t.join(20_000)
        assertFalse(t.isAlive, "the logging thread is stuck")
    }

    @Test
    fun `an interrupt cleared by one sink is back even when a later sink raises an Error that propagates`() {
        val flag = AtomicBoolean(false)
        val thrown = AtomicReference<Throwable?>(null)
        onThread {
            val factory = createLoggerFactory(sinks = listOf(
                LogSink { _, _, _, _ -> throw InterruptedException("woken") },
                LogSink { _, _, _, _ -> throw OutOfMemoryError("sink") },
            ))
            try { factory.logger("x").i { "m" } } catch (e: OutOfMemoryError) { thrown.set(e) }
            flag.set(Thread.currentThread().isInterrupted)
        }
        assertTrue(thrown.get() is OutOfMemoryError, "the Error reaches the caller")
        assertTrue(flag.get(), "the interrupt the first sink's throw cleared is lost")
    }

}
