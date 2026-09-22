package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Further behaviour of a [BiometricGate], beyond the policy in [BiometricGateConfig] — the choices a
 * shared-device or kiosk deployment needs and a consumer app usually does not.
 *
 * A separate class rather than new fields on [BiometricGateConfig] so that existing callers, which
 * construct and copy that data class, keep compiling and linking unchanged. Pass both to the
 * three-argument `createBiometricGate` overload.
 *
 * @param strength which sensor tier counts; see [BiometricStrength]. Defaults to
 *   [BiometricStrength.STRONG], the tier [BiometricPolicy.BIOMETRIC_ONLY] has always meant.
 *   [BiometricStrength.WEAK] is only valid with [BiometricPolicy.BIOMETRIC_ONLY]: widening to the
 *   device credential already accepts something weaker than any sensor, and Android cannot combine
 *   the weak tier with it. The rule is checked on both platforms, but only Android has a second tier.
 * @param singleAttempt whether one unrecognised biometric ends the prompt. `false` — the default —
 *   leaves the platform's own retry loop in place: the prompt stays up and the user tries again, until
 *   the platform gives up with [BiometricResult.Rejected] or locks the sensor. `true` ends the prompt
 *   on the first non-match with [BiometricResult.Rejected], so that one [BiometricGate.authenticate]
 *   is exactly one sensor attempt. Choose it when each attempt is an event your app counts itself —
 *   a supervised check on a shared device — and when letting the platform retry would walk the user
 *   into a lockout only the device credential clears. With a policy that allows the device credential,
 *   the first non-match also ends the prompt before the user could switch to the credential.
 *   **Android only**; iOS offers no such control.
 * @param enrollmentThrottle the shortest interval between two [BiometricGate.launchEnrollment] calls
 *   that actually start a settings screen; a call inside the window returns
 *   [BiometricEnrollmentLaunch.THROTTLED]. It keeps a double tap from stacking two settings tasks.
 *   Must not be negative; [Duration.ZERO] turns throttling off.
 * @throws IllegalArgumentException if [enrollmentThrottle] is negative.
 * @since 1.5.0
 */
public class BiometricGateOptions(
    public val strength: BiometricStrength = BiometricStrength.STRONG,
    public val singleAttempt: Boolean = false,
    public val enrollmentThrottle: Duration = 1.seconds,
) {
    init {
        require(!enrollmentThrottle.isNegative()) { "enrollmentThrottle must not be negative, was $enrollmentThrottle" }
    }

    override fun equals(other: Any?): Boolean =
        other is BiometricGateOptions &&
            strength == other.strength &&
            singleAttempt == other.singleAttempt &&
            enrollmentThrottle == other.enrollmentThrottle

    override fun hashCode(): Int {
        var result: Int = strength.hashCode()
        result = 31 * result + singleAttempt.hashCode()
        result = 31 * result + enrollmentThrottle.hashCode()
        return result
    }

    override fun toString(): String =
        "BiometricGateOptions(strength=$strength, singleAttempt=$singleAttempt, " +
            "enrollmentThrottle=$enrollmentThrottle)"
}

/**
 * The sensor tier a biometric-only [BiometricGate] accepts. **Android only** — iOS has one tier.
 *
 * @since 1.5.0
 */
public enum class BiometricStrength {

    /**
     * Android's `BIOMETRIC_STRONG` (Class 3): sensors the platform considers spoof-resistant enough to
     * gate a Keystore key with.
     */
    STRONG,

    /**
     * Android's `BIOMETRIC_WEAK` (Class 2) and above — which adds the camera-based face unlock of many
     * phones and tablets. It is a weaker claim than [STRONG]: such a sensor may accept a good enough
     * photograph. Use it where reaching those devices matters more than that, and where the result
     * gates nothing a Keystore key protects.
     */
    WEAK,
}

/**
 * What became of a [BiometricGate.launchEnrollment] call.
 *
 * @since 1.5.0
 */
public enum class BiometricEnrollmentLaunch {

    /** A system screen for enrolling or managing biometrics was started. */
    LAUNCHED,

    /** Nothing was started: the previous launch was within [BiometricGateOptions.enrollmentThrottle]. */
    THROTTLED,

    /**
     * Nothing was started: the platform has no such screen (iOS, or an Android device that resolves
     * none of the candidates), or, on Android, the app's own `SystemScreenLauncher` returned `false`
     * or threw. Point the user at the system settings yourself.
     */
    UNAVAILABLE,
}
