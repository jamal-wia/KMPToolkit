package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The wake lock with two windows of the app on screen at once (split screen, freeform): `ScreenWakeLockController`
 * says "the platform flag never outlives the screen that requested it", and that the owner clears it with
 * `setKeepScreenOn(false)`. A window that is still resumed and visible must not keep it after that.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockMultiWindowTest {

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

    private fun resumedWindow(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    @Test
    fun `a window still resumed beside a closed one loses the flag when the hold is released`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val left: ActivityController<Activity> = resumedWindow()
        controller.setKeepScreenOn(true)
        val right: ActivityController<Activity> = resumedWindow()
        looper.idle()
        assertTrue(left.get().hasKeepScreenOnFlag())
        assertTrue(right.get().hasKeepScreenOnFlag())

        right.pause() // the right-hand window goes away; the left one is still on screen and resumed
        controller.setKeepScreenOn(false)
        looper.idle()

        assertFalse(right.get().hasKeepScreenOnFlag())
        assertFalse(left.get().hasKeepScreenOnFlag(), "the window that is still on screen keeps the screen awake after the release")
    }

    @Test
    fun `both windows lose the flag when the hold is released while both are resumed`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val left: ActivityController<Activity> = resumedWindow()
        controller.setKeepScreenOn(true)
        val right: ActivityController<Activity> = resumedWindow()
        looper.idle()

        controller.setKeepScreenOn(false)
        looper.idle()

        assertFalse(right.get().hasKeepScreenOnFlag())
        assertFalse(left.get().hasKeepScreenOnFlag(), "the earlier of two resumed windows keeps the flag after the release")
    }

    @Test
    fun `a paused window the controller flagged loses the flag at the release, not at its next resume`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val first: ActivityController<Activity> = resumedWindow()
        controller.setKeepScreenOn(true)
        looper.idle()
        first.pause()
        val second: ActivityController<Activity> = resumedWindow()
        looper.idle()

        controller.setKeepScreenOn(false)
        looper.idle()

        assertFalse(first.get().hasKeepScreenOnFlag(), "the paused window kept the flag until it resumes")
        assertFalse(second.get().hasKeepScreenOnFlag())
    }

    @Test
    fun `control - a window another live controller still holds keeps the flag after this controller's release`() {
        val releasing = AndroidScreenWakeLockController(activityAccess)
        val staying = AndroidScreenWakeLockController(activityAccess)
        val window: ActivityController<Activity> = resumedWindow()
        releasing.setKeepScreenOn(true)
        staying.setKeepScreenOn(true)
        looper.idle()
        window.pause()

        releasing.setKeepScreenOn(false)
        looper.idle()
        assertTrue(window.get().hasKeepScreenOnFlag(), "the flag another controller holds was cleared")

        staying.setKeepScreenOn(false)
        looper.idle()
        window.resume()
        looper.idle()
        assertFalse(window.get().hasKeepScreenOnFlag(), "nobody holds the window and it kept the flag")
    }
}
