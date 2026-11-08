package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Real Context-based controllers (the shared tracker) under a thread storm and activity hand-overs:
 * one controller per thread for the wake lock, one shared system-bars controller written from eight
 * threads. Bounded counts, capped looper drains, bounded joins.
 */
@RunWith(AndroidJUnit4::class)
class AndroidControllersStormTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val built: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        built.forEach { it.close() }
    }

    private fun Activity.keepsOn(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun Activity.darkStatusIcons(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars

    private fun newActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { built += it }.create().start()

    private fun drain(cap: Int = 300_000) {
        var ran = 0
        while (ran < cap && !looper.isIdle) {
            looper.runOneTask()
            ran++
        }
    }

    @Test
    fun `a wake lock controller per thread from the shared tracker leaves no window flagged once all are cleared`() {
        repeat(5) { round ->
            built.clear()
            var current: ActivityController<Activity> = newActivity().also { it.resume() }
            val controllers: List<ScreenWakeLockController> = List(8) { createScreenWakeLockController(application) }
            val stop = AtomicBoolean(false)
            val threads: List<Thread> = controllers.mapIndexed { index, controller ->
                thread(isDaemon = true, name = "wake-storm-$index") {
                    val random = Random(round * 17 + index)
                    var calls = 0
                    while (!stop.get() && calls < 3_000) {
                        controller.setKeepScreenOn(random.nextBoolean())
                        calls++
                    }
                }
            }
            repeat(6) {
                val next: ActivityController<Activity> = newActivity()
                current.pause()
                next.resume()
                drain()
                current.stop()
                current = next
            }
            stop.set(true)
            threads.forEach { it.join(5_000) }
            assertTrue(threads.none { it.isAlive }, "round $round: a thread is stuck")
            // Every owner clears its own hold, as a screen would.
            controllers.forEach { controller ->
                thread(isDaemon = true) { controller.setKeepScreenOn(false) }.join(5_000)
            }
            drain()
            // The shared flag: with eight controllers over one window the contract allows only one owner,
            // so only the last cleared state matters: nobody holds, nobody may carry the flag.
            built.toList().filter { it !== current }.forEach { earlier ->
                current.pause()
                earlier.restart().start().resume()
                drain()
                earlier.pause().stop()
            }
            val leaked: List<Activity> = built.map { it.get() }.filter { it.keepsOn() }
            assertEquals(0, leaked.size, "round $round: windows still carry the flag with every controller cleared")
        }
    }

    @Test
    fun `eight threads writing the base config while windows hand over leave every window on the final config`() {
        val controller: SystemBarsController = createSystemBarsController(application)
        var current: ActivityController<Activity> = newActivity().also { it.resume() }
        val stop = AtomicBoolean(false)
        val threads: List<Thread> = List(8) { index ->
            thread(isDaemon = true, name = "bars-storm-$index") {
                val random = Random(index)
                var calls = 0
                while (!stop.get() && calls < 3_000) {
                    controller.setBaseConfig(
                        if (random.nextBoolean()) SystemBarsConfig.ForLightBackground else SystemBarsConfig.ForDarkBackground,
                    )
                    calls++
                }
            }
        }
        repeat(6) {
            val next: ActivityController<Activity> = newActivity()
            current.pause()
            next.resume()
            drain()
            current.stop()
            current = next
        }
        stop.set(true)
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive }, "a thread is stuck")
        controller.setBaseConfig(SystemBarsConfig.ForLightBackground)
        drain()

        assertTrue(current.get().darkStatusIcons(), "the current window does not show the final configuration")
        built.toList().filter { it !== current }.forEach { earlier ->
            current.pause()
            earlier.restart().start().resume()
            drain()
            assertTrue(earlier.get().darkStatusIcons(), "a window brought back does not show the final configuration")
            earlier.pause().stop()
        }
        controller.release()
    }
}
