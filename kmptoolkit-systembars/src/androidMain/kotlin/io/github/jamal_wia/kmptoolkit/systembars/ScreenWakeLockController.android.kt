package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import java.lang.ref.WeakReference

/**
 * Creates the Android [ScreenWakeLockController].
 *
 * **Call this from `Application.onCreate`, before any activity resumes** — for the same reason as
 * `createSystemBarsController`: a flag set on a controller created after the first resume has no
 * window to land on until the next one.
 *
 * @param context any `Context`; its application context is retained to track the currently
 *   resumed activity, which is whose window the flag is written to — that identity changes on
 *   every rotation, theme change and font-size change. The activity tracker behind it is shared
 *   by every `Context`-based factory of this module, so creating several controllers registers one
 *   `ActivityLifecycleCallbacks` on the `Application`, not one each.
 */
public fun createScreenWakeLockController(context: Context): ScreenWakeLockController =
    createScreenWakeLockController(SharedActivityAccess.forApplication(context.applicationContext as Application))

/**
 * Creates the Android [ScreenWakeLockController] over an [ActivityAccess] you own.
 *
 * Same reason as the system-bars overload of the same shape: the `Context` version keeps whichever
 * activity resumed last awake, so a review session's wake lock follows the user into a system file
 * picker hosted in this process. Narrow it with
 * `createActivityAccess(application) { it is MainActivity }`, or share one instance with the
 * system-bars controller so both agree on which window they mean.
 *
 * The access must outlive the controller: clear the controller (`setKeepScreenOn(false)`) before you
 * `release()` the access. A controller over a released access can no longer reach any window, so a
 * flag it set stays on that window.
 */
public fun createScreenWakeLockController(activityAccess: ActivityAccess): ScreenWakeLockController =
    AndroidScreenWakeLockController(activityAccess)

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
 * With several windows of the app resumed together (split screen, freeform, activity embedding), a hold
 * puts the flag on every resumed window at once and on each window that resumes while it stands.
 *
 * [pushedTo] is what makes re-applying safe in both directions:
 *
 *  - It must not clear a flag it never set. [ActivityAccess.addOnActivityResumedListener] replays
 *    the currently resumed activity into a new listener immediately, and a replay that ran the full
 *    apply with nothing held would wipe `FLAG_KEEP_SCREEN_ON` off a window some other owner had set
 *    it on.
 *  - It must clear a flag it DID set. A release takes the flag off every live window listed here at
 *    once, resumed or not (several windows of the app can be resumed together, and a paused one can
 *    be written too); only a window that cannot be written any more (finishing, destroyed,
 *    collected) is dropped, its flag dying with it. The resume callback below is the fallback for a
 *    window the release could not write.
 *
 * Several controllers count as one owner of the flag per window: a release clears it only when no
 * other live controller still holds that window ([KeepScreenOnHolders]), so per-screen controllers
 * whose lifetimes overlap (a navigation transition) do not undo each other.
 *
 * Hence: the resume callback acts only when this controller either wants the flag on, or previously
 * put it on a window and has not managed to take it off yet — and in the second case only for such a
 * window, since a different one never carried this controller's flag.
 *
 * Applying runs on the main thread, because callers may wire [setKeepScreenOn] off a state flow
 * whose collection dispatcher this interface does not constrain, while the activity tracker
 * delivers resume events on the main thread. Deciding what to apply and where on two different
 * threads would let them disagree about which activity is current, which is how the flag ends up
 * set on a live window while this controller believes it holds nothing.
 *
 * The one thing recorded on the caller's thread is [requested]. A call made off the main thread is
 * posted, and a later call made on the main thread runs inline, so the posted one would run last.
 * If each carried its own value, a hold queued by a background collector would land after the
 * screen's main-thread teardown released it, and the screen would never sleep. Recording the value
 * at the call and having every main-thread pass apply the latest one makes the last call win.
 */
