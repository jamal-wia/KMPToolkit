package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.app.Application
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the controller is created relative to the first activity resume — the contract stated on
 * `createSystemBarsController` and `createActivityAccess`: create them in `Application.onCreate`.
 *
 * The reason is a platform fact, not a design choice. The activity tracker learns which activity is
 * current only from `onActivityResumed`, and Android offers no public way to ask for an activity that
 * resumed before the tracker registered. A consumer binding the controller as a lazy DI singleton
 * typically first resolves it during composition — and the first composition runs *after*
 * `onResume`, because the decor view is attached to the window in `handleResumeActivity`, after
 * the resume callbacks. Such a controller cannot style the window the user is looking at until the
 * next resume.
 */
@RunWith(AndroidJUnit4::class)
class ControllerCreationOrderTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach(ActivityAccess::release)
        activities.forEach { it.close() }
    }

    private fun access(): ActivityAccess = createActivityAccess(application).also { accesses += it }

    private fun resumedActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume().get()

    private fun Activity.hasDarkStatusBarIcons(): Boolean =
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars

    @Test
    fun `control - a controller created before the activity resumes styles its window`() {
        val controller = createSystemBarsController(access(), SystemBarsConfig.ForDarkBackground)
        val activity = resumedActivity()

        controller.setBaseConfig(SystemBarsConfig.ForLightBackground)

        assertTrue(activity.hasDarkStatusBarIcons())
    }

    @Test
    fun `a controller created after the activity resumed cannot reach that window`() {
        // The failure the contract exists to prevent, pinned so that the KDoc cannot drift from it.
        // If this ever starts passing the other way, the tracker found a way to learn about an
        // already-resumed activity, and the "create in Application.onCreate" requirement can go.
        val controller: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume()
        val late = createSystemBarsController(access(), SystemBarsConfig.ForDarkBackground)

        late.setBaseConfig(SystemBarsConfig.ForLightBackground)

        assertFalse(
            controller.get().hasDarkStatusBarIcons(),
            "the late controller styled a window it was never told about — the contract may be obsolete",
        )
    }

    @Test
    fun `a late controller recovers on the next resume, with the configuration it holds by then`() {
        // What a consumer who broke the contract actually sees: wrong bars until the user leaves and
        // comes back, or rotates. Recovery is real, so the damage is bounded — but it is visible.
        val controller: ActivityController<Activity> =
            Robolectric.buildActivity(Activity::class.java).also { activities += it }.create().resume()
        val late = createSystemBarsController(access(), SystemBarsConfig.ForDarkBackground)
        late.setBaseConfig(SystemBarsConfig.ForLightBackground)

        controller.pause().resume()

        assertTrue(controller.get().hasDarkStatusBarIcons())
    }
}
