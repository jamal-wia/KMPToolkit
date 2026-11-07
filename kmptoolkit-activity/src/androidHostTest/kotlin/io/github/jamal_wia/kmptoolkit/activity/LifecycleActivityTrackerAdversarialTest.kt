package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Re-initialisation hazard of the activity tracker: trackers created and released in a loop across activity recreations must leave
 * nothing tracking.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerAdversarialTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val trackers: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        trackers.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private fun launchActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }

    private fun newTracker(): LifecycleActivityTracker =
        (createActivityAccess(application) as LifecycleActivityTracker).also { trackers.add(it) }

    @Test
    fun `fifty trackers created and released across activity recreations leave none of them tracking`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        val released: MutableList<LifecycleActivityTracker> = mutableListOf()
        val lateCalls = AtomicInteger()

        repeat(CYCLES) { cycle ->
            val tracker: LifecycleActivityTracker = newTracker()
            tracker.addOnActivityResumedListener { lateCalls.incrementAndGet() }
            controller.recreate()
            assertSame(controller.get(), tracker.withActivity { it }, "cycle $cycle: a live tracker follows the recreated activity")

            tracker.release()
            released += tracker
            controller.recreate()

            released.forEachIndexed { index, old ->
                assertNull(old.withActivity { it }, "cycle $cycle: tracker $index answers after release")
                assertNull(old.activityReferenceForTest(), "cycle $cycle: tracker $index tracks an activity after release")
                assertEquals(0, old.listenerCountForTest(), "cycle $cycle: tracker $index holds a listener after release")
            }
        }

        val before: Int = lateCalls.get()
        released.forEach { old -> old.addOnActivityResumedListener { lateCalls.incrementAndGet() } }
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(before, lateCalls.get(), "a listener of a released tracker fired on a later resume")
        released.forEach { old -> assertEquals(0, old.listenerCountForTest(), "a listener added after release is held") }

        // The last instance created after all that still works.
        val last: LifecycleActivityTracker = newTracker()
        controller.pause().resume()
        assertNotNull(last.withActivity { it })
    }

    private companion object {
        const val CYCLES: Int = 50
    }
}