internal class AndroidScreenWakeLockController(
    private val activityAccess: ActivityAccess,
) : ScreenWakeLockController {

    // What the caller asked for last. Written on the caller's thread, at the call, so that the order
    // of the calls decides it rather than the order in which their main-thread passes happen to run.
    @Volatile
    private var requested: Boolean = false

    // What this controller has applied. Main thread only.
    private var enabled: Boolean = false

    // Every window this controller turned the flag ON for and has not turned it off again — see
    // the class KDoc. More than one while the hold is on: an activity of the app can come on top of
    // another, and the hold puts the flag on each, so a release has to take it off each. Weak, because
    // it must outlive nothing, and empty for a controller that has never written the flag, which is
    // what keeps the resume callback off another owner's flag. Main thread only, like [enabled] and
    // [subscription]: every access runs from applyRequested or the resume listener, both delivered on
    // the main thread, so no volatile is needed.
    private val pushedTo: MutableList<WeakReference<Activity>> = mutableListOf()

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    // The resume listener, present only while there is something to re-apply: the flag is wanted, or
    // it was put on a window and not yet taken off. Main thread only. There is no release() to
    // unwind a listener that lived as long as the controller, and the tracker holds its listeners
    // strongly, so a controller that kept one for good could never be collected, even after its
    // owner cleared it.
    private var subscription: ActivitySubscription? = null

    override fun setKeepScreenOn(enabled: Boolean) {
        requested = enabled
        onMainThread { applyRequested() }
    }

    /** Main thread only. Applies whatever was asked for last, not what one particular call asked. */
    private fun applyRequested() {
        val target: Boolean = requested
        if (enabled == target) return
        enabled = target
        if (target) KeepScreenOnHolders.register(this) else KeepScreenOnHolders.unregister(this)
        if (target) subscribeToResumes()
        // No activity means the screen is already going away. The write is not lost: pushedTo
        // still names the window carrying the flag, so its next resume applies this release.
        if (target) {
            // Every resumed window, not only the newest: with two resumed already (split screen, freeform,
            // activity embedding) only the resume listener would otherwise reach a window that resumes later.
            activityAccess.forEachResumedActivity { activity -> applyFlag(activity, keepOn = true) }
        } else {
            activityAccess.withActivity { activity -> applyFlag(activity, keepOn = false) }
        }
        // A release takes the flag off every live window this controller put it on, resumed or not: a
        // window that is paused, stopped or still resumed beside the current one can be written all the
        // same, and one that is resumed already will not resume again to be told. Snapshot first,
        // applyFlag edits pushedTo.
        if (!target) {
            val live: List<Activity> = pushedTo.mapNotNull { it.get() }.filter { !it.isFinishing && !it.isDestroyed }
            live.forEach { window -> applyFlag(window, keepOn = false) }
        }
        // A window that cannot be written any more (finishing, destroyed, collected) is dropped by
        // unsubscribeWhenIdle: its flag dies with it. Whatever is left is owed its release at its next
        // resume, and the subscription lives until then.
        unsubscribeWhenIdle()
    }

    /** Main thread only. Idempotent. The tracker's immediate replay is harmless: adding is idempotent. */
    private fun subscribeToResumes() {
        if (subscription != null) return
        subscription = activityAccess.addOnActivityResumedListener { activity ->
            when {
                enabled -> applyFlag(activity, keepOn = true)
                pushedTo.any { it.get() === activity } -> applyFlag(activity, keepOn = false)
            }
            unsubscribeWhenIdle()
        }
    }

    /**
     * Main thread only. Idle means no flag is wanted and none is left on a live window. A window that
     * is finishing or destroyed will never resume, so a release owed to it can never be delivered and
     * its flag dies with it: it counts as taken off, exactly like one that has been collected. This is
     * the common teardown, where the activity finishes, pauses, and only then its owner releases.
     */
    private fun unsubscribeWhenIdle() {
        if (enabled) return
        // Windows that are gone, finishing or destroyed owe nothing: they drop out of the list.
        pushedTo.removeAll { reference ->
            val owed: Activity? = reference.get()
            owed == null || owed.isFinishing || owed.isDestroyed
        }
        if (pushedTo.isNotEmpty()) return
        subscription?.cancel()
        subscription = null
    }

    /** Main thread only. Whether this controller currently holds the flag and has put it on [activity]. */
    internal fun keepsOn(activity: Activity): Boolean = enabled && pushedTo.any { it.get() === activity }

    private fun applyFlag(activity: Activity, keepOn: Boolean) {
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (!KeepScreenOnHolders.heldByAnother(activity, except = this)) {
            // Several controllers are one owner of the flag per window: it is taken off only when no
            // other live controller still holds that window. The other one owns the window now and
            // clears it on its own release, so it is no longer owed by this controller either way.
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        pushedTo.removeAll { it.get() == null || it.get() === activity }
        if (keepOn) pushedTo.add(WeakReference(activity))
    }

    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}

/**
 * The controllers that currently hold the flag, so that one controller's release does not clear a
 * window another one still holds. Main thread only, by confinement and without any lock: every caller
 * runs from `applyRequested` or from the resume listener, both delivered on the main thread (D-21:
 * thread confinement, not a low-level primitive). Weak references, so the registry never becomes a
 * retention path for a controller its owner dropped.
 */
internal object KeepScreenOnHolders {

    private val holders: MutableList<WeakReference<AndroidScreenWakeLockController>> = mutableListOf()

    fun register(controller: AndroidScreenWakeLockController) {
        prune()
        if (holders.none { it.get() === controller }) holders.add(WeakReference(controller))
    }

    fun unregister(controller: AndroidScreenWakeLockController) {
        holders.removeAll { reference -> reference.get().let { it == null || it === controller } }
    }

    /** True when a live controller other than [except] holds the flag and has put it on [activity]. */
    fun heldByAnother(activity: Activity, except: AndroidScreenWakeLockController): Boolean {
        prune()
        return holders.any { reference -> reference.get().let { it != null && it !== except && it.keepsOn(activity) } }
    }

    private fun prune() {
        holders.removeAll { it.get() == null }
    }
}
