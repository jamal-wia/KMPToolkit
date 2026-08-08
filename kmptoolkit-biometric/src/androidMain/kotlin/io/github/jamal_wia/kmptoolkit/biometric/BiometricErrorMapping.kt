package io.github.jamal_wia.kmptoolkit.biometric

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

/**
 * The authenticator mask this policy asks `androidx.biometric` for.
 *
 * `BIOMETRIC_STRONG` rather than `BIOMETRIC_WEAK`: the weak tier includes sensors the platform will
 * not let you gate a Keystore key with, and accepting them would quietly weaken what
 * [BiometricResult.Authenticated] claims.
 *
 * The device-credential combination is only expressible this way from API 30. Below it the platform
 * has no implementation of `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, and `androidx.biometric`
 * rejects the prompt outright rather than degrading — so the mask below 30 is the biometric tier
 * alone, and the credential fallback is requested through the deprecated builder flag instead (see
 * [buildPromptInfo]).
 */
internal fun BiometricGateConfig.allowedAuthenticators(): Int =
    if (policy == BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL && supportsCombinedAuthenticators()) {
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    } else {
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

/**
 * Whether this API level can express "strong biometric **or** device credential" as an authenticator
 * mask.
 *
 * API 30 (`R`) is the cut-off documented by `androidx.biometric`: on 28 and 29 the combination is
 * explicitly unsupported, and `DEVICE_CREDENTIAL` on its own is unsupported before 30 as well.
 */
internal fun supportsCombinedAuthenticators(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

/**
 * Translates a `BiometricManager.canAuthenticate` status into this module's vocabulary.
 *
 * `BIOMETRIC_ERROR_HW_UNAVAILABLE` covers both a busy sensor and a temporary lockout — the platform
 * does not separate them at query time — so it maps to [BiometricUnavailability.HARDWARE_UNAVAILABLE]
 * and the lockout only becomes visible through [mapAuthenticationError]. An unrecognised status is
 * [BiometricUnavailability.UNKNOWN] rather than an exception: a future Android adding a status must
 * not crash an app built against this version.
 */
internal fun mapCanAuthenticate(status: Int): BiometricAvailability = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available

    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
        BiometricAvailability.Unavailable(BiometricUnavailability.NO_HARDWARE)

    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
        BiometricAvailability.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE)

    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
        BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED)

    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricAvailability.Unavailable(BiometricUnavailability.SECURITY_UPDATE_REQUIRED)

    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
        BiometricAvailability.Unavailable(BiometricUnavailability.UNSUPPORTED)

    else -> BiometricAvailability.Unavailable(BiometricUnavailability.UNKNOWN)
}

/**
 * Translates a terminal `BiometricPrompt.AuthenticationCallback.onAuthenticationError` code into
 * this module's vocabulary.
 *
 * Only terminal codes reach here. `onAuthenticationFailed` — an unrecognised finger with the sheet
 * still up — is not an error code at all and is deliberately not surfaced; see
 * [BiometricResult.Rejected].
 *
 * Three groupings are worth stating, because they are judgements rather than transcription:
 * - `ERROR_NEGATIVE_BUTTON` is a [BiometricResult.Cancelled], not a fallback request. The button's
 *   label is the consumer's, so the library cannot know whether it said "Cancel" or "Use PIN", and
 *   guessing would put words in their mouth.
 * - `ERROR_NO_DEVICE_CREDENTIAL` is [BiometricUnavailability.NOT_ENROLLED]: no PIN, pattern or
 *   passcode is set, which is the credential equivalent of no enrolled finger.
 * - `ERROR_TIMEOUT`, `ERROR_UNABLE_TO_PROCESS`, `ERROR_NO_SPACE` and `ERROR_VENDOR` stay
 *   [BiometricResult.Failed] with their code: each is retryable and none says anything durable
 *   about the device, so promoting them to a [BiometricResult.Unavailable] reason would overstate
 *   what happened.
 */
@Suppress("CyclomaticComplexMethod")
internal fun mapAuthenticationError(errorCode: Int): BiometricResult = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_CANCELED,
    -> BiometricResult.Cancelled

    BiometricPrompt.ERROR_HW_NOT_PRESENT ->
        BiometricResult.Unavailable(BiometricUnavailability.NO_HARDWARE)

    BiometricPrompt.ERROR_HW_UNAVAILABLE ->
        BiometricResult.Unavailable(BiometricUnavailability.HARDWARE_UNAVAILABLE)

    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    -> BiometricResult.Unavailable(BiometricUnavailability.NOT_ENROLLED)

    BiometricPrompt.ERROR_LOCKOUT ->
        BiometricResult.Unavailable(BiometricUnavailability.LOCKED_OUT)

    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
        BiometricResult.Unavailable(BiometricUnavailability.PERMANENTLY_LOCKED_OUT)

    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED ->
        BiometricResult.Unavailable(BiometricUnavailability.SECURITY_UPDATE_REQUIRED)

    else -> BiometricResult.Failed(errorCode)
}
