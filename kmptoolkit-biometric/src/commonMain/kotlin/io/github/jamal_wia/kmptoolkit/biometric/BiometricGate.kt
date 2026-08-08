package io.github.jamal_wia.kmptoolkit.biometric

/**
 * Asks the operating system to confirm that the person holding the device is the device's owner.
 *
 * This is the only type your shared code should depend on. The concrete instance is built in
 * platform code — `createBiometricGate(activityAccess, config)` on Android,
 * `createBiometricGate(config)` on iOS — and handed to common code as this interface, which is
 * what keeps `FragmentActivity` and `LAContext` out of `commonMain` without an `expect`
 * declaration that would have to lie about its parameters.
 *
 * **It is a gate, not a keystore.** A successful [authenticate] means "the OS says this is the
 * owner"; it does not encrypt, sign, or unlock anything. Code that treats the returned value as
 * the *only* thing standing between an attacker and a secret has a bypass — see
 * `docs/kmptoolkit-biometric/01-overview.md`, "What this is not", and use
 * `kmptoolkit-storage`'s encrypted store for data that must survive a compromised process.
 *
 * **Contract:**
 * - Neither function throws for a platform condition. A device with no sensor, an unenrolled user,
 *   a lockout, a dismissed sheet — all of them come back as a [BiometricAvailability] or a
 *   [BiometricResult]. The only exception either can raise is [kotlin.coroutines.cancellation.CancellationException],
 *   because [authenticate] is a well-behaved suspending function.
 * - [authenticate] is **cancellable**. Cancelling the calling coroutine dismisses the system
 *   prompt; no [BiometricResult] is delivered in that case, the `CancellationException` is.
 * - [authenticate] does not pre-check [availability]. It asks the OS, which is the only party that
 *   can answer at the moment the prompt would appear, and reports what it says. Calling
 *   [availability] first is for *deciding what UI to show*, not for guarding the call.
 * - Both are safe to call from any thread; the implementations hop to the platform's UI thread
 *   themselves where the platform requires it.
 * - One prompt at a time. Starting a second [authenticate] while one is on screen is undefined
 *   across platforms — Android replaces the prompt, iOS may reject it — so don't.
 *
 * Implement it yourself when you need a decorator: caching a successful authentication for a grace
 * period, or short-circuiting when the user turned the app lock off in your settings, are both a
 * few lines around a delegate.
 */
public interface BiometricGate {

    /**
     * Whether the device can authenticate the owner right now, and if not, why not.
     *
     * The answer is about the policy this gate was configured with (see [BiometricGateConfig]): a
     * gate that accepts the device PIN can be [BiometricAvailability.Available] on a phone with no
     * fingerprint enrolled, while a biometric-only gate on the same phone reports
     * [BiometricUnavailability.NOT_ENROLLED].
     *
     * Use it to decide what to show — hide an "unlock with biometrics" switch on hardware that has
     * none, offer an enrolment shortcut when nothing is enrolled — not to guard [authenticate].
     * The answer can go stale between the two calls: the user can enrol, unenrol, or lock
     * themselves out while your screen is on.
     */
    public suspend fun availability(): BiometricAvailability

    /**
     * Shows the system authentication prompt and suspends until the OS decides.
     *
     * @param prompt the copy the OS will render. This library ships no wording of its own, so
     *   every string is yours — see [BiometricPromptText].
     * @return what the OS made of it; see [BiometricResult] for what each outcome should make your
     *   app do.
     */
    public suspend fun authenticate(prompt: BiometricPromptText): BiometricResult
}
