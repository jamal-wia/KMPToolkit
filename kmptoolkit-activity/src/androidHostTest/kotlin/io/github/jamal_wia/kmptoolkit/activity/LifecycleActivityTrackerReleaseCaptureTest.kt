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
 * `ActivitySubscription.cancel()` releases whatever the listener captured: once it ran and the caller
 * dropped the handle, nothing the listener captured stays reachable from the tracker.
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
    fun `a cancelled subscription whose handle is dropped no longer holds what its listener captured`() {
        val captured: WeakReference<Payload> = subscribeCancelAndForget(newTracker())

        assertNull(if (clearedAfterGc(captured)) null else captured.get(), "cancel() left what the listener captured reachable from the tracker")
    }
}
