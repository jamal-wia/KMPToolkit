package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * A subscription whose immediate replay throws: `addOnActivityResumedListener` throws, so the caller never
 * receives a handle. Nothing it cannot cancel may stay subscribed.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerThrowingReplayTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val trackers: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        trackers.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    @Test
    fun `a listener whose immediate replay throws is not left subscribed without a handle`() {
        val tracker: LifecycleActivityTracker = (createActivityAccess(application) as LifecycleActivityTracker).also { trackers.add(it) }
        val screen: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()
        var calls = 0

        assertFailsWith<IllegalStateException> {
            tracker.addOnActivityResumedListener {
                calls++
                if (calls == 1) error("the listener failed on its replay")
            }
        }

        assertEquals(1, calls)
        assertEquals(0, tracker.listenerCountForTest(), "the failed subscription stayed registered and its caller has no handle to cancel it")
        screen.pause()
        screen.resume()
        assertEquals(1, calls, "the listener the caller never got a handle for was called again on a later resume")
    }
}
