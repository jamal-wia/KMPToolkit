package io.github.jamal_wia.kmptoolkit.biometric

/**
 * What became of a [BiometricGate.authenticate] call.
 *
 * Only [Authenticated] means the OS vouched for the user. Every other case is a different problem
 * with a different correct response, which is why this is a sealed hierarchy and not a `Boolean`:
 * a lockout wants your own credential fallback, an unenrolled device wants a shortcut to system
 * settings, a cancellation usually wants nothing at all. Collapsing them loses exactly the
 * information the caller needs.
 *
 * There is no `Failed(message)` carrying text to show. Presenting anything is the app's job; the
 * OS has already shown the user what went wrong inside its own prompt.
 */
public sealed interface BiometricResult {

    /**
     * The OS confirmed the user.
     *
     * How they proved it — a finger, a face, or the device PIN when the gate's
     * [BiometricGateConfig.policy] allows it — is deliberately not reported: the policy you
     * configured already decided what counts, and the two are not distinguishable on both
     * platforms anyway.
     *
     * **This is an assertion about a moment, not a key.** It unlocks nothing on its own; see the
     * warning on [BiometricGate].
     */
    public data object Authenticated : BiometricResult

    /**
     * The user dismissed the prompt — back gesture, the negative button, the system cancel button,
     * a swipe away — or the system dismissed it on their behalf (an incoming call, the screen
     * locking).
     *
     * Not an error and not worth an error message: the user said no. Return them to where they
     * were. The distinction between "the user tapped cancel" and "the system took the prompt away"
     * is not reliably reported by either platform, so this module does not claim to make it.
     */
    public data object Cancelled : BiometricResult

    /**
     * The user presented a credential and the OS did not recognise it, enough times that the
     * prompt gave up on its own.
     *
     * A single unrecognised finger is *not* this: the platforms keep the prompt on screen and let
     * the user try again, and this module stays silent while they do. By the time you see
     * `Rejected`, the sheet is gone. It says nothing about the user being an impostor — a wet
     * finger and a bad angle look the same to a sensor — so let them retry if your flow allows it,
     * but expect [Unavailable] with [BiometricUnavailability.LOCKED_OUT] soon after.
     */
    public data object Rejected : BiometricResult

    /**
     * The prompt could not run, or stopped running, because the device cannot authenticate the
     * owner at all.
     *
     * The same vocabulary [BiometricGate.availability] uses, arriving through the authentication
     * path — because the state can change between the two calls, and because some of these reasons
     * (a lockout in particular) are only discovered by trying.
     *
     * @param reason what is missing; see [BiometricUnavailability] and
     *   [BiometricUnavailability.isTransient].
     */
    public data class Unavailable(public val reason: BiometricUnavailability) : BiometricResult

    /**
     * Android only: there was no resumed `FragmentActivity` to host the system prompt, so nothing
     * was ever shown to the user.
     *
     * Android's biometric prompt is a fragment; it needs an activity that is on screen. You get
     * this when the call is made from the background, during a configuration change, or from an
     * `Activity` that does not extend `FragmentActivity` — the last of which is a wiring mistake
     * to fix rather than a runtime condition to handle.
     *
     * It is distinct from [Cancelled] because the user never saw anything to cancel: retrying once
     * your UI is back on screen is correct, and telling the user "you cancelled" would be a lie.
     */
    public data object NoPromptHost : BiometricResult

    /**
     * The platform refused for a reason that is neither a cancellation, nor a rejected credential,
     * nor a standing device limitation: a timeout, an out-of-space enrolment error, a
     * vendor-specific code, or a prompt the platform declined to display.
     *
     * It exists so that `authenticate` can keep its promise never to throw. Treat it as a retryable
     * failure and log [platformCode] — that is what makes a bug report from a device you do not own
     * actionable.
     *
     * @param platformCode the raw platform code: an `androidx.biometric.BiometricPrompt.ERROR_*`
     *   constant on Android, an `LAError` code on iOS. `null` when the failure arose at this
     *   library's own boundary and no platform code exists — the platforms number their errors
     *   independently, so a code is only meaningful together with the platform it came from.
     */
    public data class Failed(public val platformCode: Int? = null) : BiometricResult
}
