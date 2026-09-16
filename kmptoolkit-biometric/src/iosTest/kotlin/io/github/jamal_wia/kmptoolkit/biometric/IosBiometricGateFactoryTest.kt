package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The iOS factory holds shared wiring to the same option rule the Android factory enforces. */
class IosBiometricGateFactoryTest {

    @Test
    fun `the factory rejects the weak tier together with the device credential`() {
        assertFailsWith<IllegalArgumentException> {
            createBiometricGate(
                BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
                BiometricGateOptions(strength = BiometricStrength.WEAK),
            )
        }
    }

    @Test
    fun `the factory accepts the weak tier with biometrics only`() {
        createBiometricGate(
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_ONLY),
            BiometricGateOptions(strength = BiometricStrength.WEAK),
        )
    }
}
