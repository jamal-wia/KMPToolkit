package io.github.jamal_wia.kmptoolkit.haptics

/**
 * Plays one short tactile event on the device.
 *
 * This is the only type your shared code should depend on. The concrete instance is built in
 * platform code — `createHapticFeedback(context)` on Android, `createHapticFeedback()` on iOS —
 * and handed to common code as this interface, which is what keeps a `Context` out of `commonMain`
 * without an `expect` declaration that would have to lie about its parameters.
 *
 * **Contract:**
 * - [perform] never throws. Anything that would have thrown — a missing permission, a device with
 *   no motor — comes back as a [HapticResult].
 * - [perform] does not block on the vibration finishing. It returns as soon as the platform has
 *   accepted the request; the pulse plays out afterwards.
 * - Implementations are safe to call from any thread. The iOS implementation hops to the main
 *   thread itself, because UIKit's feedback generators require it.
 * - Nothing is queued or coalesced. Two calls in quick succession produce two requests, and how
 *   they overlap is up to the platform — on Android a second `vibrate()` replaces the first.
 *
 * Implement it yourself when you need a decorator: a settings-aware wrapper that checks the user's
 * "vibration" preference before delegating is the common case, and is a few lines.
 */
public interface HapticFeedback {

    /**
     * Requests a haptic event of the given [type].
     *
     * @return whether the platform accepted the request; see [HapticResult]. Safe to ignore.
     */
    public fun perform(type: HapticType): HapticResult
}

/**
 * A [HapticFeedback] that does nothing and reports [HapticResult.UNAVAILABLE] for every call.
 *
 * Use it as the instance you inject when the user has turned haptics off in your settings, or on a
 * target where you have not wired a real implementation — that way the call sites in shared code
 * stay unconditional instead of growing a null check each.
 *
 * The returned instance is stateless; calling this repeatedly is free of consequence.
 */
public fun noOpHapticFeedback(): HapticFeedback = NoOpHapticFeedback

private object NoOpHapticFeedback : HapticFeedback {
    override fun perform(type: HapticType): HapticResult = HapticResult.UNAVAILABLE
}
