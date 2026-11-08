package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import androidx.core.view.WindowCompat
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
 * The Android controller on every API level from the library minimum up: every combination of icon styles and
 * visibility preset reaches a real window without throwing, the status bar icon style reads back as written
 * (it works on every supported level), and releasing the controller leaves the window as it was.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 30, 33, 34])
class SystemBarsApiMatrixTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach(ActivityAccess::release)
        activities.forEach { it.close() }
    }

    @Test
    fun `every icon style and visibility combination is applied to a real window on this level`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val controller: SystemBarsController = createSystemBarsController(access)
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val visibilities = listOf(SystemBarsVisibility.Visible, SystemBarsVisibility.Immersive, SystemBarsVisibility.Hidden)
        for (status in SystemBarIconStyle.entries) for (nav in SystemBarIconStyle.entries) for (visibility in visibilities) {
            controller.setBaseConfig(SystemBarsConfig(statusBarIcons = status, navigationBarIcons = nav, visibility = visibility))
            assertEquals(status == SystemBarIconStyle.DarkIcons, insets.isAppearanceLightStatusBars, "status icons $status $nav $visibility")
        }
        controller.release()
    }

    @Test
    fun `an override pushed and released through the window ends on the base on this level`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val controller: SystemBarsController = createSystemBarsController(access, SystemBarsConfig.ForLightBackground)
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        val handle = controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        assertEquals(false, insets.isAppearanceLightStatusBars)
        handle.release()

        assertEquals(true, insets.isAppearanceLightStatusBars, "the base light-background icons must be back")
        controller.release()
    }
}
