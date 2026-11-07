package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
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
 * The wake-lock controller, a consumer of the activity tracker's resume listener, under eight
 * threads that toggle the flag while the main thread hands the window over between activities.
 *
 * Bounded in real time: fixed iteration counts, capped looper drains, bounded joins.
 */
@RunWith(AndroidJUnit4::class)
class AndroidScreenWakeLockControllerStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { it.close() }
    }

    private fun Activity.hasKeepScreenOnFlag(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun drain(cap: Int = 256) {
        var ran = 0
        while (ran < cap && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    @Test
    fun `a flag put on two windows while the hold was on is gone from both once the hold is released`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val first: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.create().resume()
        controller.setKeepScreenOn(true)
        assertTrue(first.get().hasKeepScreenOnFlag())

        // A second activity of the app comes on top (a settings screen, say) and gets the flag too.
        val second: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.create().start()
        first.pause()
        second.resume()
        drain()
        assertTrue(second.get().hasKeepScreenOnFlag(), "the hold did not follow the user to the second window")

        controller.setKeepScreenOn(false)
        drain()
        assertFalse(second.get().hasKeepScreenOnFlag())

        // The user goes back: the first window is on screen again, with no hold wanted by anyone.
        second.pause()
        first.resume()
        drain()
        assertFalse(first.get().hasKeepScreenOnFlag(), "the first window kept the flag after the hold was released")
    }

    @Test
    fun `eight toggling threads and activity hand-overs leave the flag on the window that is current exactly when the last call asked for it`() {
        repeat(ROUNDS) { round ->
            val controller = AndroidScreenWakeLockController(activityAccess)
            var current: ActivityController<Activity> =
                Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.create().resume()
            val stop = AtomicBoolean(false)
            val random = Random(round)
            val togglers: List<Thread> = List(8) { index ->
                thread(name = "storm-toggle-$index", isDaemon = true) {
                    val own = Random(round * 31 + index)
                    var calls = 0
                    while (!stop.get() && calls < 5_000) {
                        controller.setKeepScreenOn(own.nextBoolean())
                        calls++
                    }
                }
            }
            repeat(6) {
                val next: ActivityController<Activity> =
                    Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.create().start()
                current.pause()
                next.resume()
                drain()
                current.stop()
                current = next
            }
            stop.set(true)
            togglers.forEach { it.join(5_000) }
            assertTrue(togglers.none { it.isAlive }, "round $round: a toggling thread is stuck")

            val last: Boolean = random.nextBoolean()
            thread(name = "storm-last", isDaemon = true) { controller.setKeepScreenOn(last) }.join(5_000)
            // The queue holds up to 40 000 passes the togglers posted (FIFO); the last request's pass sits
            // behind them, so the drain here has to be able to reach it (still capped, never open-ended).
            drain(cap = FINAL_DRAIN_CAP)

            assertEquals(last, current.get().hasKeepScreenOnFlag(), "round $round: the current window does not carry the last request ($last)")
            if (!last) {
                val leaked: List<Activity> = controllers.map { it.get() }.filter { it.hasKeepScreenOnFlag() }
                assertTrue(leaked.isEmpty(), "round $round: windows still carry the flag after a release: ${leaked.size}")
            }
            controller.setKeepScreenOn(false)
            drain(cap = FINAL_DRAIN_CAP)
        }
    }

    private companion object {
        const val ROUNDS: Int = 20
        const val FINAL_DRAIN_CAP: Int = 200_000
    }
}
