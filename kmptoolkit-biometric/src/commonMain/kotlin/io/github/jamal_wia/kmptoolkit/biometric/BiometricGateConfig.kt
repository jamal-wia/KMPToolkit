package io.github.jamal_wia.kmptoolkit.biometric

/**
 * How a [BiometricGate] behaves, fixed once when you build it.
 *
 * It is constructor configuration rather than a per-call argument because
 * [BiometricGate.availability] has to answer for the same policy [BiometricGate.authenticate] will
 * use — a gate that accepts the device PIN is available on devices a biometric-only gate is not,
 * and two different answers from one object would be a trap. Need both behaviours? Build two gates;
 * they hold no state and cost nothing.
 *
 * Every field has a default, so `BiometricGateConfig()` is the strict, biometric-only gate.
 *
 * @param policy which credentials count as proof; see [BiometricPolicy]. Defaults to
 *   [BiometricPolicy.BIOMETRIC_ONLY] — the stricter of the two, because widening what counts as
 *   authentication is a security decision that should be typed out, not inherited from a default.
 * @param requireExplicitConfirmation whether a *passive* biometric — a face or an iris, anything
 *   that succeeds without the user deliberately doing something — must still be confirmed with a
 *   tap before the prompt returns. Defaults to `true`, matching the platform default, and it is the
 *   right default for anything consequential: without it, glancing at a phone someone else is
 *   holding up to your face is enough. Turn it off for a low-stakes unlock where the extra tap is
 *   pure friction. **Android only** — iOS's own Face ID prompt decides this itself, and the value
 *   is ignored there.
 */
public data class BiometricGateConfig(
    public val policy: BiometricPolicy = BiometricPolicy.BIOMETRIC_ONLY,
    public val requireExplicitConfirmation: Boolean = true,
)

/**
 * Which credentials a [BiometricGate] accepts as proof of the device owner.
 *
 * The choice is explicit — rather than a fallback the gate quietly offers when biometrics fail —
 * because the two are not the same security claim, and only the consuming app knows which one its
 * feature needs.
 */
public enum class BiometricPolicy {

    /**
     * Biometrics only: a fingerprint, a face, or an iris, at the platform's strong tier.
     *
     * On Android this is `BIOMETRIC_STRONG` — sensors the platform considers spoof-resistant
     * enough to gate a Keystore key with. Weak-tier sensors are deliberately not accepted; a gate
     * that silently downgraded to them would make a claim the hardware does not support.
     *
     * A device with no enrolment cannot satisfy this gate at all, and a permanent lockout cannot be
     * cleared through it — those users need whatever fallback your app provides. That is the price
     * of the stronger claim: *this specific person's body*, not *someone who knows the PIN*.
     */
    BIOMETRIC_ONLY,

    /**
     * Biometrics **or** the device credential — the PIN, pattern or passcode used to unlock the
     * device.
     *
     * A meaningfully weaker claim: it authenticates whoever knows the device's unlock secret, which
     * includes anyone who watched the user type it, and it is what a shoulder-surfer already has.
     * Do not use it to gate anything you would not let a person holding the unlocked phone see.
     *
     * What it buys is reach and recovery. It works on devices with no biometric hardware, on users
     * who never enrolled, and — crucially — it is the only way out of
     * [BiometricUnavailability.PERMANENTLY_LOCKED_OUT], since the credential prompt is exactly what
     * clears that lockout.
     *
     * The platforms implement it differently, and both differences are visible to you: on Android
     * the system prompt supplies its own negative button, so [BiometricPromptText.cancelLabel] is
     * ignored; on iOS the policy becomes `deviceOwnerAuthentication`, whose passcode fallback the
     * OS labels itself.
     */
    BIOMETRIC_OR_DEVICE_CREDENTIAL,
}
