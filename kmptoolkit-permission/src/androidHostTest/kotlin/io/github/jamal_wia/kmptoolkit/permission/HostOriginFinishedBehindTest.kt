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
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * "It gives up when the activity is finished (not recreated) while the dialog is up": the activity the
 * dialog was shown from, even when another activity of the app is the one on top by then.
 */
@RunWith(AndroidJUnit4::class)
class HostOriginFinishedBehindTest {

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

    @Test
    fun `the activity the dialog came from finishing behind another live activity ends the request`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        var answers: Map<String, Boolean>? = null
        assertTrue(host.launch(listOf(camera)) { answers = it })

        origin.pause()
        resumedActivity() // another screen of the app is on top now
        origin.get().finish()
        origin.stop().destroy()
        activities.remove(origin)

        assertEquals(emptyMap(), answers, "the dialog's activity is gone, nothing can answer, and the request is still pending")
        assertTrue(host.launch(listOf(camera)) {}, "the host stayed stuck on the dead request")
    }

    @Test
    fun `an activity recreated by a rotation and then finished ends the request`() {
        shadowOf(application).denyPermissions(camera)
        val origin: ActivityController<ComponentActivity> = resumedActivity()
        val host = ActivityResultPermissionRequestHost(access)
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        assertTrue(host.launch(listOf(camera)) { answers += it })

        origin.recreate() // the recreated instance owns the answer from here on
        assertEquals(emptyList(), answers, "a rotation ended the request")
        origin.get().finish()
        origin.pause().stop().destroy()
        activities.remove(origin)

        assertEquals(listOf<Map<String, Boolean>>(emptyMap()), answers, "the recreated activity finished and the request was not ended exactly once")
        resumedActivity() // something to show the next dialog from
        assertTrue(host.launch(listOf(camera)) {}, "the host stayed stuck on the dead request")
    }
}
