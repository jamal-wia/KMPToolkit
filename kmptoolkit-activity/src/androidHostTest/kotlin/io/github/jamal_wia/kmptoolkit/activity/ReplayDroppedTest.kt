package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * `addOnActivityResumedListener` from another thread: the immediate replay "is dropped if you cancel first,
 * if the activity stops being resumed in the meantime, or if a real resume reaches the listener before it".
 * The cancel and real-resume cases are pinned elsewhere; this pins the "stops being resumed" ones, and
 * the replay of a tracker released meanwhile.
 */
@RunWith(AndroidJUnit4::class)
class ReplayDroppedTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    /** Adds the listener from another thread, so its replay is queued on the main looper, not run. */
    private fun addFromAnotherThread(listener: (Activity) -> Unit) {
        thread { tracker.addOnActivityResumedListener(listener) }.join()
    }

    @Test
    fun `a queued replay is dropped when the activity pauses before it runs`() {
        val activity: ActivityController<Activity> = resumedActivity()
        var calls = 0
        addFromAnotherThread { calls++ }

        // Pause without letting the main looper drain first: the posted replay is still waiting.
        tracker.onActivityPaused(activity.get())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, calls, "the replay delivered an activity that was no longer resumed")
    }

    @Test
    fun `a queued replay is dropped when the activity finishes before it runs`() {
        val activity: ActivityController<Activity> = resumedActivity()
        var calls = 0
        addFromAnotherThread { calls++ }

        activity.get().finish()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, calls, "the replay delivered a finishing activity")
    }

    @Test
    fun `a queued replay is dropped when the tracker is released before it runs`() {
        resumedActivity()
        var calls = 0
        addFromAnotherThread { calls++ }

        tracker.release()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, calls)
    }

    @Test
    fun `a queued replay delivers the activity that is resumed when it runs, once`() {
        val first: ActivityController<Activity> = resumedActivity()
        val seen: MutableList<Activity> = mutableListOf()
        addFromAnotherThread { seen += it }

        // First pauses and the second resumes before the replay runs; the real resume already told the listener.
        tracker.onActivityPaused(first.get())
        val second: ActivityController<Activity> = resumedActivity()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(second.get()), seen)
    }

    @Test
    fun `several replays queued from several threads each deliver once`() {
        val activity: ActivityController<Activity> = resumedActivity()
        val counts: IntArray = IntArray(6)
        val threads: List<Thread> = (0 until 6).map { index ->
            thread { tracker.addOnActivityResumedListener { counts[index]++ } }
        }
        threads.forEach { it.join(5_000) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(List(6) { 1 }, counts.toList(), "activity ${activity.get()}")
    }
}
