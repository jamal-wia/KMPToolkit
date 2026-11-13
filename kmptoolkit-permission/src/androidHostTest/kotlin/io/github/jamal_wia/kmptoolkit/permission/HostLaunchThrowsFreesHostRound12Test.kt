package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
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
 * A launch the system refuses (the dialog's screen cannot be started) reports `false` and leaves the host free: the next
 * request, once the system works again, is shown. Found by deleting the cleanup of that path: the host then stays "showing"
 * forever and every later request is refused, and no test noticed.
 */
@RunWith(AndroidJUnit4::class)
class HostLaunchThrowsFreesHostRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        shadowOf(application).checkActivities(false)
        access.release()
        activities.forEach { it.close() }
    }

    @Test
    fun `a launch the system refuses leaves the host free for the next request`() {
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val host = ActivityResultPermissionRequestHost(access)

        shadowOf(application).checkActivities(true)
        assertFalse(host.launch(listOf(Manifest.permission.CAMERA)) {}, "the refused launch must report false")

        shadowOf(application).checkActivities(false)
        assertTrue(host.launch(listOf(Manifest.permission.CAMERA)) {}, "the host is still wedged by the refused launch")
    }
}
