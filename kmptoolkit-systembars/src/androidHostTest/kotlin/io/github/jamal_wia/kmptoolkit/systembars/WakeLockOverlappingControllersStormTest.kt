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
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Four overlapping wake-lock controllers toggled from eight real threads while the main thread hands the
 * window over between activities: once the threads are done and each controller has been given a final
 * value, the resumed window carries the flag exactly while some controller holds, every other window the
 * controllers touched agrees after it comes back, and releasing all of them leaves no flag and no subscription.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockOverlappingControllersStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())

    private class CountingAccess(private val real: ActivityAccess) : ActivityAccess by real {
        var live: Int = 0
        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            live++
            val subscription: ActivitySubscription = real.addOnActivityResumedListener(listener)
            var cancelled = false
            return object : ActivitySubscription {
                override fun cancel() {
                    if (!cancelled) { cancelled = true; live-- }
                    subscription.cancel()
                }
            }
        }
    }

    private fun Activity.keepsOn(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun idle() {
        var ran = 0
        while (ran < 10_000 && !looper.isIdle) { looper.runOneTask(); ran++ }
    }

    @Test
    fun `overlapping controllers toggled from threads during hand-overs end consistent`() {
        for (round in 1..6) {
            val access = CountingAccess(createActivityAccess(application))
            val controllers: List<ScreenWakeLockController> = List(4) { createScreenWakeLockController(access) }
            val windows: MutableList<ActivityController<Activity>> = mutableListOf()
            try {
                fun fresh(): ActivityController<Activity> =
                    Robolectric.buildActivity(Activity::class.java).also { windows += it }.create().start()
                var current: ActivityController<Activity> = fresh().also { it.resume() }
                val togglers = List(8) { index ->
                    thread(isDaemon = true, name = "overlap-$index") {
                        val random = Random(round * 100 + index)
                        repeat(150) { controllers[random.nextInt(4)].setKeepScreenOn(random.nextBoolean()) }
                    }
                }
                var handovers = 0
                while (togglers.any { it.isAlive } && handovers < 400) {
                    idle()
                    if (handovers % 5 == 0) {
                        val next = fresh()
                        current.pause()
                        next.resume()
                        current = next
                    }
                    handovers++
                    Thread.sleep(1)
                }
                togglers.forEach { it.join(10_000) }
                assertTrue(togglers.none { it.isAlive }, "round $round: a toggler is stuck")
                val finals = BooleanArray(4) { it % 2 == round % 2 }
                controllers.forEachIndexed { i, c -> thread(isDaemon = true) { c.setKeepScreenOn(finals[i]) }.join(5_000) }
                idle()
                val any = finals.any { it }
                assertEquals(any, current.get().keepsOn(), "round $round: resumed window flag != any hold")
                for (w in windows.filter { it !== current }.toList()) {
                    if (w.get().isDestroyed || w.get().isFinishing) continue
                    current.pause(); w.resume(); idle()
                    assertEquals(any, w.get().keepsOn(), "round $round: a handed-over window disagrees")
                    w.pause(); current.resume(); idle()
                }
                assertEquals(finals.count { it }, access.live, "round $round: subscriptions != holding controllers")
                controllers.forEach { c -> thread(isDaemon = true) { c.setKeepScreenOn(false) }.join(5_000) }
                idle()
                for (w in windows) { current.pause(); w.resume(); idle(); assertEquals(false, w.get().keepsOn(), "round $round: flag left after release"); w.pause(); current.resume() }
                idle()
                assertEquals(0, access.live, "round $round: subscription left after release")
            } finally {
                windows.forEach { runCatching { it.close() } }
            }
        }
    }
}
