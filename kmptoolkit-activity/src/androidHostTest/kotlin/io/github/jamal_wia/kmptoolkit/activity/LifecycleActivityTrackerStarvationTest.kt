package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Memory held by the tracker, measured by garbage-collector reachability: what a kept handle, a queued
 * replay or a released tracker still pins. Every wait is bounded, and every collection loop is capped.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerStarvationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val trackers: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        trackers.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private class Payload

    private fun clearedAfterGc(references: List<WeakReference<*>>): Boolean {
        repeat(30) {
            System.gc()
            System.runFinalization()
            if (references.all { it.get() == null }) return true
            Thread.sleep(25)
        }
        return references.all { it.get() == null }
    }

    private fun newTracker(isTracked: (Activity) -> Boolean = { true }): LifecycleActivityTracker =
        (createActivityAccess(application, isTracked) as LifecycleActivityTracker).also { trackers.add(it) }

    private fun releasedTrackerWithoutHandle(): WeakReference<Payload> {
        val captured = Payload()
        val tracker: ActivityAccess = createActivityAccess(application) { captured.hashCode() >= 0 }
        tracker.addOnActivityResumedListener { }
        tracker.release()
        return WeakReference(captured)
    }

    @Test
    fun `control - a released tracker with no handle kept is collectable with what its predicate captured`() {
        val payload: WeakReference<Payload> = releasedTrackerWithoutHandle()

        assertTrue(clearedAfterGc(listOf(payload)), "the control did not clear: the test's GC is unreliable")
    }

    @Test
    fun `replays queued for cancelled subscriptions do not pin their listeners while the main thread is busy`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val activity: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()
        controllers.add(activity)
        val captured: MutableList<WeakReference<Payload>> = mutableListOf()
        val handles: MutableList<ActivitySubscription> = mutableListOf()
        // From another thread, so each add posts its replay to the main looper, which nothing drains here.
        val worker = thread(isDaemon = true) {
            repeat(3_000) {
                val payload = Payload()
                handles += tracker.addOnActivityResumedListener { payload.hashCode() }
                captured += WeakReference(payload)
            }
        }
        worker.join(20_000)
        assertTrue(!worker.isAlive, "the adding thread did not finish")
        assertEquals(3_000, tracker.listenerCountForTest())

        handles.forEach { it.cancel() }

        assertEquals(0, tracker.listenerCountForTest())
        assertTrue(
            clearedAfterGc(captured),
            "${captured.count { it.get() != null }} of 3000 listeners are still pinned although their subscriptions were cancelled",
        )
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(handles.size, 3_000)
    }

    @Test
    fun `replays queued when the tracker is released do not pin their listeners`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val activity: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()
        controllers.add(activity)
        val captured: MutableList<WeakReference<Payload>> = mutableListOf()
        val worker = thread(isDaemon = true) {
            repeat(1_000) {
                val payload = Payload()
                tracker.addOnActivityResumedListener { payload.hashCode() }
                captured += WeakReference(payload)
            }
        }
        worker.join(20_000)
        assertTrue(!worker.isAlive, "the adding thread did not finish")

        tracker.release()

        assertTrue(
            clearedAfterGc(captured),
            "${captured.count { it.get() != null }} of 1000 listeners are pinned by a released tracker's queued replays",
        )
        assertNull(tracker.activityReferenceForTest()?.get())
    }

    @Test
    fun `two hundred activities resumed and destroyed one after another are all collectable while one listener stays`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val seen: MutableList<WeakReference<Activity>> = mutableListOf()
        tracker.addOnActivityResumedListener { seen += WeakReference(it) }
        repeat(200) {
            val controller: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()
            controller.pause().stop().destroy()
        }

        assertEquals(200, seen.size)
        assertTrue(
            clearedAfterGc(seen),
            "${seen.count { it.get() != null }} of 200 destroyed activities are still reachable through the tracker",
        )
        assertEquals(1, tracker.listenerCountForTest())
    }

    @Test
    fun `eight threads adding listeners with replays queued, then a release, leave none pinned and none counted`() {
        val tracker: LifecycleActivityTracker = newTracker()
        val activity: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).setup()
        controllers.add(activity)
        val captured: java.util.concurrent.ConcurrentLinkedQueue<WeakReference<Payload>> = java.util.concurrent.ConcurrentLinkedQueue()
        val workers: List<Thread> = List(8) {
            thread(isDaemon = true) {
                repeat(500) {
                    val payload = Payload()
                    tracker.addOnActivityResumedListener { payload.hashCode() }
                    captured += WeakReference(payload)
                }
            }
        }
        workers.forEach { it.join(20_000) }
        assertTrue(workers.none { it.isAlive }, "an adding thread did not finish")
        assertEquals(4_000, tracker.listenerCountForTest())

        tracker.release()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(0, tracker.listenerCountForTest())
        assertTrue(
            clearedAfterGc(captured.toList()),
            "${captured.count { it.get() != null }} of 4000 listeners are pinned after release and the queued replays ran",
        )
    }
}
