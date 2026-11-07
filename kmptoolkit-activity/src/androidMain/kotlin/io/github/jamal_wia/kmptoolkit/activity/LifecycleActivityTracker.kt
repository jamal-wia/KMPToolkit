package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [ActivityAccess] backed by `Application.ActivityLifecycleCallbacks`.
 *
 * The framework, not the caller, is what clears the reference here. A `bind`/`unbind` pair called
 * by hand from `onResume`/`onPause` is one forgotten override away from pinning a destroyed
 * activity, and the failure is invisible until a heap dump. Registering with the `Application`
 * means every activity in the process is seen whether or not anyone remembered to wire it up —
 * [isTracked] then decides which of those this instance is actually willing to answer with.
 *
 * The reference itself is a [WeakReference] as a second line of defence: even if a lifecycle
 * callback were somehow missed, the garbage collector can still reclaim the activity, and
 * [withActivity] would simply start answering `null`.
 */
internal class LifecycleActivityTracker(
    private val application: Application,
    private val isTracked: (Activity) -> Boolean = { true },
) : ActivityAccess, Application.ActivityLifecycleCallbacks {

    // java.util.concurrent on purpose: this module has no kotlinx-coroutines dependency, and its API
    // is non-suspending, fed by main-thread lifecycle callbacks and called from any thread.
    // The other atomic / volatile fields below (listeners, released) follow it; Registration.cancelled is guarded by its lock.
    /**
     * The only reference to an activity this class holds, and it is weak. Atomic so a caller on another
     * thread clearing a stale activity cannot erase one that resumed in the meantime.
     */
    private val current: AtomicReference<WeakReference<Activity>?> = AtomicReference(null)

    /**
     * Copy-on-write so a listener that subscribes or cancels during dispatch cannot break it. Each
     * entry is its own [Registration] object rather than the bare lambda, so that cancelling removes
     * exactly the subscription it was returned for, even when one lambda is added twice.
     */
    private val listeners: CopyOnWriteArrayList<Registration> = CopyOnWriteArrayList()

    /** Set before [release] clears [listeners] — see [addOnActivityResumedListener] for why. */
    private val released: AtomicBoolean = AtomicBoolean(false)

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    fun register() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun <R> withActivity(block: (Activity) -> R): R? {
        val reference: WeakReference<Activity> = current.get() ?: return null
        val activity: Activity = reference.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) {
            current.compareAndSet(reference, null)
            return null
        }
        return block(activity)
    }

    /**
     * The immediate replay runs where every other delivery runs: on the main thread. Called there,
     * it is synchronous, as before. Called from anywhere else, it is posted — running it on the
     * caller's thread would hand a window-touching listener the activity off the main thread, which
     * is exactly what the interface promises never happens.
     *
     * A posted replay is skipped if the subscription is cancelled first, if the activity is no longer
     * resumed by the time it runs, or if a real resume has already reached the listener in between —
     * that delivery already said everything the replay would have.
     */
    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        if (released.get()) return NoopSubscription
        beforeListenerAddedForTest()
        val onMainThread: Boolean = Looper.myLooper() == Looper.getMainLooper()
        val registration = Registration(this, listener, replayPending = !onMainThread)
        listeners.add(registration)
        // release() may have run between the check above and the add. It sets the flag before it
        // clears the list, so either that clear already removed this registration, or this re-check
        // sees the flag — without it, a released tracker would hold the listener for good.
        if (released.get()) {
            // Marked cancelled, not just removed: a delivery on the main thread may already hold the
            // registration, and release() has finished (or will finish) its loop without seeing it.
            registration.markCancelled()
            listeners.remove(registration)
            return NoopSubscription
        }
        if (onMainThread) {
            replayCurrentTo(registration)
        } else {
            mainHandler.post { if (registration.replayPending) replayCurrentTo(registration) }
        }
        return registration
    }

    override fun release() {
        // A second caller does not return early: it marks every registration cancelled as well, so
        // "once release() has returned, no listener is called" holds for every caller, including one
        // that lost the race (or a listener calling release() again while the first call waits for it).
        // A tracker-wide lock would deadlock with that re-entrant call, hence the repeated loop.
        if (released.compareAndSet(false, true)) {
            application.unregisterActivityLifecycleCallbacks(this)
            current.set(null)
        }
        // Through markCancelled(), not a bare flag: like cancel() it must not return while a delivery
        // to the listener is still running on the main thread.
        listeners.forEach { registration -> registration.markCancelled() }
        listeners.clear()
    }

    /**
     * An activity the caller does not track is ignored outright rather than replacing the current
     * one. That is the whole point of the predicate: a picker or a sign-in screen resuming over the
     * app must not inherit what the app asked for. The app's own activity was paused when it was
     * covered, so it is not reachable while covered either; it becomes current again when it resumes.
     */
    override fun onActivityResumed(activity: Activity) {
        if (released.get() || !isTracked(activity)) return
        val reference = WeakReference(activity)
        current.set(reference)
        // A resume already in flight when release() ran gets here after release() cleared the slot (the
        // framework dispatches from a snapshot, so unregistering does not stop it). release() sets the
        // flag before it clears the slot, so either its clear comes after the set above, or this re-check
        // sees the flag: either way a released tracker does not keep, or hand out, an activity.
        if (released.get()) {
            current.compareAndSet(reference, null)
            return
        }
        // The loop walks a snapshot: a listener cancelled by an earlier one in this very loop is
        // still in it, and "cancel stops the listener from being called again" must hold for it too.
        // deliver() re-checks the flag under the registration's lock, so it also holds for a cancel()
        // from another thread.
        listeners.forEach { registration -> registration.deliver(activity) }
    }

    /**
     * Clears on pause rather than on stop or destroy: the earliest point at which this activity is
     * no longer the one the user is interacting with.
     */
    override fun onActivityPaused(activity: Activity) {
        clearIfCurrent(activity)
    }

    /** Belt and braces: an activity destroyed without a matching pause still clears the slot. */
    override fun onActivityDestroyed(activity: Activity) {
        clearIfCurrent(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?): Unit = Unit
    override fun onActivityStarted(activity: Activity): Unit = Unit
    override fun onActivityStopped(activity: Activity): Unit = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle): Unit = Unit

    /**
     * Identity comparison, not equality: two activity instances of the same class are different
     * objects, and during a configuration change both exist at once.
     */
    private fun clearIfCurrent(activity: Activity) {
        val reference: WeakReference<Activity>? = current.get()
        if (reference?.get() === activity) current.compareAndSet(reference, null)
    }

    /**
     * Test seam: the actual field, so a test can prove the tracker holds nothing but a weak
     * reference.
     */
    internal fun activityReferenceForTest(): WeakReference<Activity>? = current.get()

    /** Test seam: how many listeners the tracker holds strongly right now. */
    internal fun listenerCountForTest(): Int = listeners.size

    /**
     * Test seam: runs between [addOnActivityResumedListener]'s released check and its insertion, so
     * a test can make [release] land exactly in that window.
     */
    internal var beforeListenerAddedForTest: () -> Unit = {}

    /** Main thread only, like every other delivery. */
    private fun replayCurrentTo(registration: Registration) {
        registration.deliverReplay()
    }

    /**
     * One subscription. Identity, not equality, is what [listeners] compares, which is why this is a
     * plain class and not a data class. Nested, not inner, and it lets go of the tracker as it lets go
     * of the listener: a handle the caller keeps after cancel() or release() pins neither.
     *
     * @param replayPending whether a posted immediate replay is still owed. Set at construction —
     *   before the registration is visible to [onActivityResumed] — so a resume delivered in between
     *   can clear it without a later write putting it back. Main thread only after construction; the
     *   constructor value is published by the listeners add and the Handler post.
     */
    private class Registration(
        tracker: LifecycleActivityTracker,
        listener: (Activity) -> Unit,
        var replayPending: Boolean,
    ) : ActivitySubscription {
        // A low-level lock on purpose (D-21): kmptoolkit-activity has no kotlinx-coroutines dependency,
        // and cancel() is a non-suspending public function that has to BLOCK until a delivery already
        // running on the main thread has returned, which a Mutex cannot do without runBlocking. A
        // ReentrantLock so a listener may cancel its own subscription from inside the call. One lock per
        // registration, held only around the check and the call: a thread that holds one registration's
        // lock never waits for another's, so two registrations cannot deadlock each other.
        private val lock: ReentrantLock = ReentrantLock()

        /** Dropped by [markCancelled] (under [lock]) so a handle the caller keeps does not pin what the listener captured. */
        private var listener: ((Activity) -> Unit)? = listener

        /** Dropped by [markCancelled] (under [lock]) so a handle the caller keeps does not pin the tracker, its application or its predicate. */
        private var tracker: LifecycleActivityTracker? = tracker

        /** Set by [markCancelled] only, and only under [lock]; every delivery re-checks it under the same lock. */
        private var cancelled: Boolean = false

        /** Calls the listener unless this subscription was cancelled, with no window for a cancel() in between. */
        fun deliver(activity: Activity) {
            lock.withLock {
                if (cancelled) return
                replayPending = false
                listener?.invoke(activity)
            }
        }

        /** The immediate replay: main thread only, skipped when cancelled, removed, or no activity is resumed. */
        fun deliverReplay() {
            lock.withLock {
                replayPending = false
                val owner: LifecycleActivityTracker = tracker ?: return
                if (cancelled || this !in owner.listeners) return
                owner.current.get()?.get()?.let { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) listener?.invoke(activity)
                }
            }
        }

        /** Waits for a delivery already running, then makes every later one a no-op. Returns the tracker it was let go of. */
        fun markCancelled(): LifecycleActivityTracker? = lock.withLock {
            cancelled = true
            listener = null
            val owner: LifecycleActivityTracker? = tracker
            tracker = null
            owner
        }

        override fun cancel() {
            markCancelled()?.listeners?.remove(this)
        }
    }

    private object NoopSubscription : ActivitySubscription {
        override fun cancel(): Unit = Unit
    }
}
