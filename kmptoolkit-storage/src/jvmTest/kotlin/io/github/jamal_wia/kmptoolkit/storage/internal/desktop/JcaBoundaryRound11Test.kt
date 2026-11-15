package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 11 (observation O3 of the phase A breakers): a provider that is interrupted throws `InterruptedException`,
 * which clears the thread's interrupt status. The boundary types it like every other provider failure and puts the status back, so a
 * caller (a store call on a thread that is being shut down) still sees the interrupt after the call answered `OperationFailed`.
 * Other failures leave the status alone.
 */
class JcaBoundaryRound11Test {

    private class FailsWith(private val failure: Throwable) : JcaRandom() {
        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0x55)
            throw failure
        }
    }

    @Test
    fun `an InterruptedException from the provider is typed, its bytes are wiped and the interrupt status is restored`() {
        assertFalse(Thread.currentThread().isInterrupted, "control: the thread is not interrupted when the test starts")
        val interruption = InterruptedException("a blocking entropy read was interrupted")
        try {
            val typed: JcaFailure = assertFailsWith<JcaFailure> { Jca.randomBytes(FailsWith(interruption), 8) }
            assertSame(interruption, typed.cause, "the original is kept as the cause")
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt status must be restored")
        } finally {
            // Leave the test thread as it was found, whatever the assertions said.
            Thread.interrupted()
        }
    }

    @Test
    fun `a failure that is not an interrupt leaves the interrupt status alone`() {
        for (failure: Throwable in listOf(IllegalStateException("noise"), java.security.ProviderException("token error"), RuntimeException(InterruptedException("only a cause")))) {
            assertFailsWith<JcaFailure>(failure.toString()) { Jca.randomBytes(FailsWith(failure), 8) }
            assertEquals(false, Thread.currentThread().isInterrupted, "$failure: the status must not be set")
        }
    }
}
