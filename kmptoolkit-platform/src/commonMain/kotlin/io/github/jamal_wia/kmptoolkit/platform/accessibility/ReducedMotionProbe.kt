package io.github.jamal_wia.kmptoolkit.platform.accessibility

/**
 * Reads the OS-level "reduce motion" accessibility preference.
 *
 * Respecting it is not cosmetic: for users with vestibular disorders, large parallax and
 * scale-and-fade transitions cause real nausea, and both platforms treat honouring the setting as
 * an accessibility requirement. The usual shape of the fix is to swap a motion-heavy transition
 * for a cross-fade — not to remove the transition entirely, which loses the spatial cue that told
 * the user what just happened.
 *
 * Obtain one from the platform factory (`createReducedMotionProbe(context)` on Android,
 * `createReducedMotionProbe()` on iOS) and pass it into shared code as this interface.
 */
public interface ReducedMotionProbe {

    /**
     * Whether reduced motion is on **right now**.
     *
     * Read live on every call rather than cached, because the user can change it in Settings
     * while the app is running and expects the next screen to obey.
     *
     * There is no change notification here on purpose: Android exposes the setting as a
     * `Settings.Global` value with no first-class listener, so a common "observe" API would be a
     * `ContentObserver` on one platform and a notification on the other, with different timing and
     * different guarantees. Call this when you are about to animate; that is when the answer
     * matters.
     *
     * Never throws. A platform that refuses to answer is reported as `false` — assume motion is
     * acceptable — because failing closed would silently disable animation for everyone.
     *
     * @return `true` when the user asked for reduced motion.
     */
    public fun isReducedMotionEnabled(): Boolean
}
