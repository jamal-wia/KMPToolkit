package io.github.jamal_wia.kmptoolkit.smoke.storage

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The verdict rules of the smoke (breaker round 1: m1 and m4). They are the only thing that decides skip, pass or fail. */
class VerdictsTest {

    // --- what counts as a skip ---

    @Test
    fun `a locked key store without a cause is a skip`() {
        val decision: ProbeFailureDecision = decideProbeFailure(StorageError.Unavailable(), secureRequired = false)
        assertIs<ProbeFailureDecision.Skip>(decision)
    }

    @Test
    fun `no key store and no user session are skips`() {
        for (reason: UnsupportedReason in listOf(UnsupportedReason.NO_KEY_STORE, UnsupportedReason.NO_USER_SESSION)) {
            val decision: ProbeFailureDecision = decideProbeFailure(StorageError.Unsupported(reason), secureRequired = false)
            assertIs<ProbeFailureDecision.Skip>(decision, "reason $reason")
        }
    }

    // --- m4: a hang or an error in the binding is a failure ---

    @Test
    fun `a native call that passed its time limit is a failure, not a skip`() {
        // This is exactly what the library answers for NativeOutcome.TimedOut (ErrorMapping.nativeOutcome).
        val timedOut: StorageError = StorageError.Unavailable(IOException("The native key-store call passed its time limit"))
        val decision: ProbeFailureDecision = decideProbeFailure(timedOut, secureRequired = false)
        val failure: ProbeFailureDecision.Fail = assertIs<ProbeFailureDecision.Fail>(decision)
        assertTrue("time limit" in failure.reason, "the failure names the cause: ${failure.reason}")
    }

    @Test
    fun `any unavailable answer that carries a cause is a failure`() {
        val decision: ProbeFailureDecision = decideProbeFailure(StorageError.Unavailable(IllegalStateException("x")), secureRequired = false)
        assertIs<ProbeFailureDecision.Fail>(decision)
    }

    @Test
    fun `a broken binding is a failure, never a skip`() {
        val broken: List<StorageError> = listOf(
            StorageError.Unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, UnsatisfiedLinkError("x")),
            StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_RUNTIME),
            StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT),
            StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_OS),
            StorageError.OperationFailed(StorageOperation.ENSURE_KEY),
            StorageError.AccessDenied(),
        )
        for (error: StorageError in broken) {
            assertIs<ProbeFailureDecision.Fail>(decideProbeFailure(error, secureRequired = false), "error $error")
        }
    }

    // --- required turns every skip into a failure ---

    @Test
    fun `secure required turns a skip into a failure`() {
        val skips: List<StorageError> = listOf(
            StorageError.Unavailable(),
            StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE),
            StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION),
        )
        for (error: StorageError in skips) {
            assertIs<ProbeFailureDecision.Fail>(decideProbeFailure(error, secureRequired = true), "error $error")
        }
    }

    // --- m1: a probe-only run cannot satisfy a required secure round trip ---

    @Test
    fun `probe only together with secure required is refused`() {
        val failure: SmokeFailure = assertFailsWith<SmokeFailure> { checkRunOptions(secureRequired = true, probeOnly = true) }
        assertTrue("contradict" in failure.message.orEmpty(), "the refusal says why: ${failure.message}")
    }

    @Test
    fun `every other combination of the two options is accepted`() {
        checkRunOptions(secureRequired = false, probeOnly = false)
        checkRunOptions(secureRequired = false, probeOnly = true)
        checkRunOptions(secureRequired = true, probeOnly = false)
    }
}
