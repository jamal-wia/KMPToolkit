package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * `ActivitySubscription.cancel()` "releases whatever [the listener] captured": the caller may keep the
 * handle it was given for the life of its owner, and that must not keep the listener's captures alive.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerReleaseCaptureTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val trackers: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        trackers.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private class Payload

    private fun newTracker(): LifecycleActivityTracker =
        (createActivityAccess(application) as LifecycleActivityTracker).also { trackers.add(it) }

    /** Tries a bounded number of collections: a weak reference that never clears within them is retained. */
    private fun clearedAfterGc(reference: WeakReference<Payload>): Boolean {
        repeat(20) {
            System.gc()
            System.runFinalization()
            if (reference.get() == null) return true
            Thread.sleep(25)
        }
        return reference.get() == null
    }

    private fun subscribeWithCapture(tracker: LifecycleActivityTracker): Pair<ActivitySubscription, WeakReference<Payload>> {
        val payload = Payload()
        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener { payload.hashCode() }
        return subscription to WeakReference(payload)
    }

    private fun subscribeCancelAndForget(tracker: LifecycleActivityTracker): WeakReference<Payload> {
        val (subscription, captured) = subscribeWithCapture(tracker)
        subscription.cancel()
        return captured
    }

    @Test
    fun `control - a cancelled subscription whose handle is dropped no longer holds what its listener captured`() {
        val captured: WeakReference<Payload> = subscribeCancelAndForget(newTracker())

        assertNull(if (clearedAfterGc(captured)) null else captured.get(), "the control did not clear: the test's GC is unreliable")
    }

    @Test
    fun `a cancelled subscription whose handle the caller keeps no longer holds what its listener captured`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val (subscription, captured) = subscribeWithCapture(tracker)

        subscription.cancel()

        // The handle stays reachable on purpose: a consumer keeps it in a field until its owner goes away.
        assertNull(
            if (clearedAfterGc(captured)) null else captured.get(),
            "cancel() did not release what the listener captured while the handle was still held",
        )
        subscription.cancel() // keeps the handle strongly reachable up to here
    }

    @Test
    fun `a subscription of a released tracker whose handle the caller keeps no longer holds what its listener captured`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val (subscription, captured) = subscribeWithCapture(tracker)

        tracker.release()

        assertNull(
            if (clearedAfterGc(captured)) null else captured.get(),
            "release() did not release what the listener captured while the handle was still held",
        )
        subscription.cancel()
    }
}
