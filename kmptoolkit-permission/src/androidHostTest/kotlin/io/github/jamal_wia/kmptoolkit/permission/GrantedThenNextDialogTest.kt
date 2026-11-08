package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * One permission allowed, then another dialog from the same coroutine, where the answer is delivered
 * (`Dispatchers.Main.immediate`, here `UnconfinedTestDispatcher`). Android delivers the answer to a paused
 * activity and resumes it right after; a dialog launched inside that delivery finds no resumed activity and
 * is "could not be shown" - the second request returns its old status and the user is never asked
 * ("every runtime permission that can still prompt shares one system dialog"; a request shows its dialog).
 * A refusal waits for the resume before going on (`settle`); an allow does not.
 */
@RunWith(AndroidJUnit4::class)
class GrantedThenNextDialogTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val logger = RecordingLogger()

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun ShadowActivity.answerAllow(activity: Activity) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        shadowOf(application).grantPermissions(*request.requestedPermissions)
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `an allowed permission followed by another request from the same coroutine still shows the second dialog`() =
        runTest(UnconfinedTestDispatcher()) {
            application.declareInManifest(camera, microphone)
            shadowOf(application).denyPermissions(camera, microphone)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val controller: PermissionController = createPermissionController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                activityAccess = access,
                host = ActivityResultPermissionRequestHost(access, logger),
                logger = logger,
            )
            val both = async {
                controller.request(CommonPermission.Camera)
                controller.request(CommonPermission.Microphone)
            }
            assertEquals(listOf(camera), shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(), "the first dialog is up")
            screen.pause() // the activity is paused behind the dialog
            shadowOf(screen.get()).answerAllow(screen.get()) // delivered to the paused activity
            screen.resume() // the resume that follows the answer

            assertEquals(
                listOf(microphone),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                "the second request never put its dialog up; log: ${logger.entries.filter { it.level == LogLevel.WARN }.map { it.message }}",
            )
            both.cancel()
        }

    @Test
    fun `a batch of location and background location still shows the background dialog after the foreground one is allowed`() =
        runTest(UnconfinedTestDispatcher()) {
            val fine: String = Manifest.permission.ACCESS_FINE_LOCATION
            val coarse: String = Manifest.permission.ACCESS_COARSE_LOCATION
            val background: String = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            application.declareInManifest(fine, coarse, background)
            shadowOf(application).denyPermissions(fine, coarse, background)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val controller: PermissionController = createPermissionController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                activityAccess = access,
                host = ActivityResultPermissionRequestHost(access, logger),
                logger = logger,
            )
            // "everything else (special accesses, roles, background location ...) is then requested one by one, in order"
            val call = async { controller.request(listOf(CommonPermission.Location, CommonPermission.LocationBackground)) }
            assertEquals(
                setOf(fine, coarse),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toSet(),
                "the foreground dialog is up",
            )
            screen.pause()
            shadowOf(screen.get()).answerAllow(screen.get())
            screen.resume()

            assertEquals(
                listOf(background),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                "the background request of the batch never put its dialog up; log: ${logger.entries.filter { it.level == LogLevel.WARN }.map { it.message }}",
            )
            call.cancel()
        }
}
