package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application

/**
 * Scoped access to the activity that is resumed **right now**, for the one Android API in this
 * module that cannot be reached from an application `Context`: `shouldShowRequestPermissionRationale`.
 *
 * Private to this module — [createActivityTracker] is called once, internally, by
 * [createPermissionHandler][io.github.jamal_wia.kmptoolkit.permission.createPermissionHandler].
 * Everything about the shape of this interface exists to make an activity leak hard:
 *
 * - **There is no getter.** You cannot obtain an `Activity` and put it in a field; you can only
 *   run a block while the tracker still considers one valid. A leak has to be written on purpose.
 * - **The reference is weak**, and it is cleared the moment the activity is paused or destroyed —
 *   whichever comes first — by the framework's own lifecycle callbacks, not by a `bind`/`unbind`
 *   pair a caller has to remember. There is no code path in which forgetting a call leaks.
 * - **Nothing global holds it.** The tracker is registered with the `Application`, which outlives
 *   every activity — hence the weak reference — and [release] unregisters it.
 */
internal interface ActivityAccess {

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
    fun <R> withActivity(block: (Activity) -> R): R?

    /**
     * Subscribes to activity resumption, and fires immediately if one is already resumed.
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
    fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription

    /**
     * Unregisters from the `Application`, drops the current activity reference, and forgets every
     * listener.
     *
     * A process-lifetime tracker never needs this. It exists for tests. Idempotent.
     */
    fun release()
}

/** A handle returned by [ActivityAccess.addOnActivityResumedListener]. */
internal interface ActivitySubscription {

    /** Stops the listener from being called again, and releases whatever it captured. Idempotent. */
    fun cancel()
}

/**
 * Creates this module's process-wide [ActivityAccess], registered against [application]'s
 * activity lifecycle callbacks.
 *
 * Passing an `Activity` here would be a mistake the compiler cannot catch, which is why the
 * parameter is `Application` and not `Context`.
 */
internal fun createActivityTracker(application: Application): ActivityAccess =
    LifecycleActivityTracker(application).also { tracker -> tracker.register() }
