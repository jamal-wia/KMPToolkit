package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * 05-platform-notes: a window that is finishing or destroyed will never resume, so a release owed to it is
 * dropped at once. A release therefore does not write to a destroyed window, and leaves nothing subscribed.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockReleaseOwedToDestroyedWindowTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        access.release()
        controllers.forEach { it.close() }
    }

    @Test
    fun `a release does not write to a window that was destroyed without finishing`() {
        val controller: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()
        val window: Activity = controller.get()
        val wake = AndroidScreenWakeLockController(access)
        wake.setKeepScreenOn(true)
        controller.pause().stop().destroy()

        wake.setKeepScreenOn(false)

        assertTrue(
            window.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0,
            "the dead window was written to by the release",
        )
    }
}
