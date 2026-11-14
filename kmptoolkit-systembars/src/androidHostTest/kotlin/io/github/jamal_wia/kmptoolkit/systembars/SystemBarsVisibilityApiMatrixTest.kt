package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Build
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * What [SystemBarsApiMatrixTest] does not read back: the bar visibility, the hidden-bar behaviour and the
 * navigation bar icon style on a real window, on each side of the API levels where the platform changes (23 and
 * 26 for the icons per `docs/kmptoolkit-systembars/05-platform-notes.md`, 30 for the window insets controller).
 *
 * Below API 30 `WindowInsetsControllerCompat` writes system UI flags on the decor view, so visibility and behaviour
 * read back from `View.getSystemUiVisibility`. From API 30 the Robolectric decor view is not attached to a window
 * and its pending insets controller keeps only the behaviour (`systemBarsBehavior`), not the hidden types, so the
 * visibility of a bar is asserted below API 30 only; the behaviour is asserted on every level. Six levels, the most
 * rule TH6 allows in one class.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 30, 33, 35])
class SystemBarsVisibilityApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<Activity>> = mutableListOf()
    private val level: Int = Build.VERSION.SDK_INT

    @AfterTest
    fun tearDown() {
        accesses.forEach(ActivityAccess::release)
        activities.forEach { it.close() }
    }

    private class Window(val controller: SystemBarsController, val activity: Activity, val insets: WindowInsetsControllerCompat) {
        fun flags(): Int = activity.window.decorView.systemUiVisibility
        fun statusHidden(): Boolean = flags() and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
        fun navigationHidden(): Boolean = flags() and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0
    }

    private fun open(initial: SystemBarsConfig = SystemBarsConfig()): Window {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val controller: SystemBarsController = createSystemBarsController(access, initial)
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()
        return Window(controller, activity, WindowCompat.getInsetsController(activity.window, activity.window.decorView))
    }

    @Test
    fun `each bar is hidden or shown on its own below API 30`() {
        if (level >= Build.VERSION_CODES.R) return
        val window: Window = open()
        for (status in listOf(true, false)) for (navigation in listOf(true, false)) {
            window.controller.setBaseConfig(
                SystemBarsConfig(visibility = SystemBarsVisibility(isStatusBarVisible = status, isNavigationBarVisible = navigation)),
            )
            assertEquals(!status, window.statusHidden(), "status bar visible=$status navigation visible=$navigation API $level")
            assertEquals(!navigation, window.navigationHidden(), "navigation bar visible=$navigation status visible=$status API $level")
        }
        window.controller.release()
    }

    @Test
    fun `the presets reach the window below API 30`() {
        if (level >= Build.VERSION_CODES.R) return
        val window: Window = open()
        window.controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Immersive))
        assertEquals(true, window.statusHidden() && window.navigationHidden(), "Immersive hides both bars, API $level")
        window.controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Visible))
        assertEquals(false, window.statusHidden() || window.navigationHidden(), "Visible shows both bars, API $level")
        window.controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Hidden))
        assertEquals(true, window.statusHidden() && window.navigationHidden(), "Hidden hides both bars, API $level")
        window.controller.release()
    }

    @Test
    fun `the hidden bar behaviour reads back as written on every level`() {
        val window: Window = open()
        window.controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Immersive))
        val swipe: Int = window.insets.systemBarsBehaviorOrFlags(window)
        window.controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Hidden))
        val stay: Int = window.insets.systemBarsBehaviorOrFlags(window)
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, swipe, "Immersive (swipe to reveal), API $level")
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, stay, "Hidden (stay hidden), API $level")
        window.controller.release()
    }

    /** The behaviour as `WindowInsetsControllerCompat` reports it; below API 30 it is rebuilt from the immersive flags the compat writes. */
    private fun WindowInsetsControllerCompat.systemBarsBehaviorOrFlags(window: Window): Int =
        if (level >= Build.VERSION_CODES.R) {
            systemBarsBehavior
        } else if (window.flags() and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY != 0) {
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }

    @Test
    fun `the navigation bar icon style takes effect from API 26 and below it is silently ignored`() {
        val window: Window = open()
        for (style in listOf(SystemBarIconStyle.DarkIcons, SystemBarIconStyle.LightIcons, SystemBarIconStyle.DarkIcons)) {
            window.controller.setBaseConfig(SystemBarsConfig(navigationBarIcons = style))
            val expected: Boolean = level >= Build.VERSION_CODES.O && style == SystemBarIconStyle.DarkIcons
            assertEquals(expected, window.insets.isAppearanceLightNavigationBars, "navigation icons $style API $level")
        }
        window.controller.release()
    }

    @Test
    fun `the status bar icon style takes effect on every level and does not touch the navigation icons`() {
        val window: Window = open()
        window.controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.DarkIcons, navigationBarIcons = SystemBarIconStyle.LightIcons))
        assertEquals(true, window.insets.isAppearanceLightStatusBars, "status dark API $level")
        assertEquals(false, window.insets.isAppearanceLightNavigationBars, "navigation light next to a dark status API $level")
        window.controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons, navigationBarIcons = SystemBarIconStyle.DarkIcons))
        assertEquals(false, window.insets.isAppearanceLightStatusBars, "status light API $level")
        assertEquals(level >= Build.VERSION_CODES.O, window.insets.isAppearanceLightNavigationBars, "navigation dark next to a light status API $level")
        window.controller.release()
    }
}
