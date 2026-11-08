package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.lang.ref.WeakReference
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The wake lock over many window recreations and many controllers, measured by what stays reachable and
 * what stays subscribed: destroyed windows, the controller's own bookkeeping, listeners on the tracker.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockStarvationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val real: ActivityAccess = createActivityAccess(application)
    private val access = CountingAccess(real)
    private val built: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        real.release()
        built.forEach { it.close() }
    }

    private class CountingAccess(private val real: ActivityAccess) : ActivityAccess by real {
        var live: Int = 0
        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            live++
            val subscription: ActivitySubscription = real.addOnActivityResumedListener(listener)
            var cancelled = false
            return object : ActivitySubscription {
                override fun cancel() {
                    if (!cancelled) {
                        cancelled = true
                        live--
                    }
                    subscription.cancel()
                }
            }
        }
    }

    private fun Activity.keepsOn(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun idle() {
        var ran = 0
        while (ran < 10_000 && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    private fun clearedAfterGc(references: List<WeakReference<*>>): Boolean {
        repeat(30) {
            System.gc()
            System.runFinalization()
            if (references.all { it.get() == null }) return true
            Thread.sleep(25)
        }
        return references.all { it.get() == null }
    }

    /** Resumes a fresh window, then pauses and destroys it without finishing (a recreation); returns it weakly. */
    private fun passWindow(): WeakReference<Activity> {
        val screen: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        idle()
        screen.pause().stop().destroy()
        idle()
        return WeakReference(screen.get())
    }

    @Test
    fun `a hold kept across a hundred and fifty destroyed windows pins none of them and keeps one subscription`() {
        val controller: ScreenWakeLockController = createScreenWakeLockController(access)
        controller.setKeepScreenOn(true)
        idle()
        val passed: List<WeakReference<Activity>> = List(150) { passWindow() }
        assertEquals(1, access.live, "a hold must keep exactly one resume subscription however many windows passed")

        assertTrue(
            clearedAfterGc(passed),
            "${passed.count { it.get() != null }} of 150 destroyed windows are still reachable from the wake lock or the tracker",
        )
        val last: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        built += last
        idle()
        assertTrue(last.get().keepsOn(), "the hold did not reach the newest window")

        controller.setKeepScreenOn(false)
        idle()
        assertFalse(last.get().keepsOn())
        assertEquals(0, access.live, "a released hold left a resume subscription behind")
    }

    @Test
    fun `the bookkeeping of windows a hold put the flag on stays small once they are collected`() {
        val controller: ScreenWakeLockController = createScreenWakeLockController(access)
        controller.setKeepScreenOn(true)
        idle()
        val passed: List<WeakReference<Activity>> = List(150) { passWindow() }
        assertTrue(clearedAfterGc(passed))
        val last: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        built += last
        idle()

        val field = controller.javaClass.getDeclaredField("pushedTo").also { it.isAccessible = true }
        val entries: Int = (field.get(controller) as List<*>).size
        assertTrue(entries <= 2, "the controller still lists $entries windows it put the flag on, after all but one were collected")
        controller.setKeepScreenOn(false)
        idle()
    }

    @Test
    fun `three hundred overlapping controllers released in random order leave no flag no listener and are collectable`() {
        val random = Random(7)
        val screen: ActivityController<Activity> = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        built += screen
        idle()
        val held: MutableList<ScreenWakeLockController> = mutableListOf()
        val dropped: MutableList<WeakReference<ScreenWakeLockController>> = mutableListOf()
        repeat(300) {
            val controller: ScreenWakeLockController = createScreenWakeLockController(access)
            dropped += WeakReference(controller)
            controller.setKeepScreenOn(true)
            held += controller
            idle()
            if (held.size > 5 && random.nextBoolean()) {
                held.removeAt(random.nextInt(held.size)).setKeepScreenOn(false)
                idle()
            }
            assertTrue(screen.get().keepsOn(), "iteration $it: the flag was off while controllers still held it")
        }
        while (held.isNotEmpty()) {
            val one: ScreenWakeLockController = held.removeAt(random.nextInt(held.size))
            one.setKeepScreenOn(false)
            idle()
            if (held.isNotEmpty()) assertTrue(screen.get().keepsOn(), "the flag went off with ${held.size} controllers still holding it")
        }

        assertFalse(screen.get().keepsOn(), "every controller released but the window still carries the flag")
        assertEquals(0, access.live, "released controllers left resume subscriptions behind")
        assertTrue(clearedAfterGc(dropped), "${dropped.count { it.get() != null }} released controllers are still reachable")
    }
}
