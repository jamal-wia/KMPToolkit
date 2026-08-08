package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The vocabulary itself: what "transient" promises, and that the config defaults to the stricter
 * policy rather than the more convenient one.
 */
class BiometricAvailabilityTest {

    @Test
    fun `a lockout is transient because the cool-down expires on its own`() {
        assertTrue(BiometricUnavailability.LOCKED_OUT.isTransient)
    }

    @Test
    fun `a busy sensor is transient because nothing is asked of the user`() {
        assertTrue(BiometricUnavailability.HARDWARE_UNAVAILABLE.isTransient)
    }

    @Test
    fun `a permanent lockout is not transient - only a credential clears it`() {
        assertFalse(BiometricUnavailability.PERMANENTLY_LOCKED_OUT.isTransient)
    }

    @Test
    fun `nothing enrolled is not transient - waiting never enrols a finger`() {
        assertFalse(BiometricUnavailability.NOT_ENROLLED.isTransient)
    }

    @Test
    fun `absent or distrusted hardware is never transient`() {
        assertFalse(BiometricUnavailability.NO_HARDWARE.isTransient)
        assertFalse(BiometricUnavailability.SECURITY_UPDATE_REQUIRED.isTransient)
        assertFalse(BiometricUnavailability.UNSUPPORTED.isTransient)
    }

    @Test
    fun `an unknown reason is not transient so a retry loop cannot spin forever`() {
        assertFalse(BiometricUnavailability.UNKNOWN.isTransient)
    }

    @Test
    fun `exactly two reasons are transient`() {
        // Pins the whole enum: adding a reason without deciding whether waiting clears it fails
        // here rather than silently defaulting to "retry is fine".
        assertEquals(
            listOf(
                BiometricUnavailability.HARDWARE_UNAVAILABLE,
                BiometricUnavailability.LOCKED_OUT,
            ),
            BiometricUnavailability.entries.filter { it.isTransient }.sortedBy { it.name },
        )
    }

    @Test
    fun `an unavailable answer carries the reason it was built with`() {
        BiometricUnavailability.entries.forEach { reason ->
            assertEquals(reason, BiometricAvailability.Unavailable(reason).reason)
        }
    }

    @Test
    fun `two unavailable answers with different reasons are not equal`() {
        assertTrue(
            BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE) !=
                BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
        )
    }

    @Test
    fun `the default config is the strict biometric-only gate`() {
        val config = BiometricGateConfig()

        assertEquals(BiometricPolicy.BIOMETRIC_ONLY, config.policy)
        assertTrue(
            config.requireExplicitConfirmation,
            "a passive face match must need a confirming tap unless the consumer opts out",
        )
    }

    @Test
    fun `a result carries the same reason vocabulary as an availability answer`() {
        // The two paths report the same facts; a caller that handles one set of reasons handles
        // both.
        BiometricUnavailability.entries.forEach { reason ->
            assertEquals(reason, BiometricResult.Unavailable(reason).reason)
        }
    }

    @Test
    fun `a failure without a platform code is still a failure`() {
        assertEquals(null, BiometricResult.Failed().platformCode)
        assertEquals(7, BiometricResult.Failed(7).platformCode)
        assertTrue(BiometricResult.Failed() != BiometricResult.Failed(7))
    }
}
