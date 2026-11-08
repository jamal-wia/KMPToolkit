package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Two windows of one app on screen at once (split screen, freeform, a desktop display): both activities
 * are resumed. `ActivityAccess` is "the activity that is resumed right now"; when the more recently
 * resumed of the two goes away, the other one is still resumed and is the answer.
 */
@RunWith(AndroidJUnit4::class)
class MultiWindowTrackerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    private fun resumedWindow(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    @Test
    fun `the earlier window is the answer again when the later one pauses while it is still resumed`() {
        val first: ActivityController<Activity> = resumedWindow()
        val second: ActivityController<Activity> = resumedWindow()
        assertSame(second.get(), tracker.withActivity { it })

        second.pause()

        assertSame(first.get(), tracker.withActivity { it }, "an activity is resumed, yet the tracker says there is none")
    }

    @Test
    fun `a listener added after the later window paused is replayed the window that is still resumed`() {
        val first: ActivityController<Activity> = resumedWindow()
        val second: ActivityController<Activity> = resumedWindow()
        second.pause()
        val seen: MutableList<Activity> = mutableListOf()

        tracker.addOnActivityResumedListener { seen += it }

        assertEquals(listOf(first.get()), seen)
    }

    @Test
    fun `the earlier window is the answer again when the later one is finished and destroyed`() {
        val first: ActivityController<Activity> = resumedWindow()
        val second: ActivityController<Activity> = resumedWindow()

        second.get().finish()
        second.pause().stop().destroy()

        assertSame(first.get(), tracker.withActivity { it }, "an activity is resumed, yet the tracker says there is none")
    }

    @Test
    fun `an earlier window that was paused is not the answer when the later one pauses`() {
        val first: ActivityController<Activity> = resumedWindow()
        first.pause()
        val second: ActivityController<Activity> = resumedWindow()

        second.pause()

        assertNull(tracker.withActivity { it }, "a paused window was answered as the resumed one")
    }

    @Test
    fun `no listener is called when the earlier window becomes the answer again`() {
        val first: ActivityController<Activity> = resumedWindow()
        val second: ActivityController<Activity> = resumedWindow()
        val seen: MutableList<Activity> = mutableListOf()
        tracker.addOnActivityResumedListener { seen += it }
        assertEquals(listOf(second.get()), seen)

        second.pause()

        assertEquals(listOf(second.get()), seen, "no activity resumed, yet a listener was called")
        assertSame(first.get(), tracker.withActivity { it })
    }
}
