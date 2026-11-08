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

/** `release()` "does not repaint": a window write a background call had already queued must not repaint with the reset defaults. */
@RunWith(AndroidJUnit4::class)
class ReleaseDoesNotRepaintQueuedWriteTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    @Test
    fun `a write queued before release does not repaint the window with the reset defaults`() {
        val controller: SystemBarsController = createSystemBarsController(access, SystemBarsConfig.ForDarkBackground)
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertFalse(insets.isAppearanceLightStatusBars, "control: the initial dark-background config reached the window")
        thread { controller.updateBaseConfig { it.copy(visibility = SystemBarsVisibility.Hidden) } }.join()
        controller.release()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SystemBarsConfig(), controller.config.value)
        assertFalse(insets.isAppearanceLightStatusBars, "release() promised not to repaint, yet the window now shows the defaults")
    }
}
