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
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * The real host against random lifecycle walks of one activity while its dialog is up: pause, resume,
 * rotation, and the answer arriving at any point of them (between a recreation and its resume included).
 * Whatever the walk, the answer resolves the request exactly once with what the user chose, and the host
 * takes the next request. Seeded and capped: 120 walks of at most eight events.
 */
@RunWith(AndroidJUnit4::class)
class HostLifecycleFuzzTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    @Test
    fun `a random lifecycle walk with the dialog up still resolves the request once with the real answer`() {
        shadowOf(application).denyPermissions(camera)
        val random = Random(1108)
        repeat(120) { walk ->
            val controller: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val host = ActivityResultPermissionRequestHost(access)
            val answers: MutableList<Map<String, Boolean>> = mutableListOf()
            assertTrue(host.launch(listOf(camera)) { answers += it }, "walk $walk: the dialog was not launched")
            val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(controller.get()).lastRequestedPermission)
            var resumed = true
            var delivered = false
            val trail: MutableList<String> = mutableListOf()

            fun deliver() {
                if (delivered) return
                delivered = true
                trail += "answer"
                endPlatformRequest(controller.get())
                @Suppress("DEPRECATION")
                controller.get().onRequestPermissionsResult(
                    request.requestCode,
                    request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_DENIED),
                )
                endPlatformRequest(controller.get())
            }

            repeat(random.nextInt(1, 9)) {
                when (random.nextInt(4)) {
                    0 -> if (resumed) { trail += "pause"; controller.pause(); resumed = false }
                    1 -> if (!resumed) { trail += "resume"; controller.resume(); resumed = true }
                    2 -> if (resumed) { trail += "rotate"; controller.recreate() }
                    else -> if (random.nextInt(3) == 0) deliver()
                }
            }
            if (!resumed) controller.resume()
            deliver()

            assertEquals(listOf(mapOf(camera to false)), answers, "walk $walk $trail")
            assertTrue(host.launch(listOf(camera)) {}, "walk $walk $trail: the host did not take the next request")
            endPlatformRequest(controller.get())
            // Finish this walk's activity before the next one starts: finishing ends the walk's unanswered
            // second request and frees the activity, so no stale request attaches to a later walk's
            // activity and 120 resumed activities are not held until the end of the test run.
            controller.get().finish()
            controller.pause().stop().destroy()
            controller.close()
            activities.remove(controller)
        }
    }
}
