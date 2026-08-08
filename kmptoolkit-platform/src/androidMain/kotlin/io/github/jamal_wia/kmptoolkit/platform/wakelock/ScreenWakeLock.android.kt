package io.github.jamal_wia.kmptoolkit.platform.wakelock

import android.app.Activity
import android.view.WindowManager
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivityAccess

/**
 * Creates the Android [ScreenWakeLock], backed by the window flag `FLAG_KEEP_SCREEN_ON`.
 *
 * The window flag, not `PowerManager.WakeLock`: the flag is scoped to a foreground window, is
 * released automatically when that window goes away, and — the reason it is the right choice for a
 * library — needs **no `android.permission.WAKE_LOCK`**. A `PowerManager` wake lock would put a
 * permission into every consumer's manifest and could outlive the app's foreground, which is how
 * batteries disappear.
 *
 * The desired state is held here, independently of any activity, and reapplied whenever an
 * activity resumes. That is what makes it survive a configuration change: rotating the device
 * destroys the window that had the flag and creates a new one with platform defaults, so without
 * reapplying, a rotation would silently drop the keep-awake guarantee mid-session.
 *
 * @param activityAccess the process-wide tracker from `createActivityTracker`. This wake lock
 *   subscribes to it and never stores an activity of its own.
 * @param logger where a failed window write is reported.
 */
public fun createScreenWakeLock(
    activityAccess: ActivityAccess,
    logger: Logger = NoopLogger,
): ScreenWakeLock = AndroidScreenWakeLock(activityAccess, logger)

private class AndroidScreenWakeLock(
    private val activityAccess: ActivityAccess,
    private val logger: Logger,
) : ScreenWakeLock {

    /** `@Volatile`: written from a caller's thread, read from the main thread on resume. */
    @Volatile
    private var keepScreenOn: Boolean = false

    init {
        // The listener captures `this` (a process-lifetime object) and never the activity handed
        // to it — see ActivityAccess's leak note.
        activityAccess.addOnActivityResumedListener { activity -> apply(activity) }
    }

    override fun setKeepScreenOn(enabled: Boolean): WakeLockResult {
        keepScreenOn = enabled
        return activityAccess.withActivity { activity -> apply(activity) }
            ?: WakeLockResult.NO_ACTIVE_WINDOW
    }

    private fun apply(activity: Activity): WakeLockResult = runCatching {
        // Window flags must be written on the UI thread, and setKeepScreenOn is typically called
        // from a flow collector whose dispatcher the contract deliberately leaves unspecified.
        activity.runOnUiThread {
            if (keepScreenOn) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        WakeLockResult.APPLIED
    }.getOrElse { cause ->
        logger.w(cause) { "Could not apply FLAG_KEEP_SCREEN_ON" }
        WakeLockResult.FAILED
    }
}
