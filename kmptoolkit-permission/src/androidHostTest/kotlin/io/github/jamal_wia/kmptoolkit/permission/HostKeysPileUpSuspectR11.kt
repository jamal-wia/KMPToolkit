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

/** Round 11 suspect: the default host leaves one key and request code per host in the activity's registry. */
@RunWith(AndroidJUnit4::class)
class HostKeysPileUpSuspectR11 {

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
    fun `three hundred hosts that each showed one dialog leave the activity registry no larger than the live ones`() {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val registry: ActivityResultRegistry = screen.get().activityResultRegistry
        val before: Map<String, Int> = registrySizes(registry)
        var answered = 0
        repeat(300) {
            val host = ActivityResultPermissionRequestHost(access)
            assertTrue(host.launch(listOf(camera)) { answered++ }, "round $it: not launched")
            answerDenied(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
            generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertEquals(300, answered)
        val after: Map<String, Int> = registrySizes(registry)
        // The hosts are gone and none has a request left: nothing of them may stay in the registry of the
        // activity that outlives them (a controller per screen of a single-activity app is this shape).
        assertTrue((after["keyToRc"] ?: 0) <= 2 && (after["rcToKey"] ?: 0) <= 2, "the registry kept keys of finished hosts: $before -> $after")
    }


    @Test
    fun `forty process generations that each answered one dialog do not carry forty generations of keys in the saved state`() {
        shadowOf(application).denyPermissions(camera)
        var screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        repeat(40) { generation ->
            PermissionHostKeys.restartProcessForTest()
            val host = ActivityResultPermissionRequestHost(access)
            assertTrue(host.launch(listOf(camera)) { }, "generation $generation: not launched")
            answerDenied(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
            generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            // The process dies and the system restores the task: the registry comes back from the saved state.
            val state = android.os.Bundle()
            screen.pause().saveInstanceState(state).stop().destroy()
            screen = Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.create(state).start().postCreate(state).resume()
        }
        val sizes: Map<String, Int> = registrySizes(screen.get().activityResultRegistry)
        assertTrue((sizes["keyToRc"] ?: 0) <= 4, "the saved registry carries every dead generation's keys: $sizes")
    }
}
