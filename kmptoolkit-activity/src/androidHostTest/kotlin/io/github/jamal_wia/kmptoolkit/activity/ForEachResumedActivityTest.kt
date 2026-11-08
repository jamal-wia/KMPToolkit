package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** `forEachResumedActivity`: every tracked, live, resumed window once, earliest first. */
@RunWith(AndroidJUnit4::class)
class ForEachResumedActivityTest {

    private class Picker : Activity()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker = LifecycleActivityTracker(application) { it !is Picker }.also { it.register() }
    private val controllers: MutableList<ActivityController<*>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { it.close() }
    }

    private fun window(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    private fun visited(access: ActivityAccess = tracker): List<Activity> =
        mutableListOf<Activity>().also { out -> access.forEachResumedActivity { out += it } }

    @Test
    fun `two resumed windows are visited earliest first`() {
        val first: Activity = window().get()
        val second: Activity = window().get()

        assertEquals(listOf(first, second), visited())
        assertEquals(second, tracker.withActivity { it })
    }

    @Test
    fun `a paused window is skipped`() {
        val first: ActivityController<Activity> = window()
        val second: Activity = window().get()
        first.pause()

        assertEquals(listOf(second), visited())
    }

    @Test
    fun `a finishing or destroyed window is skipped`() {
        val finishing: ActivityController<Activity> = window()
        val destroyed: ActivityController<Activity> = window()
        val live: Activity = window().get()
        finishing.get().finish()
        destroyed.pause().stop().destroy()

        assertEquals(listOf(live), visited())
    }

    @Test
    fun `an untracked picker is never visited`() {
        val tracked: Activity = window().get()
        controllers.add(Robolectric.buildActivity(Picker::class.java).setup())

        assertEquals(listOf(tracked), visited())
    }

    @Test
    fun `nothing is visited after release`() {
        window()
        window()
        tracker.release()

        assertTrue(visited().isEmpty())
    }

    @Test
    fun `a block that finishes the window it is given does not change the visited set`() {
        val first: Activity = window().get()
        val second: Activity = window().get()
        val seen: MutableList<Activity> = mutableListOf()

        tracker.forEachResumedActivity { activity ->
            seen += activity
            activity.finish()
        }

        assertEquals(listOf(first, second), seen)
    }

    @Test
    fun `an access that implements only the abstract members visits what withActivity answers`() {
        val only: Activity = window().get()
        val access = object : ActivityAccess {
            override fun <R> withActivity(block: (Activity) -> R): R? = block(only)
            override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
                object : ActivitySubscription {
                    override fun cancel() = Unit
                }
            override fun release() = Unit
        }

        assertEquals(listOf(only), visited(access))
    }
}
