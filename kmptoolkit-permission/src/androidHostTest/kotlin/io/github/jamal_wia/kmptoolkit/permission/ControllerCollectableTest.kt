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
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * A controller its owner dropped, after its requests are over, must be collectable while the activity and
 * the shared access live on: nothing of a finished request may stay registered where it can reach it.
 */
@RunWith(AndroidJUnit4::class)
class ControllerCollectableTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private fun clearedAfterGc(reference: WeakReference<*>): Boolean {
        repeat(30) {
            System.gc()
            System.runFinalization()
            if (reference.get() == null) return true
            Thread.sleep(25)
        }
        return reference.get() == null
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun answer(activity: Activity, request: ShadowActivity.PermissionsRequest, granted: Boolean) {
        if (granted) shadowOf(application).grantPermissions(camera)
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) {
                if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
        endPlatformRequest(activity)
    }

    private fun answeredRequestsOnOneActivity(screen: ActivityController<ComponentActivity>): WeakReference<PermissionController> {
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
        runBlocking {
            // A request answered, one whose caller gave up with its dialog up (answered afterwards), and an observer.
            val first: Deferred<PermissionStatus> = async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
            answer(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission), granted = false)
            withTimeout(10_000) { first.await() }
            val abandoned: Deferred<PermissionStatus> = async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
            abandoned.cancelAndJoin()
            answer(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission), granted = false)
        }
        return WeakReference(controller)
    }

    @Test
    fun `a controller whose requests ended on a live activity is collectable once its owner drops it`() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        shadowOf(application.packageManager).setShouldShowRequestPermissionRationale(camera, true)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()

        val controller: WeakReference<PermissionController> = answeredRequestsOnOneActivity(screen)

        assertTrue(clearedAfterGc(controller), "the controller is still reachable after every request ended")
        assertEquals(false, screen.get().isFinishing)
    }

    private fun requestAnsweredAcrossARecreation(): WeakReference<PermissionController> {
        val first: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val controller: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
        runBlocking {
            val request: Deferred<PermissionStatus> = async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
            val dialog: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(first.get()).lastRequestedPermission)
            // Destroyed with its state saved; the new instance is created and started, and gets the answer before it resumes.
            val state = Bundle()
            first.pause().saveInstanceState(state).stop().destroy()
            val second: ActivityController<ComponentActivity> =
                Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.create(state).start().postCreate(state)
            answer(second.get(), dialog, granted = true)
            second.resume()
            withTimeout(10_000) { request.await() }
        }
        return WeakReference(controller)
    }

    @Test
    fun `a controller whose request was answered across a recreation is collectable once its owner drops it`() {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)

        val controller: WeakReference<PermissionController> = requestAnsweredAcrossARecreation()

        assertTrue(clearedAfterGc(controller), "the recreated activity's registry still reaches the controller after its request ended")
    }
}
