package io.github.jamal_wia.kmptoolkit.platform.wakelock

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.platform.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.platform.activity.createActivityTracker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The window flag is asserted on the real `Window`, and the case that matters most is the one a
 * manual test never reproduces: a configuration change replaces the window, and the request has to
 * land on the new one by itself.
 */
@RunWith(AndroidJUnit4::class)
class AndroidScreenWakeLockTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val tracker: ActivityAccess = createActivityTracker(application)
    private val wakeLock: ScreenWakeLock = createScreenWakeLock(tracker)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        tracker.release()
        controllers.forEach { controller -> controller.close() }
    }

    private fun launchActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }

    private val Activity.keepsScreenOn: Boolean
        get() = window.attributes.flags and
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun `enabling sets the window flag on the resumed activity`() {
        val controller: ActivityController<Activity> = launchActivity().setup()

        assertEquals(WakeLockResult.APPLIED, wakeLock.setKeepScreenOn(true))
        assertTrue(controller.get().keepsScreenOn)
    }

    @Test
    fun `disabling clears the window flag`() {
        val controller: ActivityController<Activity> = launchActivity().setup()
        wakeLock.setKeepScreenOn(true)

        assertEquals(WakeLockResult.APPLIED, wakeLock.setKeepScreenOn(false))
        assertFalse(controller.get().keepsScreenOn)
    }

    @Test
    fun `enabling twice is idempotent`() {
        val controller: ActivityController<Activity> = launchActivity().setup()

        wakeLock.setKeepScreenOn(true)
        assertEquals(WakeLockResult.APPLIED, wakeLock.setKeepScreenOn(true))

        assertTrue(controller.get().keepsScreenOn)
    }

    @Test
    fun `disabling without ever enabling leaves the flag clear`() {
        val controller: ActivityController<Activity> = launchActivity().setup()

        assertEquals(WakeLockResult.APPLIED, wakeLock.setKeepScreenOn(false))

        assertFalse(controller.get().keepsScreenOn)
    }

    @Test
    fun `reports no active window when nothing is resumed`() {
        assertEquals(WakeLockResult.NO_ACTIVE_WINDOW, wakeLock.setKeepScreenOn(true))
    }

    @Test
    fun `a request made with no window is applied to the next activity that resumes`() {
        assertEquals(WakeLockResult.NO_ACTIVE_WINDOW, wakeLock.setKeepScreenOn(true))

        val controller: ActivityController<Activity> = launchActivity().setup()

        assertTrue(
            controller.get().keepsScreenOn,
            "a request made while backgrounded must survive to the next window",
        )
    }

    @Test
    fun `the flag survives an activity recreation`() {
        val first: ActivityController<Activity> = launchActivity().setup()
        wakeLock.setKeepScreenOn(true)
        assertTrue(first.get().keepsScreenOn)

        // What rotation does: the old activity goes away and a brand-new window appears, starting
        // from platform defaults.
        first.pause()
        val second: ActivityController<Activity> = launchActivity().setup()

        assertTrue(
            second.get().keepsScreenOn,
            "a rotation must not silently drop the keep-awake guarantee",
        )
    }

    @Test
    fun `a released request is not reapplied to a new activity`() {
        val first: ActivityController<Activity> = launchActivity().setup()
        wakeLock.setKeepScreenOn(true)
        wakeLock.setKeepScreenOn(false)
        first.pause()

        val second: ActivityController<Activity> = launchActivity().setup()

        assertFalse(second.get().keepsScreenOn)
    }
}
