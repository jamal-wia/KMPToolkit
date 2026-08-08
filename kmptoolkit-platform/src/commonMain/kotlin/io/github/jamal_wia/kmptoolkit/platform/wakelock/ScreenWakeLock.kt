package io.github.jamal_wia.kmptoolkit.platform.wakelock

/**
 * Keeps the screen awake for as long as a feature genuinely owns it — a recording session, a
 * countdown, a navigation view, a recipe step the user is following with their hands full.
 *
 * Obtain one from the platform factory (`createScreenWakeLock(activityTracker)` on Android,
 * `createScreenWakeLock()` on iOS) and pass it into shared code as this interface.
 *
 * **This is a screen-on request, not a CPU wake lock.** It suppresses the OS idle timer while the
 * app is in the foreground (Android's `FLAG_KEEP_SCREEN_ON`, iOS's
 * `UIApplication.isIdleTimerDisabled`) and does nothing for background work. That is also why it
 * needs no `WAKE_LOCK` permission — see `docs/kmptoolkit-platform/05-platform-notes.md`.
 */
public interface ScreenWakeLock {

    /**
     * Requests that the screen stay on ([enabled] `= true`) or releases that request.
     *
     * Idempotent by contract: setting the value it already holds is a cheap no-op, so a caller can
     * wire this straight off a boolean state without edge-detecting first.
     *
     * **You must set it back to `false`.** Nothing releases it for you — not the coroutine scope
     * that set it, not the screen going away. Pair every `true` with a `false` in the owning
     * component's dispose/destroy hook, or the user's phone stays lit until they leave the app.
     *
     * @return whether the platform took the request; see [WakeLockResult].
     */
    public fun setKeepScreenOn(enabled: Boolean): WakeLockResult
}

/**
 * What became of a [ScreenWakeLock.setKeepScreenOn] call.
 *
 * Typed rather than a `Boolean` so that "there was no window to apply it to" is distinguishable
 * from "the platform refused" — the first is a normal, transient state that resolves itself, the
 * second is not.
 */
public enum class WakeLockResult {

    /**
     * The request was applied, or was already in effect.
     *
     * On iOS the write is queued to the main thread, so this means "handed to UIKit". On Android
     * it means the window flag was set on the currently resumed activity.
     */
    APPLIED,

    /**
     * Android only: there is no resumed activity to hold the window flag right now, so the request
     * was **recorded but not applied**.
     *
     * This is expected rather than exceptional — the app is backgrounded, or an activity is being
     * recreated on rotation. The wake lock remembers what you asked for and applies it to the next
     * activity that resumes, so a caller normally ignores this value. It matters only if you were
     * about to tell the user their screen will stay on.
     */
    NO_ACTIVE_WINDOW,

    /**
     * The platform rejected the request — a dead window, a system service that went away.
     *
     * Rare and device-specific. It exists so that `setKeepScreenOn` can keep its promise never to
     * throw: a convenience feature must not crash the screen that asked for it.
     */
    FAILED,
}
