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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The host gives a request up when the origin activity is "finishing (not recreated)" as another activity
 * resumes. An origin that is finishing and changing configuration at once is being recreated, not left: its
 * restored registry still owns the answer, so the request must stay pending. The state is produced by setting
 * the framework's own flag, as the sibling host tests do for the platform-request flag.
 */
@RunWith(AndroidJUnit4::class)
class HostOriginChangingConfigurationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun resumedActivity(): ActivityController<ComponentActivity> =
        Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

    private fun markChangingConfigurations(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mChangingConfigurations")
        field.isAccessible = true
        field.setBoolean(activity, true)
        assertTrue(activity.isChangingConfigurations, "the framework flag was not set")
    }

    @Test
    fun `an origin that is finishing while changing configuration does not end the request when another activity resumes`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })

        origin.get().finish()
        markChangingConfigurations(origin.get())
        origin.pause()
        resumedActivity()

        assertNull(answers, "the request was ended although the origin is being recreated")
    }

    @Test
    fun `control - the same origin finishing without a configuration change ends the request`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })

        origin.get().finish()
        origin.pause()
        resumedActivity()

        assertEquals(emptyMap(), answers)
    }
}
