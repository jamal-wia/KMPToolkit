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

/** A hold started with two windows resumed is taken off both on release, and another controller's hold on them survives it. */
@RunWith(AndroidJUnit4::class)
class WakeLockHoldBothResumedReleaseTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityAccess(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()
    private val looper = shadowOf(Looper.getMainLooper())

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { it.close() }
    }

    private fun Activity.flagged(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    private fun resumedWindow(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.setup()

    @Test
    fun `a release after a hold started with two windows resumed takes the flag off both`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val left: Activity = resumedWindow().get()
        val right: Activity = resumedWindow().get()

        controller.setKeepScreenOn(true)
        looper.idle()
        assertTrue(left.flagged() && right.flagged())
        controller.setKeepScreenOn(false)
        looper.idle()

        assertFalse(left.flagged(), "the left window kept the flag after the release")
        assertFalse(right.flagged(), "the right window kept the flag after the release")
    }

    @Test
    fun `a second controller's hold on both windows survives the first one's release`() {
        val first = AndroidScreenWakeLockController(activityAccess)
        val second = AndroidScreenWakeLockController(activityAccess)
        val left: Activity = resumedWindow().get()
        val right: Activity = resumedWindow().get()

        second.setKeepScreenOn(true)
        first.setKeepScreenOn(true)
        looper.idle()
        first.setKeepScreenOn(false)
        looper.idle()
        assertTrue(left.flagged() && right.flagged(), "the first release took a flag the second controller still holds")

        second.setKeepScreenOn(false)
        looper.idle()
        assertFalse(left.flagged() || right.flagged(), "the last release left a flag behind")
    }
}
