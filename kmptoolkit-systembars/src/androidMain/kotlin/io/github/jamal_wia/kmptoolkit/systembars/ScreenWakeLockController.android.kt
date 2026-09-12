package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Creates the Android [ScreenWakeLockController].
 *
 * @param context any `Context`; its application context is retained to track the currently
 *   resumed activity, which is whose window the flag is written to — that identity changes on
 *   every rotation, theme change and font-size change.
 */
public fun createScreenWakeLockController(context: Context): ScreenWakeLockController {
    val activityAccess: ActivityAccess = createActivityTracker(context.applicationContext as Application)
    return AndroidScreenWakeLockController(activityAccess)
}

/**
 * Android implementation via `Window.FLAG_KEEP_SCREEN_ON`.
 *
 * Holds the desired on/off state independently of any Activity instance and re-applies it through
 * [ActivityAccess]'s observer surface — the same pattern the Android [SystemBarsController] uses,
 * and for the same reason: on a configuration change (rotation, density, locale) the activity is
 * recreated, but a caller that outlives it (a retained view-model, a Decompose component) keeps
 * calling this same controller — and a freshly attached activity's window starts with the flag
 * UNSET regardless of what the previous window had.
 *
 * [pushedTo] is what makes re-applying safe in both directions:
 *
 *  - It must not clear a flag it never set. [ActivityAccess.addOnActivityResumedListener] replays
 *    the currently resumed activity into a new listener immediately, and a replay that ran the full
 *    apply with nothing held would wipe `FLAG_KEEP_SCREEN_ON` off a window some other owner had set
 *    it on.
 *  - It must clear a flag it DID set but could not take back. The activity tracker clears its
 *    reference on pause, before an owner's teardown may run — so a release that arrives with the
 *    screen already going away finds no activity to write to. Without re-applying that release on
 *    the next resume, the window would keep the flag for the rest of its life.
 *
 * Hence: the resume callback acts only when this controller either wants the flag on, or previously
 * put it on a window and has not managed to take it off yet — and in the second case only for that
 * same window, since a different one never carried this controller's flag.
 *
 * Everything — state included — runs on the main thread, because callers may wire [setKeepScreenOn]
 * off a state flow whose collection dispatcher this interface does not constrain, while the
 * activity tracker delivers resume events on the main thread. Deciding what to apply and where on
 * two different threads would let them disagree about which activity is current, which is how the
 * flag ends up set on a live window while this controller believes it holds nothing.
 */
internal class AndroidScreenWakeLockController(
    private val activityAccess: ActivityAccess,
) : ScreenWakeLockController {

    // What the caller wants.
    @Volatile
    private var enabled: Boolean = false

    // The window this controller last turned the flag ON for and has not turned it off again — see
    // the class KDoc. Weak, because it must outlive nothing, and null for a controller that has
    // never written the flag, which is what keeps the resume callback off another owner's flag.
    @Volatile
    private var pushedTo: WeakReference<Activity>? = null

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    init {
        // Lives for as long as the controller does — there is no release() to unwind it, matching
        // the interface's process-lifetime contract.
        activityAccess.addOnActivityResumedListener { activity ->
            when {
                enabled -> applyFlag(activity, keepOn = true)
                pushedTo?.get() === activity -> applyFlag(activity, keepOn = false)
            }
        }
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        onMainThread {
            if (this.enabled == enabled) return@onMainThread
            this.enabled = enabled
            // No activity means the screen is already going away. The write is not lost: pushedTo
            // still names the window carrying the flag, so its next resume applies this release.
            activityAccess.withActivity { activity -> applyFlag(activity, keepOn = enabled) }
        }
    }

    private fun applyFlag(activity: Activity, keepOn: Boolean) {
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        pushedTo = if (keepOn) WeakReference(activity) else null
    }

    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}
