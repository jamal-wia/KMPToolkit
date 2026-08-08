package io.github.jamal_wia.kmptoolkit.biometric

import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryDisconnected
import platform.LocalAuthentication.LAErrorBiometryLockout
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorBiometryNotPaired
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicy
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics

/**
 * The `LAPolicy` this gate evaluates.
 *
 * `deviceOwnerAuthenticationWithBiometrics` is Face ID / Touch ID / Optic ID and nothing else;
 * `deviceOwnerAuthentication` is the same, plus the passcode — including the passcode sheet iOS
 * shows by itself once biometry is locked out, which is what makes
 * [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL] the recoverable option on this platform too.
 */
internal fun BiometricGateConfig.laPolicy(): LAPolicy = when (policy) {
    BiometricPolicy.BIOMETRIC_ONLY -> LAPolicyDeviceOwnerAuthenticationWithBiometrics
    BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL -> LAPolicyDeviceOwnerAuthentication
}

/**
 * Translates the `NSError` from a failed `canEvaluatePolicy` into this module's vocabulary.
 *
 * [biometryAbsent] is the one thing the error code cannot tell us: `LAErrorBiometryNotAvailable`
 * means "biometry is not available", which covers a device that has no sensor **and** a device
 * whose owner denied this app's Face ID usage prompt. `LAContext.biometryType` separates them, and
 * only there — so the caller reads it and passes the answer in.
 *
 * A `null` code means `canEvaluatePolicy` returned false without populating an error, which the
 * framework does not promise never to do; [BiometricUnavailability.UNKNOWN] is the honest answer.
 */
internal fun mapAvailabilityError(code: Long?, biometryAbsent: Boolean): BiometricAvailability =
    BiometricAvailability.Unavailable(
        when (code) {
            LAErrorBiometryNotAvailable ->
                if (biometryAbsent) {
                    BiometricUnavailability.NO_HARDWARE
                } else {
                    BiometricUnavailability.HARDWARE_UNAVAILABLE
                }

            LAErrorBiometryNotPaired, LAErrorBiometryDisconnected ->
                BiometricUnavailability.HARDWARE_UNAVAILABLE

            // No enrolled biometric, and — for a credential-accepting gate — no passcode set at
            // all. Both are "the owner has not set up the thing we would check".
            LAErrorBiometryNotEnrolled, LAErrorPasscodeNotSet ->
                BiometricUnavailability.NOT_ENROLLED

            // iOS has one lockout, and it is the permanent kind: it clears only when the owner
            // enters the device passcode, never by waiting.
            LAErrorBiometryLockout -> BiometricUnavailability.PERMANENTLY_LOCKED_OUT

            else -> BiometricUnavailability.UNKNOWN
        },
    )

/**
 * Translates the `NSError` from a failed `evaluatePolicy` into this module's vocabulary.
 *
 * Availability reasons are shared with [mapAvailabilityError] — the same codes mean the same things
 * on both paths — with [biometryAbsent] taken as `false` here: by the time a prompt has run, "there
 * is no sensor" has already been answered by the availability query, and claiming
 * [BiometricUnavailability.NO_HARDWARE] from an authentication attempt that got as far as being
 * evaluated would be a guess.
 *
 * The judgement calls:
 * - **`LAErrorUserFallback` is a cancellation.** It is the user tapping the fallback button, and a
 *   biometric-only gate hides that button (it has no label from the consumer to put on it), so this
 *   is a rare path. What it is not is a request this library can act on — offering the passcode is
 *   [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL]'s job, chosen up front.
 * - **`LAErrorSystemCancel` and `LAErrorAppCancel` are cancellations**, not failures: the prompt
 *   was taken away (the app was backgrounded, another sheet appeared, or `invalidate()` was
 *   called). The user did not fail anything.
 * - **`LAErrorAuthenticationFailed` is [BiometricResult.Rejected]**: iOS keeps the sheet up through
 *   the individual mismatches and only reports this once it has given up.
 */
internal fun mapAuthenticationError(code: Long?): BiometricResult = when (code) {
    null -> BiometricResult.Failed()

    LAErrorUserCancel, LAErrorUserFallback, LAErrorSystemCancel, LAErrorAppCancel ->
        BiometricResult.Cancelled

    LAErrorAuthenticationFailed -> BiometricResult.Rejected

    LAErrorBiometryNotAvailable,
    LAErrorBiometryNotPaired,
    LAErrorBiometryDisconnected,
    LAErrorBiometryNotEnrolled,
    LAErrorPasscodeNotSet,
    LAErrorBiometryLockout,
    -> when (val availability: BiometricAvailability = mapAvailabilityError(code, biometryAbsent = false)) {
        is BiometricAvailability.Unavailable -> BiometricResult.Unavailable(availability.reason)
        BiometricAvailability.Available -> BiometricResult.Failed(code.toInt())
    }

    else -> BiometricResult.Failed(code.toInt())
}
