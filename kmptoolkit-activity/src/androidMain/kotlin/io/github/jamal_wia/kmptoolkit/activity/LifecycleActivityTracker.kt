package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * The only reference to an activity this class holds, and it is weak. Atomic so a caller on another
     * thread clearing a stale activity cannot erase one that resumed in the meantime.
     */
    private val current: AtomicReference<WeakReference<Activity>?> = AtomicReference(null)

    /** Copy-on-write so a listener that subscribes or cancels during dispatch cannot break it. */
    private val listeners: CopyOnWriteArrayList<(Activity) -> Unit> = CopyOnWriteArrayList()

    @Volatile
    private var released: Boolean = false

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

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        if (released) return NoopSubscription
        listeners.add(listener)
        current.get()?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) listener(activity)
        }
        return ListenerSubscription(listener)
    }

    override fun release() {
        if (released) return
        released = true
        application.unregisterActivityLifecycleCallbacks(this)
        current.set(null)
        listeners.clear()
    }

    /**
     * An activity the caller does not track is ignored outright rather than replacing the current
     * one. That is the whole point of the predicate: a picker or a sign-in screen resuming over the
     * app must not inherit what the app asked for. The app's own activity was paused when it was
     * covered, so it is not reachable while covered either; it becomes current again when it resumes.
     */
    override fun onActivityResumed(activity: Activity) {
        if (!isTracked(activity)) return
        current.set(WeakReference(activity))
        listeners.forEach { listener -> listener(activity) }
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

    private inner class ListenerSubscription(
        private val listener: (Activity) -> Unit,
    ) : ActivitySubscription {
        override fun cancel() {
            listeners.remove(listener)
        }
    }

    private object NoopSubscription : ActivitySubscription {
        override fun cancel(): Unit = Unit
    }
}
