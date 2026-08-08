package io.github.jamal_wia.kmptoolkit.platform.activity

import android.app.Activity
import android.app.Application

/**
 * Scoped access to the activity that is resumed **right now**, for the handful of Android APIs
 * that cannot be reached from an application `Context`: window flags, dialogs, biometric prompts.
 *
 * Create one per process from [createActivityTracker] and pass it where it is needed. Everything
 * about the shape of this interface exists to make an activity leak hard:
 *
 * - **There is no getter.** You cannot obtain an `Activity` and put it in a field; you can only
 *   run a block while the tracker still considers one valid. A leak has to be written on purpose.
 * - **The reference is weak**, and it is cleared the moment the activity is paused or destroyed —
 *   whichever comes first — by the framework's own lifecycle callbacks, not by a `bind`/`unbind`
 *   pair a caller has to remember. There is no code path in which forgetting a call leaks.
 * - **Nothing global holds it.** The tracker is an ordinary object you own. It is registered with
 *   the `Application`, which outlives every activity — hence the weak reference — and [release]
 *   unregisters it.
 *
 * The one way left to leak is a listener from [addOnActivityResumedListener] that captures the
 * activity it is handed. Don't: use the activity inside the callback and let it go.
 */
public interface ActivityAccess {

    /**
     * Runs [block] with the currently resumed activity and returns its result, or returns `null`
     * without running it when there is none.
     *
     * `null` is a normal answer, not an error: the app may be in the background, or between two
     * activities during a configuration change. Callers either skip the work or record it and
     * replay it from [addOnActivityResumedListener].
     *
     * The activity is validated before [block] runs — an instance that is finishing or already
     * destroyed is treated as absent and dropped, so a stale reference cannot reach your code.
     * The block runs on the calling thread; most `Activity` APIs require the main thread, and this
     * does not move you there.
     */
    public fun <R> withActivity(block: (Activity) -> R): R?

    /**
     * Subscribes to activity resumption, and fires immediately if one is already resumed.
     *
     * The immediate replay matters for anything long-lived that reapplies state to a window: an
     * object constructed after the current activity resumed would otherwise wait for the *next*
     * resume before it ever got to act. It is also why a recreated activity — rotation, a theme
     * change, a font-size change — gets your state pushed onto its brand-new window, which starts
     * at platform defaults regardless of what the old one had.
     *
     * The listener is invoked synchronously on whatever thread the framework delivers
     * `onActivityResumed` on, which is the main thread.
     *
     * **Do not let the listener store the activity.** It is held strongly by the tracker until you
     * cancel it, so anything it captures lives as long as the tracker does.
     *
     * @return a handle to stop receiving callbacks. Cancel it when the listener's owner goes away;
     *   a listener that lives as long as the process never needs to.
     */
    public fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription

    /**
     * Unregisters from the `Application`, drops the current activity reference, and forgets every
     * listener.
     *
     * A process-lifetime tracker never needs this. It exists for tests and for a host that tears
     * down a whole component graph without ending the process. Idempotent.
     */
    public fun release()
}

/** A handle returned by [ActivityAccess.addOnActivityResumedListener]. */
public interface ActivitySubscription {

    /** Stops the listener from being called again, and releases whatever it captured. Idempotent. */
    public fun cancel()
}

/**
 * Creates the process-wide [ActivityAccess], registered against [application]'s activity lifecycle
 * callbacks.
 *
 * Call it once, from `Application.onCreate`, and hold the result for the process lifetime. Passing
 * an `Activity` here would be a mistake the compiler cannot catch, which is why the parameter is
 * `Application` and not `Context`.
 *
 * Creating a second tracker is harmless — they do not interfere — but each registers its own
 * lifecycle callbacks, so there is no reason to.
 */
public fun createActivityTracker(application: Application): ActivityAccess =
    LifecycleActivityTracker(application).also { tracker -> tracker.register() }
