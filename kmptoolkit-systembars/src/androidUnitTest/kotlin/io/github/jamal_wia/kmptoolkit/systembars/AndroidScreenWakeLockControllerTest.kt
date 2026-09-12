package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [AndroidScreenWakeLockController] against a real [LifecycleActivityTracker] and real
 * Robolectric activities — the properties worth proving are about *which window* ends up holding
 * the flag across activity recreation, and a fake tracker would just assert the mock was called.
 */
@RunWith(AndroidJUnit4::class)
class AndroidScreenWakeLockControllerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activityAccess: ActivityAccess = createActivityTracker(application)
    private val controllers: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        activityAccess.release()
        controllers.forEach { controller -> controller.close() }
    }

    private fun resumedActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).also { controllers.add(it) }.create().resume()

    private fun Activity.hasKeepScreenOnFlag(): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test
    fun `setKeepScreenOn true adds FLAG_KEEP_SCREEN_ON to the current activity window`() {
        val activity = resumedActivity().get()
        val controller = AndroidScreenWakeLockController(activityAccess)

        controller.setKeepScreenOn(true)

        assertTrue(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `setKeepScreenOn false clears a previously set flag`() {
        val activity = resumedActivity().get()
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)

        controller.setKeepScreenOn(false)

        assertFalse(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `setKeepScreenOn true then false round-trips cleanly (no leftover flag)`() {
        val activity = resumedActivity().get()
        val controller = AndroidScreenWakeLockController(activityAccess)

        controller.setKeepScreenOn(true)
        assertTrue(activity.hasKeepScreenOnFlag(), "sanity: flag should be set before clearing")
        controller.setKeepScreenOn(false)

        assertFalse(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `a request made with no activity attached is remembered, not lost`() {
        // No activity is resumed yet. The pair of calls must not throw AND must leave the
        // controller holding nothing, which is observable on the next window: it neither gains the
        // flag nor loses one that was already there.
        val controller = AndroidScreenWakeLockController(activityAccess)

        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)

        val activity = resumedActivity().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // somebody else's
        assertTrue(activity.hasKeepScreenOnFlag(), "a controller holding nothing changes nothing")
    }

    @Test
    fun `the flag is re-applied to a freshly attached activity after recreation`() {
        // Simulates rotation: the old activity pauses and a new instance resumes, but this
        // controller outlives any single activity — see its class KDoc.
        val first = resumedActivity()
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)
        assertTrue(first.get().hasKeepScreenOnFlag(), "sanity: flag applied to the first activity")

        first.pause()
        val recreated = resumedActivity()

        assertTrue(
            recreated.get().hasKeepScreenOnFlag(),
            "the recreated activity's window starts with the flag unset by default — the " +
                "controller must re-apply its recorded 'enabled=true' state via the activity " +
                "tracker's resume callback",
        )
    }

    @Test
    fun `constructing the controller does not clear a flag it never set`() {
        // The kiosk-tablets-sleep-early bug: a resume replay must not run the "clear" branch with
        // nothing held, wiping FLAG_KEEP_SCREEN_ON off a window somebody else had set it on.
        val activity = resumedActivity().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AndroidScreenWakeLockController(activityAccess)

        assertTrue(
            activity.hasKeepScreenOnFlag(),
            "a controller holding nothing must not touch the window's existing flag",
        )
    }

    @Test
    fun `attaching a new activity does not clear a flag the controller does not hold`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        val activity = resumedActivity().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        assertTrue(activity.hasKeepScreenOnFlag())
        // ...and an explicit release still clears it, since then it IS the controller's to drop.
        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)
        assertFalse(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `a delivered release stops the controller owing that window anything`() {
        // pushedTo must be dropped when the release actually lands, or the window's next resume
        // would clear a flag that by then belongs to whoever set it in the meantime.
        val activity = resumedActivity()
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)

        activity.get().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // somebody else's
        activity.pause().resume()

        assertTrue(activity.get().hasKeepScreenOnFlag())
    }

    @Test
    fun `the first release a screen sends does not touch a flag somebody else set`() {
        // A caller's wake-lock flow typically emits its current state (false) first. That must
        // stay a no-op, or merely constructing the caller would clear a flag it never set.
        val activity = resumedActivity().get()
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = AndroidScreenWakeLockController(activityAccess)

        controller.setKeepScreenOn(false)

        assertTrue(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `one release undoes any number of holds`() {
        // The interface promises idempotence, not reference counting: callers wire this straight
        // off a boolean flow, so a second true must not require a second false.
        val activity = resumedActivity().get()
        val controller = AndroidScreenWakeLockController(activityAccess)

        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)

        assertFalse(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `a hold taken with no activity attached is applied on the next attach`() {
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)

        val activity = resumedActivity().get()

        assertTrue(activity.hasKeepScreenOnFlag())
    }

    @Test
    fun `an undelivered release is not carried to a window that never had the flag`() {
        // The release is owed to ONE window. A different activity never carried this controller's
        // flag, so it must be left exactly as it is.
        val first = resumedActivity()
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)
        first.pause()
        controller.setKeepScreenOn(false)

        val second = resumedActivity().get()
        second.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // somebody else's

        assertTrue(second.hasKeepScreenOnFlag(), "another window's flag is none of this controller's business")
    }

    @Test
    fun `a release that arrives with no activity attached is applied on the next attach`() {
        val activity = resumedActivity()
        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)

        activity.pause()
        controller.setKeepScreenOn(false)
        activity.resume()

        assertFalse(
            activity.get().hasKeepScreenOnFlag(),
            "a release taken while detached must reach the window that still carries the flag",
        )
    }

    @Test
    fun `a controller constructed after an activity is already resumed picks it up immediately`() {
        val activity = resumedActivity().get()

        val controller = AndroidScreenWakeLockController(activityAccess)
        controller.setKeepScreenOn(true)

        assertTrue(activity.hasKeepScreenOnFlag())
    }
}
