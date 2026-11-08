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
 * Control for the allowed-permission case: one permission refused, then another dialog from the same coroutine
 * where the answer is delivered (`Dispatchers.Main.immediate`, here `UnconfinedTestDispatcher`). A refusal
 * waits for the activity's resume before the request goes on (`settle`), so the second dialog finds a resumed
 * activity and is shown.
 */
@RunWith(AndroidJUnit4::class)
class DeniedThenNextDialogTest {

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

    private fun ShadowActivity.answerDeny(activity: Activity) {
        val request: ShadowActivity.PermissionsRequest = assertNotNull(lastRequestedPermission)
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").also { it.isAccessible = true }
        flag.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        flag.setBoolean(activity, false)
    }

    @Test
    fun `a refused permission followed by another request from the same coroutine shows the second dialog once the activity is back`() =
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
            shadowOf(screen.get()).answerDeny(screen.get()) // delivered to the paused activity
            screen.resume() // the resume that follows the answer

            assertEquals(
                listOf(microphone),
                shadowOf(screen.get()).lastRequestedPermission?.requestedPermissions?.toList(),
                "the second request never put its dialog up after a refusal; log: ${logger.entries.filter { it.level == LogLevel.WARN }.map { it.message }}",
            )
            both.cancel()
        }
}
