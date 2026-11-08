package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * A dialog that was up when the process died is answered after the activity came back from its saved state.
 * The registry parks that answer under the dead host's key; the rebuilt process numbers its hosts from the
 * start again, so the first request of the new host uses the same key.
 */
@RunWith(AndroidJUnit4::class)
class HostStaleParkedAnswerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    /** A new process starts numbering its hosts from the beginning. */
    private fun restartHostNumbering() {
        PermissionHostKeys.restartProcessForTest()
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    /** Rebuilds "the process" with a dead dialog's answer parked in the restored activity; returns the new access. */
    private fun rebuiltProcessWithParkedAnswer(): ActivityAccess {
        shadowOf(application).denyPermissions(camera, microphone)
        application.declareInManifest(camera, microphone)
        restartHostNumbering()
        val oldAccess: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val first: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val oldHost = ActivityResultPermissionRequestHost(oldAccess)
        check(oldHost.launch(listOf(camera)) { }) { "the first dialog was not launched" }
        val oldRequest = assertNotNull(shadowOf(first.get()).lastRequestedPermission)

        // The process dies with the dialog up; the system restores the activity from its saved state.
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        oldAccess.release()
        val newAccess: ActivityAccess = createActivityAccess(application).also { accesses += it }
        restartHostNumbering()
        val second: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.create(state).start().postCreate(state)

        // The user answers the dead process's dialog (refuses): delivered to the restored activity, parked.
        endPlatformRequest(second.get())
        @Suppress("DEPRECATION")
        second.get().onRequestPermissionsResult(
            oldRequest.requestCode,
            oldRequest.requestedPermissions,
            IntArray(oldRequest.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        second.resume()
        return newAccess
    }

    @Test
    fun `a dead process's answer parked in the restored registry does not resolve the first request of the rebuilt host`() {
        val newAccess: ActivityAccess = rebuiltProcessWithParkedAnswer()

        // Later the rebuilt app asks for something else through its own, newly numbered host.
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val newHost = ActivityResultPermissionRequestHost(newAccess)
        newHost.launch(listOf(microphone)) { answers += it }

        assertEquals(emptyList(), answers, "the new request was resolved by the dead process's parked answer, before any dialog of its own was answered")
    }

    @Test
    fun `a controller request after a process rebuild is not resolved by the dead process's parked answer`(): Unit = runBlocking {
        val newAccess: ActivityAccess = rebuiltProcessWithParkedAnswer()
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), newAccess, ActivityResultPermissionRequestHost(newAccess))

        val request: Deferred<PermissionStatus> =
            async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Microphone) }

        assertFalse(request.isCompleted, "the microphone request completed with no dialog answered: ${if (request.isCompleted) request.getCompleted() else null}")
        request.cancel()
    }
}
