package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
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
 * What the host leaves in an activity's `ActivityResultRegistry` over many requests: every finished request
 * must take its key, its request code and its callback with it, or a long-lived activity accumulates them.
 */
@RunWith(AndroidJUnit4::class)
class HostRegistryChurnTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    /** The sizes of the registry's bookkeeping collections, by their private field names. */
    private fun registryState(registry: ActivityResultRegistry): Map<String, Int> =
        ActivityResultRegistry::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .mapNotNull { field ->
                field.isAccessible = true
                val value: Any? = field.get(registry)
                when (value) {
                    is Map<*, *> -> field.name to value.size
                    is Collection<*> -> field.name to value.size
                    is android.os.Bundle -> field.name to value.size()
                    else -> null
                }
            }.toMap()

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun answer(activity: Activity, request: ShadowActivity.PermissionsRequest) {
        shadowOf(application).grantPermissions(camera)
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        endPlatformRequest(activity)
    }

    @Test
    fun `two hundred requests on one activity through one host keep at most the host two keys and no callback`() {
        shadowOf(application).denyPermissions(camera)
        val screen: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val registry: ActivityResultRegistry = screen.get().activityResultRegistry
        val before: Map<String, Int> = registryState(registry)
        assertTrue(before.isNotEmpty(), "the registry's fields were not found: the test cannot measure anything")
        var answered = 0
        val host = ActivityResultPermissionRequestHost(access)

        repeat(200) { round ->
            // Denied again each round: a granted permission is answered synchronously (posted), with no dialog.
            shadowOf(application).denyPermissions(camera)
            val launched: Boolean = if (round % 2 == 0) {
                host.launch(listOf(camera)) { answered++ }
            } else {
                host.launchForResult(Intent(Intent.ACTION_VIEW)) { answered++ }
            }
            assertTrue(launched, "round $round: not launched")
            if (round % 2 == 0) {
                answer(screen.get(), assertNotNull(shadowOf(screen.get()).lastRequestedPermission))
                // The platform's permission request is itself a start-for-result; consume it so a later round finds its own.
                generateSequence { shadowOf(screen.get()).nextStartedActivityForResult }.count()
            } else {
                val started: ShadowActivity.IntentForResult = assertNotNull(shadowOf(screen.get()).nextStartedActivityForResult, "round $round: nothing started")
                shadowOf(screen.get()).receiveResult(started.intent, Activity.RESULT_OK, Intent())
            }
        }

        assertEquals(200, answered, "some answers were lost")
        val after: Map<String, Int> = registryState(registry)
        // A key and its request code stay registered once (androidx keeps them while a result is being delivered);
        // one host has two keys, and a request must not add to them.
        assertTrue((after["keyToRc"] ?: 0) <= 2 && (after["rcToKey"] ?: 0) <= 2, "the registry grew with the requests: $before -> $after")
        listOf("keyToCallback", "launchedKeys", "pendingResults", "parsedPendingResults", "keyToLifecycleContainers").forEach { name ->
            assertEquals(before[name], after[name], "the registry's $name kept entries of finished requests: $after")
        }
    }
}
