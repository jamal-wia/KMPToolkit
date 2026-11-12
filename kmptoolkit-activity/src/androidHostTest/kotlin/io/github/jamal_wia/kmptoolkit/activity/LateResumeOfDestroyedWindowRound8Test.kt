package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * A resume callback that reaches the tracker for a window that is already destroyed (not finishing), or
 * for one that is finishing: the tracker treats such a window as absent. Each read is the FIRST read after
 * the late callback in its own test, because every read prunes what it finds stale (the round 7 attempt
 * called `withActivity` first, which removed the entry, so the destroyed-window check of
 * `forEachResumedActivity` was never what decided the outcome).
 */
@RunWith(AndroidJUnit4::class)
class LateResumeOfDestroyedWindowRound8Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<*>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    private fun destroyedWindowResumedLate(): Activity {
        val controller: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()
        val window: Activity = controller.get()
        controller.pause().stop().destroy()
        assertTrue(window.isDestroyed)
        assertFalse(window.isFinishing)
        tracker.onActivityResumed(window)
        return window
    }

    private fun finishingWindowResumedLate(): Activity {
        val controller: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()
        val window: Activity = controller.get()
        controller.pause()
        window.finish()
        assertTrue(window.isFinishing)
        tracker.onActivityResumed(window)
        return window
    }

    @Test
    fun `a destroyed window reported resumed late is not visited by forEachResumedActivity`() {
        destroyedWindowResumedLate()

        val visited: MutableList<Activity> = mutableListOf()
        tracker.forEachResumedActivity { visited += it }

        assertEquals(emptyList(), visited)
    }

    @Test
    fun `a destroyed window reported resumed late is not the current window for withActivity`() {
        destroyedWindowResumedLate()

        assertNull(tracker.withActivity { it })
    }

    @Test
    fun `a finishing window reported resumed late is not visited by forEachResumedActivity`() {
        finishingWindowResumedLate()

        val visited: MutableList<Activity> = mutableListOf()
        tracker.forEachResumedActivity { visited += it }

        assertEquals(emptyList(), visited)
    }

    @Test
    fun `a finishing window reported resumed late is not the current window for withActivity`() {
        finishingWindowResumedLate()

        assertNull(tracker.withActivity { it })
    }

    @Test
    fun `a live window resumed before a destroyed one reported late stays current and visited alone`() {
        val live: Activity =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup().get()
        destroyedWindowResumedLate()

        val visited: MutableList<Activity> = mutableListOf()
        tracker.forEachResumedActivity { visited += it }
        assertEquals(listOf(live), visited)
        assertEquals(live, tracker.withActivity { it })
    }
}
