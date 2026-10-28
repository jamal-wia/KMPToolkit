package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith

/**
 * The framework callback's single-attempt rule, from [BiometricGateOptions.singleAttempt]: without it
 * a non-match is not terminal; with it the first non-match is `Rejected`, the sheet is dismissed, and
 * the cancellation that dismissal produces is not reported.
 */
@RunWith(AndroidJUnit4::class)
class AuthenticationCallbackTest {

    private val outcomes = mutableListOf<BiometricResult>()
    private var dismissals = 0

    /** The at-most-once delivery the real port wraps [outcomes] in. */
    private val deliverOnce: (BiometricResult) -> Unit = { outcome -> if (outcomes.isEmpty()) outcomes += outcome }

    private fun callback(singleAttempt: Boolean): BiometricPrompt.AuthenticationCallback =
        authenticationCallback(singleAttempt, deliverOnce) { dismissals++ }

    @Test
    fun `without single attempt a non-match is not an outcome and dismisses nothing`() {
        val callback = callback(singleAttempt = false)

        callback.onAuthenticationFailed()
        callback.onAuthenticationFailed()

        assertEquals(emptyList(), outcomes)
        assertEquals(0, dismissals)
    }

    @Test
    fun `with single attempt the first non-match is rejected and dismisses the prompt`() {
        val callback = callback(singleAttempt = true)

        callback.onAuthenticationFailed()

        assertEquals(listOf<BiometricResult>(BiometricResult.Rejected), outcomes)
        assertEquals(1, dismissals)
    }

    @Test
    fun `the cancellation caused by that dismissal is not reported`() {
        val callback = callback(singleAttempt = true)

        callback.onAuthenticationFailed()
        callback.onAuthenticationError(BiometricPrompt.ERROR_CANCELED, "cancelled")

        assertEquals(listOf<BiometricResult>(BiometricResult.Rejected), outcomes)
    }

    @Test
    fun `terminal errors are mapped the same with or without single attempt`() {
        callback(singleAttempt = true).onAuthenticationError(BiometricPrompt.ERROR_LOCKOUT, "locked")

        assertEquals(listOf<BiometricResult>(BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT)), outcomes)
    }
}
