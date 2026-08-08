package io.github.jamal_wia.kmptoolkit.biometric

import androidx.biometric.BiometricPrompt

/**
 * Builds the `PromptInfo` for one authentication attempt.
 *
 * The one rule that is easy to get wrong: **a prompt that allows the device credential must not set
 * a negative button.** That prompt supplies its own, and `androidx.biometric` throws from `build()`
 * rather than ignoring the extra label — which is why [BiometricPromptText.cancelLabel] is
 * documented as ignored under [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL] on Android.
 *
 * Below API 30 the credential fallback cannot be expressed as an authenticator mask at all (see
 * [allowedAuthenticators]), so it is requested through the deprecated `setDeviceCredentialAllowed`
 * flag. The deprecation is accurate — the flag *is* the old API — but on 24-29 it is the only one
 * that exists, and `minSdk` for this library is 24, so this is live code rather than history.
 */
@Suppress("DEPRECATION")
internal fun buildPromptInfo(
    prompt: BiometricPromptText,
    config: BiometricGateConfig,
): BiometricPrompt.PromptInfo {
    val builder: BiometricPrompt.PromptInfo.Builder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(prompt.title)
        .setSubtitle(prompt.subtitle)
        .setConfirmationRequired(config.requireExplicitConfirmation)

    val credentialAllowed: Boolean = config.policy == BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL
    when {
        credentialAllowed && supportsCombinedAuthenticators() ->
            builder.setAllowedAuthenticators(config.allowedAuthenticators())

        credentialAllowed ->
            builder.setDeviceCredentialAllowed(true)

        else -> builder
            .setAllowedAuthenticators(config.allowedAuthenticators())
            .setNegativeButtonText(prompt.cancelLabel)
    }
    return builder.build()
}
