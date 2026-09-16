package io.github.jamal_wia.kmptoolkit.systembars

/**
 * Suppresses, or restores, the OS's automatic screen-idle timer for as long as the caller says it
 * should — Android `Window.FLAG_KEEP_SCREEN_ON` / iOS `UIApplication.idleTimerDisabled` behind one
 * interface.
 *
 * A general-purpose primitive, unrelated to [SystemBarsController]: any screen that must keep the
 * display awake for a bounded stretch of time — an active recording, a hands-free session, a
 * countdown the user isn't expected to keep tapping the screen through — creates one and calls
 * [setKeepScreenOn] directly. The two controllers happen to live in the same module because both
 * are thin wrappers around a per-window platform flag, reached through the same activity-tracking
 * machinery on Android.
 *
 * Idempotent by contract: calling [setKeepScreenOn] with the value it already holds is a cheap
 * no-op in every implementation, so callers can wire this straight off a boolean `StateFlow`
 * (`isActive.collect { setKeepScreenOn(it) }`) without adding their own edge-detection.
 *
 * **This must be the app's only caller of the underlying platform flag.** A second owner of
 * `FLAG_KEEP_SCREEN_ON` / `idleTimerDisabled` reintroduces exactly the conflict this interface
 * exists to prevent: the last write wins, and whichever owner wrote second decides whether the
 * screen ever sleeps again.
 *
 * **Callers must clear the flag when they no longer need it.** Letting the owning `LaunchedEffect`
 * or coroutine scope simply get cancelled is not enough — a scope cancellation does not itself flip
 * the flag back off. Call `setKeepScreenOn(false)` explicitly from the owner's teardown so the
 * platform flag never outlives the screen that requested it.
 */
public interface ScreenWakeLockController {

    /**
     * When `true`, suppresses the OS screen-idle timer for as long as it stays `true`. When
     * `false`, restores the platform's normal auto-lock behaviour.
     */
    public fun setKeepScreenOn(enabled: Boolean)
}
