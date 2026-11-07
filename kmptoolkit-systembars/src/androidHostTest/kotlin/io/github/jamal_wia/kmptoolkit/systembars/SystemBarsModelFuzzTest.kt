package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Seeded random lifecycle sequences (navigate, back, recreate, background, foreground, destroy a
 * paused window, tear the controller down and build another over the same access) interleaved with
 * base and override changes: the resumed window always shows the live controller's configuration.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsModelFuzzTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())

    private fun idle() {
        var ran = 0
        while (ran < 10_000 && !looper.isIdle) { looper.runOneTask(); ran++ }
    }

    private fun Activity.status(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars
    private fun Activity.nav(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars

    @Test
    fun `random lifecycles and config changes keep the resumed window on the live controller's configuration`() {
        for (seed in 1..300) {
            val random = Random(seed)
            val access: ActivityAccess = createActivityAccess(application)
            val built: MutableList<ActivityController<Activity>> = mutableListOf()
            val alive: MutableList<ActivityController<Activity>> = mutableListOf()
            var current: ActivityController<Activity>? = null
            var background = false
            var controller: SystemBarsController = createSystemBarsController(access)
            val handles: MutableList<SystemBarsOverrideHandle> = mutableListOf()
            val log = StringBuilder()
            fun style(): SystemBarIconStyle = if (random.nextBoolean()) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons
            fun maybe(): SystemBarIconStyle? = if (random.nextInt(3) == 0) null else style()
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
                idle()
                repeat(50) { step ->
                    when (random.nextInt(14)) {
                        0, 1 -> { controller.setBaseConfig(SystemBarsConfig(style(), style())); log.append("base ") }
                        2 -> { controller.updateBaseConfig { it.copy(statusBarIcons = style()) }; log.append("upd ") }
                        3, 4 -> { handles += controller.applyOverride(SystemBarsOverride(maybe(), maybe())); log.append("push ") }
                        5 -> if (handles.isNotEmpty()) { handles[random.nextInt(handles.size)].update(SystemBarsOverride(maybe(), maybe())); log.append("hupd ") }
                        6 -> if (handles.isNotEmpty()) { handles.removeAt(random.nextInt(handles.size)).release(); log.append("hrel ") }
                        7 -> { goTo(fresh()); log.append("nav ") }
                        8 -> {
                            val other = alive.filter { it !== current }.randomOrNull(random)
                            if (other != null) { goTo(other); log.append("back ") }
                        }
                        9 -> {
                            val old = current
                            if (!background) old?.pause()
                            background = false
                            val next = fresh()
                            old?.stop()?.destroy(); alive.remove(old)
                            next.resume(); current = next; log.append("recreate ")
                        }
                        10 -> { if (!background) { current?.pause(); background = true; log.append("bg ") } }
                        11 -> { if (background) { current!!.resume(); background = false; log.append("fg ") } }
                        12 -> {
                            controller.release(); handles.clear()
                            controller = createSystemBarsController(access); log.append("reinit ")
                        }
                        else -> {
                            val victim = alive.filter { it !== current }.randomOrNull(random)
                            if (victim != null) { victim.pause().stop().destroy(); alive.remove(victim); log.append("kill ") }
                        }
                    }
                    idle()
                    if (!background) {
                        val cfg: SystemBarsConfig = controller.config.value
                        val w: Activity = current!!.get()
                        assertEquals(cfg.statusBarIcons == SystemBarIconStyle.DarkIcons, w.status(), "seed $seed step $step ($log): status icons")
                        assertEquals(cfg.navigationBarIcons == SystemBarIconStyle.DarkIcons, w.nav(), "seed $seed step $step ($log): nav icons")
                    }
                }
                controller.release()
            } finally {
                access.release()
                built.forEach { runCatching { it.close() } }
            }
        }
    }
}
