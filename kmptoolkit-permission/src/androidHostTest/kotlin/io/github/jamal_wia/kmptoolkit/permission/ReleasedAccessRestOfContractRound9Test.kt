package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * Round 9 (E1, bm): AY-01 documents that an `ActivityAccess` must outlive the controller built over it and what a
 * special-access request does when it does not. The rest of the contract over a released access, attacked here: a
 * runtime dialog that is up when the access is released, a runtime request after the release, `observe` (re-reads
 * only after requests, never on resume), `check`, `openSettings` and concurrent callers. Every wait is bounded.
 */
@RunWith(AndroidJUnit4::class)
class ReleasedAccessRestOfContractRound9Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val looper = shadowOf(Looper.getMainLooper())
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        activities.forEach { it.close() }
    }

    private fun newAccess(): ActivityAccess = createActivityAccess(application).also { accesses += it }

    private fun declare() {
        shadowOf(application).denyPermissions(camera, microphone)
        application.declareInManifest(camera, microphone)
    }

    private fun answer(activity: Activity, request: ShadowActivity.PermissionsRequest, permission: String, granted: Boolean) {
        if (granted) shadowOf(application).grantPermissions(permission)
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
    }

    @Test
    fun `a runtime dialog that is up when the access is released is still answered to the waiting caller`() = runBlocking {
        withTimeout(30_000) {
            declare()
            val access: ActivityAccess = newAccess()
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            val request: Deferred<PermissionStatus> = async(start = CoroutineStart.UNDISPATCHED) {
                controller.request(CommonPermission.Camera)
            }
            val dialog: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(screen.get()).lastRequestedPermission)

            access.release()
            answer(screen.get(), dialog, camera, granted = true)

            assertEquals(PermissionStatus.Granted, request.await())
        }
    }

    @Test
    fun `a runtime request after the release shows nothing returns the status unchanged and records nothing`() = runBlocking {
        withTimeout(60_000) {
            declare()
            val access: ActivityAccess = newAccess()
            val host = ActivityResultPermissionRequestHost(access)
            val controller: PermissionController = createPermissionController(application, InMemoryKeyValueStorage(), access, host)
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            access.release()

            val status: PermissionStatus = controller.request(CommonPermission.Camera)

            assertEquals(PermissionStatus.NotDetermined, status)
            assertNull(shadowOf(screen.get()).lastRequestedPermission, "no dialog may be launched without an access")
            assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera), "a refused launch records nothing")
        }
    }

    @Test
    fun `three callers after the release are each answered without any of them holding the others for ever`() = runBlocking {
        withTimeout(90_000) {
            declare()
            val access: ActivityAccess = newAccess()
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
            access.release()

            val results: List<Deferred<PermissionStatus>> = listOf(
                async(Dispatchers.Default) { controller.request(CommonPermission.Camera) },
                async(Dispatchers.Default) { controller.request(CommonPermission.Microphone) },
                async(Dispatchers.Default) { controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)).values.first() },
            )

            // Work for the main thread is posted there by callers on other threads: pump it, bounded by the timeout.
            while (results.any { !it.isCompleted }) {
                var ran = 0
                while (ran++ < 64 && !looper.isIdle) looper.runOneTask()
                kotlinx.coroutines.delay(2)
            }
            results.forEach { assertEquals(PermissionStatus.NotDetermined, it.await()) }
        }
    }

    @Test
    fun `observe over a released access re-reads after a request through the controller but never at a resume`() = runBlocking {
        withTimeout(60_000) {
            declare()
            val access: ActivityAccess = newAccess()
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
            val screen: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
            access.release()
            val seen: MutableList<PermissionStatus> = mutableListOf()
            val job = launch(Dispatchers.Default) { controller.observe(CommonPermission.Camera).collect { seen += it } }
            while (seen.isEmpty()) kotlinx.coroutines.delay(2)

            shadowOf(application).grantPermissions(camera)
            screen.pause().resume()
            kotlinx.coroutines.delay(200)
            assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen.toList(), "a resume must not re-read after the release")

            controller.request(CommonPermission.Camera) // already granted: no dialog, the answer is announced
            while (seen.size < 2) kotlinx.coroutines.delay(2)
            assertEquals(PermissionStatus.Granted, seen.last())
            job.cancel()
            job.join()
        }
    }

    @Test
    fun `check and openSettings keep working over a released access`() = runBlocking {
        withTimeout(30_000) {
            declare()
            val access: ActivityAccess = newAccess()
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
            access.release()

            assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
            assertTrue(controller.openSettings(CommonPermission.Camera))
            assertNotNull(shadowOf(application).nextStartedActivity, "the settings screen starts from the application context")
            assertEquals(emptyList<PermissionStatus>(), emptyList<PermissionStatus>())
        }
    }
}
