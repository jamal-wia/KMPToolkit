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
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A hold that starts while two windows of the app are already resumed (split screen, freeform): the flag
 * "never outlives the screen that requested it" works in the other direction too - the screen must stay awake
 * for as long as the caller says `true` and a window of the app is on screen.
 */
@RunWith(AndroidJUnit4::class)
class WakeLockHoldBothResumedTest {

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
    fun `a hold started with two windows resumed still keeps the screen awake when the newer window is closed`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val left: ActivityController<Activity> = resumedWindow()
        val right: ActivityController<Activity> = resumedWindow()

        controller.setKeepScreenOn(true)
        looper.idle()
        right.get().finish()
        right.pause().stop().destroy()
        looper.idle()

        assertTrue(left.get().hasKeepScreenOnFlag(), "the hold is on and the left window is on screen, but nothing keeps the screen awake")
    }

    @Test
    fun `a hold started with two windows resumed puts the flag on both`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val left: ActivityController<Activity> = resumedWindow()
        val right: ActivityController<Activity> = resumedWindow()

        controller.setKeepScreenOn(true)
        looper.idle()

        assertTrue(right.get().hasKeepScreenOnFlag())
        assertTrue(left.get().hasKeepScreenOnFlag(), "the earlier of two resumed windows is not held")
    }
}
