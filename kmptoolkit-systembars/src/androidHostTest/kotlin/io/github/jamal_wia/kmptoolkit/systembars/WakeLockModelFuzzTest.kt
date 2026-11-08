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
 * Seeded random lifecycle sequences (navigate, go back, recreate, background, foreground, destroy a
 * paused window) over three wake-lock controllers that hold and release in random order, checked
 * against a model: the resumed window carries the flag exactly while some controller holds, and once
 * every surviving window has been back on screen no subscription outlives the holds.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockModelFuzzTest {

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
    fun `random lifecycle sequences keep the flag exactly while some controller holds`() {
        for (seed in 1..400) {
            val random = Random(seed)
            val real: ActivityAccess = createActivityAccess(application)
            val access = CountingAccess(real)
            val built: MutableList<ActivityController<Activity>> = mutableListOf()
            val alive: MutableList<ActivityController<Activity>> = mutableListOf() // not destroyed
            var current: ActivityController<Activity>? = null
            var background = false
            val controllers: List<ScreenWakeLockController> = List(3) { createScreenWakeLockController(access) }
            val holds = BooleanArray(3)
            val log = StringBuilder()
            try {
                fun fresh(): ActivityController<Activity> =
                    Robolectric.buildActivity(Activity::class.java).also { built += it; alive += it }.create().start()
                fun goTo(next: ActivityController<Activity>) {
                    if (!background) current?.pause()
                    background = false
                    next.resume()
                    current = next
                }
                current = fresh().also { it.resume() }
                repeat(40) { step ->
                    when (val op: Int = random.nextInt(10)) {
                        0, 1 -> { val i = random.nextInt(3); holds[i] = true; controllers[i].setKeepScreenOn(true); log.append("hold$i ") }
                        2, 3 -> { val i = random.nextInt(3); holds[i] = false; controllers[i].setKeepScreenOn(false); log.append("free$i ") }
                        4 -> { goTo(fresh()); log.append("nav ") }
                        5 -> {
                            val other = alive.filter { it !== current }.randomOrNull(random)
                            if (other != null) { goTo(other); log.append("back ") }
                        }
                        6 -> { // recreate: the current window is destroyed, a new one comes up
                            val old = current
                            if (!background) old?.pause()
                            background = false
                            val next = fresh()
                            old?.stop()?.destroy()
                            alive.remove(old)
                            next.resume(); current = next; log.append("recreate ")
                        }
                        8 -> { if (!background) { current?.pause(); background = true; log.append("bg ") } }
                        9 -> { if (background) { current!!.resume(); background = false; log.append("fg ") } }
                        else -> { // destroy a paused window that is not on screen
                            val victim = alive.filter { it !== current }.randomOrNull(random)
                            if (victim != null) { victim.pause().stop().destroy(); alive.remove(victim); log.append("kill ") }
                        }
                    }
                    idle()
                    val any: Boolean = holds.any { it }
                    if (!background) assertEquals(any, current!!.get().keepsOn(), "seed $seed step $step ($log): resumed window flag != any hold")
                }
                if (background) { current!!.resume(); background = false }
                // every surviving window comes back once
                for (w in alive.toList()) { goTo(w); idle() }
                val any: Boolean = holds.any { it }
                for (w in alive) {
                    goTo(w); idle()
                    assertEquals(any, w.get().keepsOn(), "seed $seed ($log): window flag != any hold after the tour")
                }
                assertEquals(holds.count { it }, access.live, "seed $seed ($log): subscriptions != holding controllers")
                holds.indices.forEach { controllers[it].setKeepScreenOn(false) }
                idle()
                alive.forEach { goTo(it); idle(); assertFalse(it.get().keepsOn(), "seed $seed ($log): flag after all released") }
                assertEquals(0, access.live, "seed $seed ($log): subscription left after all released and every window back")
            } finally {
                real.release()
                built.forEach { runCatching { it.close() } }
            }
        }
        assertTrue(true)
    }
}
