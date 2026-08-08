package io.github.jamal_wia.kmptoolkit.biometric

/**
 * Whether [BiometricGate.availability] found the device able to authenticate its owner.
 *
 * Two cases rather than a boolean, because "no" is not one answer: a phone without a sensor and a
 * phone whose owner has never enrolled a finger both say "no", and only one of them is worth
 * offering a "set up biometrics" shortcut for.
 */
public sealed interface BiometricAvailability {

    /**
     * The device can authenticate the owner under this gate's [BiometricGateConfig.policy] right
     * now.
     *
     * It is a snapshot, not a guarantee: enrolment, a lockout, or the user disabling biometrics in
     * system settings can invalidate it before the next call, so [BiometricGate.authenticate] can
     * still return [BiometricResult.Unavailable].
     */
    public data object Available : BiometricAvailability

    /**
     * The device cannot authenticate the owner, for the given [reason].
     *
     * @param reason what is missing, and therefore what — if anything — the user could do about it.
     */
    public data class Unavailable(public val reason: BiometricUnavailability) : BiometricAvailability
}

/**
 * Why a device cannot authenticate its owner.
 *
 * The distinctions are the ones that change what an app should do, and they are typed rather than
 * a message string because presenting any of this is the app's job — it owns its own wording. See
 * `docs/01-architecture.md`.
 */
public enum class BiometricUnavailability {

    /**
     * There is no biometric sensor on this device, and there never will be.
     *
     * Permanent. Hide the feature entirely rather than showing a switch that cannot be turned on.
     */
    NO_HARDWARE,

    /**
     * There is a sensor, but it cannot be used at the moment.
     *
     * Transient and not actionable by the user in any specific way: the sensor is busy serving
     * another app, the biometric subsystem is restarting, the user denied your app's Face ID usage
     * prompt, or an external biometric accessory is disconnected. Fall back to your own credential
     * flow and try again later; see [isTransient].
     */
    HARDWARE_UNAVAILABLE,

    /**
     * The hardware works but nothing is enrolled — no finger, no face, and (for a gate that
     * accepts the device credential) no PIN, pattern or passcode set either.
     *
     * The one reason with an obvious remedy: send the user to system settings to enrol. It is also
     * the reason most likely to change while your app is in the background, so re-check
     * [BiometricGate.availability] on resume rather than caching it.
     */
    NOT_ENROLLED,

    /**
     * Too many failed attempts; the sensor is refusing everyone for a cool-down period, typically
     * 30 seconds.
     *
     * [isTransient]: the same gate will work again shortly with no user action. Show your own
     * credential fallback meanwhile — do not sit in a retry loop, which on Android escalates
     * straight to [PERMANENTLY_LOCKED_OUT].
     */
    LOCKED_OUT,

    /**
     * The sensor is locked out until the user proves themselves with the device credential.
     *
     * No amount of waiting clears this one — it is what a [LOCKED_OUT] device escalates to after
     * further failures. A gate configured with
     * [BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL] can still authenticate here (the credential
     * prompt is exactly what clears the lockout); a biometric-only gate cannot, and must hand over
     * to your app's own fallback.
     */
    PERMANENTLY_LOCKED_OUT,

    /**
     * A security update the biometric subsystem depends on has not been applied, so the OS
     * distrusts its own sensor. Android only.
     *
     * Not recoverable from inside your app and not clearable by the user in a way you can direct
     * them to; treat it as [NO_HARDWARE] for UI purposes but keep it distinct for your logs,
     * because it is a device-fleet problem rather than a per-user one.
     */
    SECURITY_UPDATE_REQUIRED,

    /**
     * This OS version cannot satisfy the requested combination of authenticators.
     *
     * Android only, and a build-configuration fact rather than a device state: the platform has no
     * implementation of the strength this gate asked for at this API level. It will not change
     * while the app runs.
     */
    UNSUPPORTED,

    /**
     * The OS declined to say. Android's `BIOMETRIC_STATUS_UNKNOWN`, or a platform error code this
     * library does not recognise.
     *
     * Treat it as unavailable, but do not build UI that explains it — you would be guessing.
     * Attempting [BiometricGate.authenticate] anyway is legitimate: the prompt will produce a
     * definite answer where the availability query would not.
     */
    UNKNOWN,
}

/**
 * Whether waiting alone can clear this reason — that is, whether "try again later" is an honest
 * thing to offer.
 *
 * True for exactly [BiometricUnavailability.LOCKED_OUT] (the cool-down expires on its own) and
 * [BiometricUnavailability.HARDWARE_UNAVAILABLE] (whatever is occupying or disabling the sensor
 * ends without the user doing anything about it here). Every other reason needs the user to change
 * something — enrol, unlock with a credential — needs a different device, or will never change at
 * all; [BiometricUnavailability.UNKNOWN] is false because guessing in the optimistic direction
 * would build a retry loop that never terminates.
 *
 * Offering a retry for [BiometricUnavailability.NOT_ENROLLED] just walks the user into the same
 * wall; offering enrolment for [BiometricUnavailability.LOCKED_OUT] sends them to a settings screen
 * that will not help.
 */
public val BiometricUnavailability.isTransient: Boolean
    get() = this == BiometricUnavailability.LOCKED_OUT ||
        this == BiometricUnavailability.HARDWARE_UNAVAILABLE
