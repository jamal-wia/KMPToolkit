package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
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

/**
 * Boss round 2 of E1, real components (the library host, the shared activity access, a Robolectric
 * `ComponentActivity`). 04-api-reference "Requests are serialized": on Android a request cancelled while
 * its system dialog is still up makes "the next request" wait (bounded) until that dialog is answered,
 * so two prompts never overlap. A special access is a request too (`request(Overlay)`), but it opens its
 * Settings screen without going through the host, so it does not wait for the stale dialog.
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogThenSpecialAccessBoss2Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<ComponentActivity>> = mutableListOf()
    private val screens: MutableList<SystemScreenRequest> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    @Test
    fun `a special access requested behind a cancelled caller's open dialog does not open Settings over it`() =
        runTest(UnconfinedTestDispatcher()) {
            application.declareInManifest(camera)
            shadowOf(application).denyPermissions(camera)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val controller: PermissionController = createPermissionController(
                context = application,
                storage = InMemoryKeyValueStorage(),
                activityAccess = access,
                host = ActivityResultPermissionRequestHost(access),
                systemScreenLauncher = SystemScreenLauncher { request -> screens += request; true },
            )
            val first = async { controller.request(CommonPermission.Camera) }
            assertNotNull(shadowOf(screen.get()).lastRequestedPermission, "the dialog is up")
            first.cancel() // the system dialog stays on screen

            val next = async { controller.request(AndroidPermission.Overlay) }
            try {
                assertEquals(0, screens.size, "Settings was opened while the cancelled caller's permission dialog is still up")
            } finally {
                next.cancel()
            }
        }
}
