package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group sto-desktop, phase A breaker round 10 (decisions D-23), attack on the round-11 interrupt rule of the JCA boundary (`Jca`
 * KDoc rule 1, 05 "the thread's interrupt status is put back"): an interrupt a provider reports must never be lost, never be invented,
 * and a thread interrupted before the call keeps its status.
 *
 * - a thread that was ALREADY interrupted when the provider threw `InterruptedException` (the JDK convention clears the flag on the
 *   throw) is interrupted after the call;
 * - a SUBCLASS of `InterruptedException` is an interrupt too;
 * - a provider that swallows nothing and throws no interrupt leaves a set status set (the boundary never clears it) and a clear one
 *   clear;
 * - on a fresh thread (not the test runner's), the status after the call is observed by the thread itself, and `Thread.interrupted()`
 *   reads it exactly once: one interrupt, never two calls' worth (a flag cannot count, so "exactly once" means set, then clear after one read).
 *
 * Mutation by reading: removing `Thread.currentThread().interrupt()` from `Jca.provider` turns tests 1, 2 and 4 red; an
 * `interrupt()` for every failure turns test 3 red; a `Thread.interrupted()` (clear) anywhere in the boundary turns test 3 red.
 */
class JcaInterruptA10BreakerR10Test {

    private class FailsWith(private val failure: () -> Throwable) : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            // The JDK convention: whoever throws InterruptedException has cleared the status.
            val thrown: Throwable = failure()
            if (thrown is InterruptedException) Thread.interrupted()
            throw thrown
        }
    }

    private class Draws : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0x11)
        }
    }

    private class TokenInterrupted : InterruptedException("a PKCS#11 session wait was interrupted")

    @Test
    fun `a thread interrupted before the call is still interrupted after a provider threw InterruptedException`() {
        try {
            Thread.currentThread().interrupt()
            val typed: JcaFailure = assertFailsWith<JcaFailure> { Jca.randomBytes(FailsWith { InterruptedException("blocking entropy read") }, 8) }
            assertTrue(typed.cause is InterruptedException, "control: the cause is the interrupt")
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt that was pending before the call was lost by the call")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `a subclass of InterruptedException restores the status too`() {
        try {
            assertFalse(Thread.currentThread().isInterrupted, "control: not interrupted at the start")
            val typed: JcaFailure = assertFailsWith<JcaFailure> { Jca.randomBytes(FailsWith { TokenInterrupted() }, 8) }
            assertTrue(typed.cause is TokenInterrupted, "control: the cause is the subclass")
            assertTrue(Thread.currentThread().isInterrupted, "an InterruptedException subclass is an interrupt: the status must be put back")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `the boundary never clears a status it did not set and never sets one for a failure that is not an interrupt`() {
        try {
            Thread.currentThread().interrupt()
            assertEquals(8, Jca.randomBytes(Draws(), 8).size, "control: a working source draws")
            assertTrue(Thread.currentThread().isInterrupted, "a successful provider call cleared the caller's pending interrupt")
            assertFailsWith<JcaFailure> { Jca.randomBytes(FailsWith { IllegalStateException("noise") }, 8) }
            assertTrue(Thread.currentThread().isInterrupted, "a failing provider call cleared the caller's pending interrupt")
            Thread.interrupted()
            assertFailsWith<JcaFailure> { Jca.randomBytes(FailsWith { java.io.InterruptedIOException("a timed read, not an interrupt") }, 8) }
            assertFalse(Thread.currentThread().isInterrupted, "an InterruptedIOException (a socket timeout's type) is not an interrupt of this thread")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `on a thread of its own the restored interrupt is seen once by that thread`() {
        val seen: AtomicReference<List<Boolean>?> = AtomicReference(null)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                runCatching { Jca.randomBytes(FailsWith { InterruptedException("interrupted") }, 4) }.exceptionOrNull()?.let { failure.set(it) }
                seen.set(listOf(Thread.interrupted(), Thread.interrupted()))
            } finally {
                done.countDown()
            }
        }, "breaker10-a10-interrupt").apply { isDaemon = true }
        worker.start()
        check(done.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS)) { "hang bound: the worker did not finish" }
        assertTrue(failure.get() is JcaFailure, "control: the call failed typed: ${failure.get()}")
        assertEquals<Any?>(InterruptedException::class.java, failure.get()?.cause?.javaClass, "control: the cause is the interrupt")
        assertEquals(listOf(true, false), seen.get(), "the status must be set after the call and read exactly once")
    }

    private companion object {
        const val HANG_BOUND_SECONDS: Long = 30
    }
}
