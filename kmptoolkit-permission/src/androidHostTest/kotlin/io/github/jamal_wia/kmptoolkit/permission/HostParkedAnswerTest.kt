package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.createActivityAccess
import java.lang.ref.WeakReference
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
 * The answer to a dialog that arrives at the recreated activity BEFORE it resumes (the framework delivers
 * results ahead of `onResume`): the registry parks it, and the host receives it from inside `register`,
 * while it is still attaching to that activity.
 */
@RunWith(AndroidJUnit4::class)
class HostParkedAnswerTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val access: ActivityAccess = createActivityAccess(application)
    private val activities: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val camera: String = Manifest.permission.CAMERA

    @AfterTest
    fun tearDown() {
        access.release()
        activities.forEach { it.close() }
    }

    private class Payload

    private fun clearedAfterGc(reference: WeakReference<*>): Boolean {
        repeat(30) {
            System.gc()
            System.runFinalization()
            if (reference.get() == null) return true
            Thread.sleep(25)
        }
        return reference.get() == null
    }

    private fun callbackCount(registry: ActivityResultRegistry): Int {
        val field = ActivityResultRegistry::class.java.declaredFields.first { it.name == "keyToCallback" || it.name == "mKeyToCallback" }
        field.isAccessible = true
        return (field.get(registry) as Map<*, *>).size
    }

    private fun endPlatformRequest(activity: Activity) {
        val field = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        field.isAccessible = true
        field.setBoolean(activity, false)
    }

    private fun launchWithPayload(host: ActivityResultPermissionRequestHost, answers: MutableList<Map<String, Boolean>>): WeakReference<Payload> {
        val payload = Payload()
        assertTrue(host.launch(listOf(camera)) { payload.hashCode(); answers += it }, "not launched")
        return WeakReference(payload)
    }

    @Test
    fun `an answer parked in the recreated activity before it resumes is delivered once and leaves no callback in its registry`() {
        shadowOf(application).denyPermissions(camera)
        val first: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.setup()
        val host = ActivityResultPermissionRequestHost(access)
        val answers: MutableList<Map<String, Boolean>> = mutableListOf()
        val payload: WeakReference<Payload> = launchWithPayload(host, answers)
        val request: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(first.get()).lastRequestedPermission)

        // The activity is destroyed with its state saved; the new one is created and started, not yet resumed.
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val second: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { activities += it }.create(state).start().postCreate(state)
        assertEquals(emptyList(), answers)

        // The framework delivers the answer to the new instance now, ahead of its onResume.
        shadowOf(application).grantPermissions(camera)
        endPlatformRequest(second.get())
        @Suppress("DEPRECATION")
        second.get().onRequestPermissionsResult(
            request.requestCode,
            request.requestedPermissions,
            IntArray(request.requestedPermissions.size) { PackageManager.PERMISSION_GRANTED },
        )
        assertEquals(emptyList(), answers, "the answer reached the request before any activity was attached")
        second.resume()

        assertEquals(1, answers.size, "the parked answer was not delivered exactly once on resume: $answers")
        assertEquals(0, callbackCount(second.get().activityResultRegistry), "the finished request's callback is still registered in the new activity's registry")
        assertTrue(clearedAfterGc(payload), "the registry still pins the finished request's result lambda")
    }
}
