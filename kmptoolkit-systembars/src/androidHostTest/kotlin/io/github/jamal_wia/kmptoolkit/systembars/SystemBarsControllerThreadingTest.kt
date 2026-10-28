package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Which configuration the window ends up with when mutations arrive from two threads, one after the
 * other — the contract on `SystemBarsController`: every method is safe from any thread, and the
 * platform follows the configuration the controller holds.
 *
 * The interleaving is made deterministic by Robolectric's paused main looper: a mutation made off the
 * main thread can only post its window write, and that write stays queued until the test idles the
 * looper — after the main thread has already made a later mutation and applied it inline.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarsControllerThreadingTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()

    private fun Activity.hasDarkStatusBarIcons(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars

    @Test
    fun `a window write queued by a background mutation does not land over a later main-thread one`() {
        val controller: SystemBarsController =
            createSystemBarsController(access, SystemBarsConfig.ForDarkBackground)
        val activity: Activity = resumedActivity()

        // Dark icons, requested from a background thread: the write to the window is posted.
        thread { controller.setBaseConfig(SystemBarsConfig.ForLightBackground) }.join()
        // Light icons, requested afterwards on the main thread: applied inline, at once.
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        // The queued write from the first call now runs.
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
        assertFalse(
            activity.hasDarkStatusBarIcons(),
            "the window must show the configuration the controller holds, not one it held earlier",
        )
    }
}
