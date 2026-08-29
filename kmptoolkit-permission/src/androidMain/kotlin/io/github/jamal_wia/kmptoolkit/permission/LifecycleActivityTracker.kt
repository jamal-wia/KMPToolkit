package io.github.jamal_wia.kmptoolkit.permission

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [ActivityAccess] backed by `Application.ActivityLifecycleCallbacks`.
 *
 * The framework, not the caller, is what clears the reference here. A `bind`/`unbind` pair called
 * by hand from `onResume`/`onPause` is one forgotten override away from pinning a destroyed
 * activity, and the failure is invisible until a heap dump. Registering with the `Application`
 * means every activity in the process is tracked whether or not anyone remembered to wire it up.
 *
 * The reference itself is a [WeakReference] as a second line of defence: even if a lifecycle
 * callback were somehow missed, the garbage collector can still reclaim the activity, and
 * [withActivity] would simply start answering `null`.
 */
internal class LifecycleActivityTracker(
    private val application: Application,
) : ActivityAccess, Application.ActivityLifecycleCallbacks {

    /** The only reference to an activity this class holds, and it is weak. */
    @Volatile
    private var current: WeakReference<Activity>? = null

    /** Copy-on-write so a listener that subscribes or cancels during dispatch cannot break it. */
    private val listeners: CopyOnWriteArrayList<(Activity) -> Unit> = CopyOnWriteArrayList()

    @Volatile
    private var released: Boolean = false

    fun register() {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun <R> withActivity(block: (Activity) -> R): R? {
        val activity: Activity = current?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) {
            current = null
            return null
        }
        return block(activity)
    }

    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
        if (released) return NoopSubscription
        listeners.add(listener)
        current?.get()?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) listener(activity)
        }
        return ListenerSubscription(listener)
    }

    override fun release() {
        if (released) return
        released = true
        application.unregisterActivityLifecycleCallbacks(this)
        current = null
        listeners.clear()
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
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
        if (current?.get() === activity) current = null
    }

    /**
     * Test seam: the actual field, so a test can prove the tracker holds nothing but a weak
     * reference.
     */
    internal fun activityReferenceForTest(): WeakReference<Activity>? = current

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
