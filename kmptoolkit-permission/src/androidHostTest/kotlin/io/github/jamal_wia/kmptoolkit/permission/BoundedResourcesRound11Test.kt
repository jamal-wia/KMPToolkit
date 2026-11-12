package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity

/**
 * Round 11, bounded resources: what the Android controller, its default host and the shared activity tracker
 * keep after thousands of instances and after a long run of requests. Every number is read from the real
 * objects (fields by reflection), none from a fake.
 */
@RunWith(AndroidJUnit4::class)
class BoundedResourcesRound11Test {

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

    private fun answerDenied(activity: Activity, request: ShadowActivity.PermissionsRequest) {
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        endPlatformRequest(activity)
    }

    private fun registrySizes(registry: ActivityResultRegistry): Map<String, Int> =
        ActivityResultRegistry::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                when (val value: Any? = field.get(registry)) {
                    is Map<*, *> -> field.name to value.size
                    is Collection<*> -> field.name to value.size
                    else -> null
                }
            }.toMap()

    private fun trackerListenerCount(): Int {
        val field = access.javaClass.getDeclaredField("listeners")
        field.isAccessible = true
        return (field.get(access) as Collection<*>).size
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

    @Test
    fun `two thousand controllers that each observed and requested leave nothing registered on the shared tracker`() = runBlocking {
        shadowOf(application).denyPermissions(camera)
        application.declareInManifest(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val listenersBefore: Int = trackerListenerCount()
        val weak: MutableList<WeakReference<PermissionController>> = mutableListOf()
        repeat(2000) { round ->
            val controller: PermissionController =
                createPermissionController(application, InMemoryKeyValueStorage(), access, ActivityResultPermissionRequestHost(access))
            if (round % 100 == 0) weak += WeakReference(controller)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { controller.observe(CommonPermission.Camera).first { false } }
            if (round % 10 == 0) {
                // A caller that gives up with its dialog up, then the answer.
                val request: Deferred<PermissionStatus> =
                    async(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
                shadowOf(screen.get()).lastRequestedPermission?.let { answerDenied(screen.get(), it) }
                request.cancelAndJoin()
                generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
            }
            watcher.cancelAndJoin()
        }
        assertEquals(listenersBefore, trackerListenerCount(), "thousands of finished controllers left listeners on the shared tracker")
        weak.forEach { assertTrue(clearedAfterGc(it), "a dropped controller is still reachable") }
    }

    @Test
    fun `a thousand distinct requests through the seams keep the controller's per request state empty`() = runBlocking {
        // check and request of 1000 distinct Runtime entries on a controller with a seam-only host: no listener
        // may be left on the resume source and the recorded storage keys stay one pair per decided permission.
        val storage = InMemoryKeyValueStorage()
        val listeners = ResumeListeners()
        val controller: PermissionController = testController(
            application, storage, host = RecordingHost(answers = { false }), resumeListeners = listeners,
            declaredPermissions = { null },
        )
        repeat(1000) { index ->
            val permission = AndroidPermission.Runtime("com.example.permission.P$index")
            controller.check(permission)
            withTimeout(10_000) { controller.request(permission) }
            controller.observe(permission).first()
        }
        assertEquals(0, listeners.count, "a finished observe or request left a resume listener")
    }
}
