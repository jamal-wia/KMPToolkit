package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryDisconnected
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorBiometryNotPaired
import platform.LocalAuthentication.LAErrorInvalidContext
import platform.LocalAuthentication.LAErrorNotInteractive
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/**
 * Every `LAError` this module claims to understand, mapped onto its vocabulary.
 *
 * Derived from the table in `docs/kmptoolkit-biometric/05-platform-notes.md`. The two mappings that
 * carry real judgement — iOS's single lockout being the permanent kind, and
 * `LAErrorBiometryNotAvailable` meaning two different things depending on `biometryType` — get a
 * case each, because both are easy to "simplify" into something wrong later.
 *
 * Test names avoid commas: Kotlin/Native rejects them in a backtick identifier even though the JVM
 * accepts them.
 */
class IosBiometricErrorMappingTest {

    private fun availability(code: Long, biometryAbsent: Boolean = false): BiometricUnavailability {
        val result: BiometricAvailability = mapAvailabilityError(code, biometryAbsent)
        assertIs<BiometricAvailability.Unavailable>(result, "expected unavailable for $code")
        return result.reason
    }

    // --- policy -------------------------------------------------------------------------------

    @Test
    fun `a biometric-only gate evaluates the biometrics policy`() {
        assertEquals(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_ONLY).laPolicy(),
        )
    }

    @Test
    fun `a credential-accepting gate evaluates the device owner policy`() {
        assertEquals(
            LAPolicyDeviceOwnerAuthentication,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL).laPolicy(),
        )
    }

    @Test
    fun `the default gate is biometrics-only on iOS too`() {
        assertEquals(
            LAPolicyDeviceOwnerAuthenticationWithBiometrics,
            BiometricGateConfig().laPolicy(),
        )
    }

    // --- availability -------------------------------------------------------------------------

    @Test
    fun `unavailable biometry on a device with no sensor is absent hardware`() {
        assertEquals(
            BiometricUnavailability.NO_HARDWARE,
            availability(LAErrorBiometryNotAvailable, biometryAbsent = true),
        )
    }

    @Test
    fun `unavailable biometry on a device that has a sensor is a transient condition`() {
        // Same error code as the case above. The difference is LAContext.biometryType — a denied
        // Face ID usage prompt is not a phone without a sensor and the user can undo it.
        val reason: BiometricUnavailability =
            availability(LAErrorBiometryNotAvailable, biometryAbsent = false)

        assertEquals(BiometricUnavailability.HARDWARE_UNAVAILABLE, reason)
        assertTrue(reason.isTransient)
    }

    @Test
    fun `disconnected or unpaired biometry is unavailable hardware`() {
        assertEquals(
            BiometricUnavailability.HARDWARE_UNAVAILABLE,
            availability(LAErrorBiometryDisconnected),
        )
        assertEquals(
            BiometricUnavailability.HARDWARE_UNAVAILABLE,
            availability(LAErrorBiometryNotPaired),
        )
    }

    @Test
    fun `nothing enrolled is reported as not enrolled`() {
        assertEquals(
            BiometricUnavailability.NOT_ENROLLED,
            availability(LAErrorBiometryNotEnrolled),
        )
    }

    @Test
    fun `no passcode set is also reported as not enrolled`() {
        // The credential equivalent of an unenrolled finger; the remedy is the same settings trip.
        assertEquals(BiometricUnavailability.NOT_ENROLLED, availability(LAErrorPasscodeNotSet))
    }

    @Test
    fun `the iOS lockout is the permanent kind`() {
        // iOS has exactly one lockout and it clears only when the owner enters the device
        // passcode. Reporting it as the transient Android-style lockout would tell an app to wait
        // 30 seconds for something that never expires.
        val reason: BiometricUnavailability = availability(LAErrorBiometryLockout)

        assertEquals(BiometricUnavailability.PERMANENTLY_LOCKED_OUT, reason)
        assertTrue(!reason.isTransient)
    }

    @Test
    fun `an unrecognised availability error is unknown rather than available`() {
        assertEquals(BiometricUnavailability.UNKNOWN, availability(-9999L))
        assertEquals(BiometricUnavailability.UNKNOWN, availability(0L))
    }

    @Test
    fun `a missing error object still reports unavailable`() {
        // canEvaluatePolicy may return false without populating the NSError out-parameter.
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.UNKNOWN),
            mapAvailabilityError(code = null, biometryAbsent = false),
        )
    }

    @Test
    fun `no availability error ever reads as available`() {
        listOf(
            LAErrorBiometryNotAvailable,
            LAErrorBiometryNotEnrolled,
            LAErrorBiometryLockout,
            LAErrorPasscodeNotSet,
            LAErrorUserCancel,
            LAErrorAuthenticationFailed,
            -1L,
            0L,
            42L,
        ).forEach { code ->
            assertTrue(
                mapAvailabilityError(code, biometryAbsent = false) !=
                    BiometricAvailability.Available,
                "code=$code read as available",
            )
        }
    }

    // --- authentication -----------------------------------------------------------------------

    @Test
    fun `a user cancellation is a cancellation`() {
        assertEquals(BiometricResult.Cancelled, mapAuthenticationError(LAErrorUserCancel))
    }

    @Test
    fun `tapping the fallback button is a cancellation`() {
        // A biometric-only gate hides that button precisely because the library has no label to
        // put on it; if it appears anyway there is nothing to fall back to.
        assertEquals(BiometricResult.Cancelled, mapAuthenticationError(LAErrorUserFallback))
    }

    @Test
    fun `a system or app cancellation is a cancellation and not a failure`() {
        assertEquals(BiometricResult.Cancelled, mapAuthenticationError(LAErrorSystemCancel))
        assertEquals(BiometricResult.Cancelled, mapAuthenticationError(LAErrorAppCancel))
    }

    @Test
    fun `an unrecognised credential is a rejection`() {
        assertEquals(BiometricResult.Rejected, mapAuthenticationError(LAErrorAuthenticationFailed))
    }

    @Test
    fun `a rejection is distinct from a cancellation`() {
        // The user tried and the sensor said no; nobody dismissed anything. An app that retries on
        // one and gives up on the other needs them apart.
        assertTrue(
            mapAuthenticationError(LAErrorAuthenticationFailed) !=
                mapAuthenticationError(LAErrorUserCancel),
        )
    }

    @Test
    fun `device limitations discovered during a prompt keep their reasons`() {
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            mapAuthenticationError(LAErrorBiometryNotEnrolled),
        )
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            mapAuthenticationError(LAErrorPasscodeNotSet),
        )
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.PERMANENTLY_LOCKED_OUT),
            mapAuthenticationError(LAErrorBiometryLockout),
        )
    }

    @Test
    fun `unavailable biometry during a prompt is never claimed as absent hardware`() {
        // By this point the sheet has been evaluated; claiming the device has no sensor would be a
        // guess this path cannot support.
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE),
            mapAuthenticationError(LAErrorBiometryNotAvailable),
        )
    }

    @Test
    fun `a non-interactive or invalid context is a failure carrying its code`() {
        assertEquals(
            BiometricResult.Failed(LAErrorNotInteractive.toInt()),
            mapAuthenticationError(LAErrorNotInteractive),
        )
        assertEquals(
            BiometricResult.Failed(LAErrorInvalidContext.toInt()),
            mapAuthenticationError(LAErrorInvalidContext),
        )
    }

    @Test
    fun `an unrecognised error is a failure carrying its code`() {
        assertEquals(BiometricResult.Failed(-9999), mapAuthenticationError(-9999L))
    }

    @Test
    fun `a missing error object is a failure with no code`() {
        assertEquals(BiometricResult.Failed(), mapAuthenticationError(null))
    }

    @Test
    fun `no error code is ever mapped to authenticated`() {
        // Success on iOS is the boolean the callback carries; no error may ever produce it.
        (-100L..100L).forEach { code ->
            assertTrue(
                mapAuthenticationError(code) != BiometricResult.Authenticated,
                "code=$code was mapped to Authenticated",
            )
        }
        assertTrue(mapAuthenticationError(null) != BiometricResult.Authenticated)
    }

    @Test
    fun `no error code is mapped to the Android-only no-host outcome`() {
        (-100L..100L).forEach { code ->
            assertTrue(
                mapAuthenticationError(code) != BiometricResult.NoPromptHost,
                "code=$code was mapped to NoPromptHost",
            )
        }
    }
}
