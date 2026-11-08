package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.lang.ref.WeakReference
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
 * Wake-lock and system-bars controllers created per screen over one shared [ActivityAccess], across
 * many activity recreations: only a controller that is live acts, nothing acts after release, and a
 * cleared or released controller can be collected. Everything is bounded: fixed counts, capped GC loops.
 */
@RunWith(AndroidJUnit4::class)
class ActivityChurnTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val built: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        access.release()
        built.forEach { it.close() }
    }

    private fun Activity.keepsOn(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun Activity.darkStatusIcons(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars

    private fun newActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { built += it }.create().start()

    private fun idle() {
        var ran = 0
        while (ran < 10_000 && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    private fun collected(reference: WeakReference<*>): Boolean {
        repeat(40) {
            if (reference.get() == null) return true
            System.gc()
            Thread.sleep(10)
        }
        return reference.get() == null
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

    @Test
    fun `twenty per-screen wake locks over one shared access act only while held and are then collectable`() {
        val dropped: MutableList<WeakReference<ScreenWakeLockController>> = mutableListOf()
        var previous: ActivityController<Activity>? = null
        repeat(20) { i ->
            val controller: ScreenWakeLockController = createScreenWakeLockController(access)
            dropped += WeakReference(controller)
            val screen: ActivityController<Activity> = newActivity()
            previous?.pause()
            screen.resume()
            idle()
            assertFalse(screen.get().keepsOn(), "iteration $i: a window carries the flag before its screen asked")

            controller.setKeepScreenOn(true)
            idle()
            assertTrue(screen.get().keepsOn(), "iteration $i: the live controller did not hold the flag")

            // Teardown the way a screen does it: the window pauses, then the owner clears the flag.
            screen.pause()
            controller.setKeepScreenOn(false)
            idle()
            screen.stop().destroy()
            previous = null
        }
        val last: ActivityController<Activity> = newActivity().also { it.resume() }
        idle()
        assertFalse(last.get().keepsOn(), "a dropped controller put the flag on a later window")
        // Every cleared controller but the last has seen a later resume, which ends its subscription.
        val stillHeld: Int = dropped.dropLast(1).count { !collected(it) }
        assertEquals(0, stillHeld, "cleared and dropped controllers still reachable from the tracker")
    }

    @Test
    fun `a hold released and taken again across three windows leaves no window with the flag once all are back`() {
        val a: ActivityController<Activity> = newActivity().also { it.resume() }
        val controller: ScreenWakeLockController = createScreenWakeLockController(access)
        controller.setKeepScreenOn(true)
        idle()
        val b: ActivityController<Activity> = newActivity()
        a.pause(); b.resume(); idle()
        val c: ActivityController<Activity> = newActivity()
        b.pause(); c.resume(); idle()

        controller.setKeepScreenOn(false); idle()
        controller.setKeepScreenOn(true); idle()
        assertTrue(c.get().keepsOn())
        // the user goes back to a, then the hold ends while a is on top
        c.pause(); a.resume(); idle()
        assertTrue(a.get().keepsOn())
        controller.setKeepScreenOn(false); idle()
        assertFalse(a.get().keepsOn())

        for (other in listOf(b, c)) {
            a.pause()
            other.resume()
            idle()
            assertFalse(other.get().keepsOn(), "a window kept the flag after the hold ended")
        }
    }

    @Test
    fun `a system bars controller restyles each of twenty recreated windows and styles none after release`() {
        val controller: SystemBarsController = createSystemBarsController(access, SystemBarsConfig.ForLightBackground)
        var previous: ActivityController<Activity>? = null
        repeat(20) { i ->
            val next: ActivityController<Activity> = newActivity()
            previous?.pause()
            next.resume()
            idle()
            assertTrue(next.get().darkStatusIcons(), "iteration $i: the new window was not styled")
            previous?.stop()?.destroy()
            previous = next
        }
        controller.release()
        previous?.pause()
        val after: ActivityController<Activity> = newActivity().also { it.resume() }
        idle()
        assertFalse(after.get().darkStatusIcons(), "a released controller styled a window that resumed afterwards")
    }

    @Test
    fun `a released system bars controller is collectable and leaves no listener on the shared access`() {
        val counting = CountingAccess(access)
        var controller: SystemBarsController? = createSystemBarsController(counting, SystemBarsConfig.ForLightBackground)
        val reference = WeakReference(controller)
        assertEquals(1, counting.live)
        controller!!.release()
        controller = null

        assertEquals(0, counting.live)
        assertTrue(collected(reference), "a released controller is still reachable")
    }

    @Test
    fun `a wake lock cleared while its window was paused and destroyed releases its subscription at the next resume`() {
        val counting = CountingAccess(access)
        val screen: ActivityController<Activity> = newActivity().also { it.resume() }
        val controller: ScreenWakeLockController = createScreenWakeLockController(counting)
        // created after the resume, so the tracker replays the resumed activity and the hold lands
        controller.setKeepScreenOn(true)
        idle()
        screen.pause()
        controller.setKeepScreenOn(false)
        idle()
        // The system destroys the stopped window without finishing it and nothing resumes for now.
        // The platform gives no destroy signal to wake the controller, so it is released by the next
        // resume of any window (bounded and self-healing: it holds a weak reference to the dead window).
        screen.stop().destroy()
        idle()
        newActivity().resume()
        idle()
        assertEquals(0, counting.live, "the next resume of any window did not release the cleared controller")
    }

    @Test
    fun `a wake lock held by a newer screen is not cleared by an older screen finishing late`() {
        val screen: ActivityController<Activity> = newActivity().also { it.resume() }
        val old: ScreenWakeLockController = createScreenWakeLockController(access)
        old.setKeepScreenOn(true)
        idle()
        val newer: ScreenWakeLockController = createScreenWakeLockController(access)
        newer.setKeepScreenOn(true)
        idle()

        old.setKeepScreenOn(false)
        idle()

        assertTrue(screen.get().keepsOn(), "the older screen's release cleared the flag the newer screen still holds")
    }

    @Test
    fun `the newer screen releasing first keeps the flag while the older still holds it`() {
        val screen: ActivityController<Activity> = newActivity().also { it.resume() }
        val old: ScreenWakeLockController = createScreenWakeLockController(access)
        old.setKeepScreenOn(true)
        idle()
        val newer: ScreenWakeLockController = createScreenWakeLockController(access)
        newer.setKeepScreenOn(true)
        idle()

        newer.setKeepScreenOn(false)
        idle()
        assertTrue(screen.get().keepsOn(), "the newer screen's release cleared the flag the older screen still holds")

        old.setKeepScreenOn(false)
        idle()
        assertFalse(screen.get().keepsOn(), "the flag survived the release of both holders")
    }

    @Test
    fun `both overlapping controllers released in either order leave no flag no subscription and are collectable`() {
        listOf(true, false).forEach { oldFirst ->
            val counting = CountingAccess(access)
            val screen: ActivityController<Activity> = newActivity().also { it.resume() }
            var old: ScreenWakeLockController? = createScreenWakeLockController(counting)
            var newer: ScreenWakeLockController? = createScreenWakeLockController(counting)
            old!!.setKeepScreenOn(true)
            idle()
            newer!!.setKeepScreenOn(true)
            idle()

            if (oldFirst) {
                old.setKeepScreenOn(false)
                newer.setKeepScreenOn(false)
            } else {
                newer.setKeepScreenOn(false)
                old.setKeepScreenOn(false)
            }
            idle()

            assertFalse(screen.get().keepsOn(), "flag left on (oldFirst=$oldFirst)")
            assertEquals(0, counting.live, "a subscription is left (oldFirst=$oldFirst)")
            val oldRef = WeakReference<Any>(old)
            val newerRef = WeakReference<Any>(newer)
            old = null
            newer = null
            assertTrue(collected(oldRef), "the older controller is retained (oldFirst=$oldFirst)")
            assertTrue(collected(newerRef), "the newer controller is retained (oldFirst=$oldFirst)")
        }
    }

    @Test
    fun `a release owed to a paused window is still delivered when another controller holds a different window`() {
        val first: ActivityController<Activity> = newActivity().also { it.resume() }
        val old: ScreenWakeLockController = createScreenWakeLockController(access)
        old.setKeepScreenOn(true)
        idle()
        first.pause()
        val second: ActivityController<Activity> = newActivity().also { it.resume() }
        idle()
        val newer: ScreenWakeLockController = createScreenWakeLockController(access)
        newer.setKeepScreenOn(true)
        idle()

        // the older controller releases: the second window is still held by the newer one, the first
        // window is paused and owed its release
        old.setKeepScreenOn(false)
        idle()
        assertTrue(second.get().keepsOn(), "the second window lost the flag the newer controller holds")

        // the newer controller lets go while the second window is still the resumed one
        newer.setKeepScreenOn(false)
        idle()
        assertFalse(second.get().keepsOn(), "the second window kept the flag after every holder released it")

        // the first window comes back: nobody holds anything, the owed release clears it
        second.pause()
        first.resume()
        idle()
        assertFalse(first.get().keepsOn(), "the owed release was never delivered to the first window")
    }
}
