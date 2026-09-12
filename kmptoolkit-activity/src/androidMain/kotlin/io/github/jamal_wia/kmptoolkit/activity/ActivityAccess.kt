package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application

/**
 * Scoped access to the activity that is resumed **right now**.
 *
 * Anything that has to reach a window, a permission launcher, or any other `Activity`-scoped API
 * from code that is not itself an activity needs this, and the identity it needs changes constantly:
 * a rotation, a theme change or a font-size change destroys the activity and builds another.
 *
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
     * A process-lifetime tracker never needs this. It exists for tests. Idempotent.
     */
    public fun release()
}

/** A handle returned by [ActivityAccess.addOnActivityResumedListener]. */
public interface ActivitySubscription {

    /** Stops the listener from being called again, and releases whatever it captured. Idempotent. */
    public fun cancel()
}

/**
 * Creates a process-wide [ActivityAccess], registered against [application]'s activity lifecycle
 * callbacks.
 *
 * Passing an `Activity` here would be a mistake the compiler cannot catch, which is why the
 * parameter is `Application` and not `Context`.
 *
 * **Create it in `Application.onCreate`, before any activity resumes.** The tracker learns which
 * activity is current only from `onActivityResumed`, and Android offers no public way to ask which
 * activity resumed before the callbacks were registered. An instance created later — for example a
 * lazy DI singleton first resolved during composition, which on Android runs after `onResume` —
 * answers `null` from [ActivityAccess.withActivity] until the next resume, and a listener added to it
 * gets no replay of the activity already on screen.
 *
 * @param isTracked decides which activities this instance is allowed to answer with. The default
 *   accepts every activity in the process, which is what you want when the thing being driven
 *   belongs to whichever activity the user is looking at.
 *
 *   Narrow it when it does not. An app whose process hosts activities it does not own the appearance
 *   of — a sign-in flow, a photo picker, a billing screen, a `ComponentActivity` some SDK declared
 *   in its own manifest — would otherwise have that activity styled, kept awake, or handed a
 *   permission launcher the moment it resumes, simply for being the most recent one. Passing
 *   `{ it is MainActivity }` makes the answer "the activity I mean", not "the activity on top".
 *
 *   An untracked activity resuming does not displace the tracked one: [withActivity] keeps
 *   answering with the tracked activity underneath, and stops only when *that* one goes away. The
 *   predicate is called on the main thread during `onActivityResumed` and should be a cheap type
 *   check.
 */
public fun createActivityAccess(
    application: Application,
    isTracked: (Activity) -> Boolean = { true },
): ActivityAccess = LifecycleActivityTracker(application, isTracked).also { it.register() }
