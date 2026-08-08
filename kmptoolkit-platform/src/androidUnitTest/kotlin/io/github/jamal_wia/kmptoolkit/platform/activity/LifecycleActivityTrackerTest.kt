package io.github.jamal_wia.kmptoolkit.platform.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The tracker's whole reason to exist is that it must not retain an `Activity`, so most of what is
 * asserted here is absence: after the activity goes away, nothing can be reached through the
 * tracker, and the tracker's own field holds nothing.
 *
 * The retention assertions are written against the weak reference rather than against a garbage
 * collector, deliberately. `System.gc()` is a hint, Robolectric's own machinery keeps activities
 * alive for the length of a test, and a test that depends on either is a test that fails at
 * random. Clearing the reference by hand proves the same property — that the only path from the
 * tracker to the activity is a weak one — and it proves it every time.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleActivityTrackerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application).also { it.register() }
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { controller -> controller.close() }
    }

    private fun launchActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }

    @Test
    fun `there is no activity before one resumes`() {
        assertNull(tracker.withActivity { it })
    }

    @Test
    fun `the block runs with the resumed activity and its result is returned`() {
        val controller: ActivityController<Activity> = launchActivity().setup()

        assertSame(controller.get(), tracker.withActivity { it })
        assertEquals("ok", tracker.withActivity { "ok" })
    }

    @Test
    fun `the block does not run once the activity has paused`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        controller.pause()

        var ran = false
        val result: Unit? = tracker.withActivity { ran = true }

        assertNull(result)
        assertTrue(!ran, "the block must not run when there is no resumed activity")
    }

    @Test
    fun `a paused activity is no longer referenced at all`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        assertNotNull(tracker.activityReferenceForTest())

        controller.pause()

        assertNull(
            tracker.activityReferenceForTest(),
            "the tracker must drop its reference when the activity pauses",
        )
    }

    @Test
    fun `a destroyed activity is no longer referenced even without a pause`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        // Straight to onActivityDestroyed, skipping the pause callback the framework would
        // normally deliver first — the belt-and-braces path.
        tracker.onActivityDestroyed(controller.get())

        assertNull(tracker.activityReferenceForTest())
        assertNull(tracker.withActivity { it })
    }

    @Test
    fun `the activity is reachable only through a weak reference`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        val reference: WeakReference<Activity> = assertNotNull(tracker.activityReferenceForTest())

        // Standing in for the garbage collector: if any strong path existed alongside this
        // reference, the tracker would keep answering after it is cleared.
        reference.clear()

        assertNull(tracker.withActivity { it })
    }

    @Test
    fun `a finishing activity is treated as absent`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        controller.get().finish()

        assertNull(tracker.withActivity { it })
        assertNull(
            tracker.activityReferenceForTest(),
            "a finishing activity must be dropped rather than merely refused",
        )
    }

    @Test
    fun `pausing an older activity does not clear the one that replaced it`() {
        val first: ActivityController<Activity> = launchActivity().setup()
        val second: ActivityController<Activity> = launchActivity().setup()

        // The real ordering during a transition: the new activity resumes, then the old one pauses.
        tracker.onActivityPaused(first.get())

        assertSame(second.get(), tracker.withActivity { it })
    }

    @Test
    fun `a listener added while an activity is resumed fires immediately`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        val seen: MutableList<Activity> = mutableListOf()

        tracker.addOnActivityResumedListener { activity -> seen.add(activity) }

        assertEquals(listOf(controller.get()), seen)
    }

    @Test
    fun `a listener added before any activity fires on the next resume`() {
        val seen: MutableList<Activity> = mutableListOf()
        tracker.addOnActivityResumedListener { activity -> seen.add(activity) }

        val controller: ActivityController<Activity> = launchActivity().setup()

        assertEquals(listOf(controller.get()), seen)
    }

    @Test
    fun `a listener fires again for the activity that replaces a recreated one`() {
        val seen: MutableList<Activity> = mutableListOf()
        tracker.addOnActivityResumedListener { activity -> seen.add(activity) }
        val first: ActivityController<Activity> = launchActivity().setup()
        first.pause()

        val second: ActivityController<Activity> = launchActivity().setup()

        assertEquals(listOf(first.get(), second.get()), seen)
    }

    @Test
    fun `a cancelled listener stops receiving activities`() {
        var calls = 0
        val subscription: ActivitySubscription =
            tracker.addOnActivityResumedListener { calls++ }

        subscription.cancel()
        launchActivity().setup()

        assertEquals(0, calls)
    }

    @Test
    fun `cancelling twice is harmless`() {
        val subscription: ActivitySubscription = tracker.addOnActivityResumedListener { }

        subscription.cancel()
        subscription.cancel()
    }

    @Test
    fun `release drops the current activity and stops tracking new ones`() {
        launchActivity().setup()

        tracker.release()

        assertNull(tracker.activityReferenceForTest())
        assertNull(tracker.withActivity { it })
        launchActivity().setup()
        assertNull(
            tracker.withActivity { it },
            "a released tracker must not pick an activity back up",
        )
    }

    @Test
    fun `release stops listeners from firing`() {
        var calls = 0
        tracker.addOnActivityResumedListener { calls++ }

        tracker.release()
        launchActivity().setup()

        assertEquals(0, calls)
    }

    @Test
    fun `releasing twice is harmless`() {
        tracker.release()
        tracker.release()
    }

    @Test
    fun `a listener added after release never fires`() {
        tracker.release()
        var calls = 0

        tracker.addOnActivityResumedListener { calls++ }
        launchActivity().setup()

        assertEquals(0, calls)
    }
}
