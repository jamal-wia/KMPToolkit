package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What this module hands `androidx.biometric`, and the API-level rule that makes the
 * device-credential path non-obvious.
 *
 * `PromptInfo.Builder.build()` validates its own arguments and **throws** rather than degrading:
 * an authenticator combination the running API cannot express, a prompt with both a negative button
 * and a device-credential fallback, or a prompt with neither, are all `IllegalArgumentException`s
 * from inside a UI callback. Every case here would be that exception if the mapping were wrong,
 * which is why these run under Robolectric at two SDK levels rather than being asserted from the
 * source.
 */
@RunWith(AndroidJUnit4::class)
class PromptInfoTest {

    private val promptText = BiometricPromptText(
        title = "Unlock",
        subtitle = "Confirm it is you",
        cancelLabel = "Not now",
    )

    @Test
    fun `a biometric-only prompt carries the consumer's copy verbatim`() {
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(promptText, BiometricGateConfig())

        assertEquals("Unlock", info.title.toString())
        assertEquals("Confirm it is you", info.subtitle.toString())
        assertEquals("Not now", info.negativeButtonText.toString())
    }

    @Test
    fun `a biometric-only prompt asks for the strong tier and nothing weaker`() {
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(promptText, BiometricGateConfig())

        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
        assertFalse(
            @Suppress("DEPRECATION") info.isDeviceCredentialAllowed,
            "a biometric-only gate must not accept the device PIN",
        )
    }

    @Test
    fun `explicit confirmation is on by default and can be turned off`() {
        assertTrue(buildPromptInfo(promptText, BiometricGateConfig()).isConfirmationRequired)
        assertFalse(
            buildPromptInfo(
                promptText,
                BiometricGateConfig(requireExplicitConfirmation = false),
            ).isConfirmationRequired,
        )
    }

    @Test
    @Config(sdk = [35])
    fun `a credential-accepting prompt on a modern API asks for both authenticators`() {
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(
            promptText,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
        )

        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            info.allowedAuthenticators,
        )
    }

    @Test
    @Config(sdk = [35])
    fun `a credential-accepting prompt sets no negative button`() {
        // The framework supplies its own, and build() rejects a prompt that sets both — which is
        // why BiometricPromptText.cancelLabel is documented as ignored in this mode on Android.
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(
            promptText,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
        )

        assertTrue(
            info.negativeButtonText.isNullOrEmpty(),
            "expected no negative button but found: ${info.negativeButtonText}",
        )
    }

    @Test
    @Config(sdk = [29])
    fun `a credential-accepting prompt below API 30 uses the legacy flag instead of the mask`() {
        // BIOMETRIC_STRONG or DEVICE_CREDENTIAL is explicitly unsupported on 28-29 and build()
        // throws for it; the deprecated flag is the only expression of the same intent there.
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(
            promptText,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
        )

        assertTrue(@Suppress("DEPRECATION") info.isDeviceCredentialAllowed)
        assertEquals(0, info.allowedAuthenticators)
        assertTrue(info.negativeButtonText.isNullOrEmpty())
    }

    @Test
    @Config(sdk = [29])
    fun `a biometric-only prompt below API 30 is unchanged`() {
        val info: BiometricPrompt.PromptInfo = buildPromptInfo(promptText, BiometricGateConfig())

        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG, info.allowedAuthenticators)
        assertEquals("Not now", info.negativeButtonText.toString())
    }

    @Test
    @Config(sdk = [29])
    fun `the authenticator mask below API 30 never combines the credential`() {
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL)
                .allowedAuthenticators(),
        )
        assertFalse(supportsCombinedAuthenticators())
    }

    @Test
    @Config(sdk = [30])
    fun `API 30 is the first level where the combination is expressible`() {
        assertTrue(supportsCombinedAuthenticators())
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL)
                .allowedAuthenticators(),
        )
    }

    @Test
    @Config(sdk = [24])
    fun `every configuration builds on the oldest supported API`() {
        // minSdk is 24. A prompt that throws at build() there would crash inside a UI post on a
        // device nobody tests on.
        listOf(
            BiometricGateConfig(),
            BiometricGateConfig(requireExplicitConfirmation = false),
            BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
            BiometricGateConfig(
                policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
                requireExplicitConfirmation = false,
            ),
        ).forEach { config -> buildPromptInfo(promptText, config) }
    }
}
