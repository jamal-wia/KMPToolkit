package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * `release()` "drops the current activity reference". A resume callback the framework had already started
 * (it dispatches from a snapshot of its callbacks, so an unregister does not stop one in flight) must not
 * put the activity back afterwards: the released tracker would keep answering [ActivityAccess.withActivity].
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerReleaseRaceTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        controllers.forEach { it.close() }
    }

    @Test
    fun `a resume already in flight when the tracker is released does not make the released tracker answer`() {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()
        val inside = CountDownLatch(1)
        val released = CountDownLatch(1)
        // The predicate is the first thing a resume runs: holding it open is "a resume in flight".
        val tracker: LifecycleActivityTracker =
            createActivityAccess(application) { _ ->
                inside.countDown()
                released.await(10, TimeUnit.SECONDS)
                true
            } as LifecycleActivityTracker
        val resume = thread(isDaemon = true) { tracker.onActivityResumed(activity) }
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the resume never started")

        tracker.release()
        released.countDown()
        resume.join(10_000)

        assertTrue(!resume.isAlive, "the in-flight resume did not finish")
        assertNull(tracker.withActivity { it }, "the released tracker answered with the activity of a resume that was in flight")
        assertNull(tracker.activityReferenceForTest(), "the released tracker holds a reference to the activity of a resume that was in flight")
    }
}
