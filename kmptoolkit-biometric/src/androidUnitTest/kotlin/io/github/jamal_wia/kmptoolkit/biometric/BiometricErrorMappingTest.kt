package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every documented Android status and error code, mapped onto this module's vocabulary.
 *
 * These are derived from the contract in `docs/kmptoolkit-biometric/04-api-reference.md` and
 * `05-platform-notes.md` — the table of "what the platform said" against "what your app should do"
 * — not from reading the `when` expressions back. The cases that would be easy to get subtly wrong,
 * and that the donor implementation did get wrong, are called out individually below.
 *
 * No Robolectric: `BiometricManager` and `BiometricPrompt` constants are plain `int`s on the
 * classpath, so these run as fast as a JVM test.
 */
class BiometricErrorMappingTest {

    // --- canAuthenticate ----------------------------------------------------------------------

    @Test
    fun `a success status is available`() {
        assertEquals(
            BiometricAvailability.Available,
            mapCanAuthenticate(BiometricManager.BIOMETRIC_SUCCESS),
        )
    }

    @Test
    fun `no hardware is reported as absent hardware`() {
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE),
            mapCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
    }

    @Test
    fun `unavailable hardware is reported as transient rather than absent`() {
        val availability: BiometricAvailability =
            mapCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)

        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE),
            availability,
        )
        assertTrue(BiometricUnavailability.HARDWARE_UNAVAILABLE.isTransient)
    }

    @Test
    fun `nothing enrolled is reported as not enrolled`() {
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            mapCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED),
        )
    }

    @Test
    fun `a required security update is kept distinct from absent hardware`() {
        // Both mean "this device cannot", but only one is a fleet-wide OS problem worth logging as
        // such — collapsing them would hide it.
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.SECURITY_UPDATE_REQUIRED),
            mapCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `an unsupported authenticator combination is reported as unsupported`() {
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.UNSUPPORTED),
            mapCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED),
        )
    }

    @Test
    fun `an unknown status is unavailable rather than available`() {
        assertEquals(
            BiometricAvailability.Unavailable(BiometricUnavailability.UNKNOWN),
            mapCanAuthenticate(BiometricManager.BIOMETRIC_STATUS_UNKNOWN),
        )
    }

    @Test
    fun `a status this version has never heard of never reads as available`() {
        // Forward compatibility: a future Android adding a status must degrade to "unavailable",
        // never to "go ahead".
        listOf(Int.MIN_VALUE, -99, 42, Int.MAX_VALUE).forEach { status ->
            assertEquals(
                BiometricAvailability.Unavailable(BiometricUnavailability.UNKNOWN),
                mapCanAuthenticate(status),
                "status=$status",
            )
        }
    }

    @Test
    fun `only the success status maps to available`() {
        val statuses: List<Int> = listOf(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
        )

        assertEquals(
            listOf(BiometricManager.BIOMETRIC_SUCCESS),
            statuses.filter { mapCanAuthenticate(it) == BiometricAvailability.Available },
        )
    }

    // --- authentication errors ----------------------------------------------------------------

    @Test
    fun `a user cancellation is a cancellation`() {
        assertEquals(
            BiometricResult.Cancelled,
            mapAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED),
        )
    }

    @Test
    fun `the negative button is a cancellation and not a fallback request`() {
        // The label on that button is the consumer's, so the library cannot know whether it said
        // "Cancel" or "Use PIN"; inventing a "fallback requested" outcome would put words in their
        // mouth. Offering the credential is a policy decision made up front.
        assertEquals(
            BiometricResult.Cancelled,
            mapAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON),
        )
    }

    @Test
    fun `a system cancellation is a cancellation`() {
        assertEquals(
            BiometricResult.Cancelled,
            mapAuthenticationError(BiometricPrompt.ERROR_CANCELED),
        )
    }

    @Test
    fun `absent hardware discovered during a prompt is reported as absent hardware`() {
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.NO_HARDWARE),
            mapAuthenticationError(BiometricPrompt.ERROR_HW_NOT_PRESENT),
        )
    }

    @Test
    fun `unavailable hardware during a prompt is not conflated with absent hardware`() {
        // The donor implementation collapsed HW_UNAVAILABLE, HW_NOT_PRESENT and NO_BIOMETRICS into
        // one outcome, which loses the difference between "buy a new phone", "wait" and "enrol".
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE),
            mapAuthenticationError(BiometricPrompt.ERROR_HW_UNAVAILABLE),
        )
    }

    @Test
    fun `no enrolled biometric is reported as not enrolled`() {
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            mapAuthenticationError(BiometricPrompt.ERROR_NO_BIOMETRICS),
        )
    }

    @Test
    fun `no device credential set is also reported as not enrolled`() {
        // A device with no PIN is the credential equivalent of a device with no enrolled finger,
        // and the app's response — send the user to system settings — is the same.
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED),
            mapAuthenticationError(BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL),
        )
    }

    @Test
    fun `a temporary lockout is transient`() {
        val result: BiometricResult = mapAuthenticationError(BiometricPrompt.ERROR_LOCKOUT)

        assertEquals(BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT), result)
        assertTrue((result as BiometricResult.Unavailable).reason.isTransient)
    }

    @Test
    fun `a permanent lockout is not transient`() {
        val result: BiometricResult =
            mapAuthenticationError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT)

        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.PERMANENTLY_LOCKED_OUT),
            result,
        )
        assertTrue(!(result as BiometricResult.Unavailable).reason.isTransient)
    }

    @Test
    fun `the two lockouts are never conflated`() {
        assertTrue(
            mapAuthenticationError(BiometricPrompt.ERROR_LOCKOUT) !=
                mapAuthenticationError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT),
            "a 30-second cool-down and a lockout needing the device PIN call for different UI",
        )
    }

    @Test
    fun `a required security update during a prompt keeps its own reason`() {
        assertEquals(
            BiometricResult.Unavailable(BiometricUnavailability.SECURITY_UPDATE_REQUIRED),
            mapAuthenticationError(BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED),
        )
    }

    @Test
    fun `retryable platform errors stay failures and carry their code`() {
        listOf(
            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_VENDOR,
        ).forEach { code ->
            assertEquals(BiometricResult.Failed(code), mapAuthenticationError(code), "code=$code")
        }
    }

    @Test
    fun `an unrecognised error code is a failure carrying that code`() {
        assertEquals(BiometricResult.Failed(9999), mapAuthenticationError(9999))
        assertEquals(BiometricResult.Failed(-1), mapAuthenticationError(-1))
    }

    @Test
    fun `no error code is ever mapped to authenticated`() {
        // The single most damaging mistake this mapping could make: an unhandled code falling
        // through to success. Sweeping the whole plausible range costs nothing here.
        (-1000..1000).forEach { code ->
            assertTrue(
                mapAuthenticationError(code) != BiometricResult.Authenticated,
                "code=$code was mapped to Authenticated",
            )
        }
    }

    @Test
    fun `no error code is mapped to the Android-only no-host outcome`() {
        // NoPromptHost means "nothing was shown"; every code here arrives from a prompt that ran.
        (-1000..1000).forEach { code ->
            assertTrue(
                mapAuthenticationError(code) != BiometricResult.NoPromptHost,
                "code=$code was mapped to NoPromptHost",
            )
        }
    }

    @Test
    fun `an unrecognised code never claims a device limitation`() {
        // Guessing "not enrolled" for a code we do not know would send the user to a settings
        // screen that has nothing wrong with it.
        assertTrue(mapAuthenticationError(12345) is BiometricResult.Failed)
    }
}
