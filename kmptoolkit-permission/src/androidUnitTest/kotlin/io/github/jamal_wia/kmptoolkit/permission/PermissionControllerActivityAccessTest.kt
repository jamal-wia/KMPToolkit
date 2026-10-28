package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivityAccess
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * The factory overload that takes an [ActivityAccess], and the part of the request only a real
 * factory wires: the system dialog answers just before the activity is resumed again, and the
 * rationale — the difference between "refused once" and "dismissed" — can only be read once it is.
 */
@RunWith(AndroidJUnit4::class)
class PermissionControllerActivityAccessTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val activity: RationaleActivity = Robolectric.buildActivity(RationaleActivity::class.java).setup().get()

    /** An activity whose rationale answer the test sets — what the system flips after a refusal. */
    class RationaleActivity : Activity() {
        var rationale: Boolean = false

        override fun shouldShowRequestPermissionRationale(permission: String): Boolean = rationale
    }

    /** An [ActivityAccess] whose resumed activity the test switches on and off. */
    private class ScriptedActivityAccess(private val activity: Activity) : ActivityAccess {
        var resumed: Boolean = true
        private val listeners: MutableList<(Activity) -> Unit> = mutableListOf()

        override fun <R> withActivity(block: (Activity) -> R): R? = if (resumed) block(activity) else null

        override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription {
            listeners += listener
            if (resumed) listener(activity)
            return object : ActivitySubscription {
                override fun cancel() {
                    listeners -= listener
                }
            }
        }

        fun resume() {
            resumed = true
            listeners.toList().forEach { it(activity) }
        }

        val listenerCount: Int get() = listeners.size

        override fun release() = Unit
    }

    /** Refuses the way the system dialog does: the host activity is paused, the rationale turns on. */
    private fun refusingHost(access: ScriptedActivityAccess): PermissionRequestHost = object : RecordingHost() {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            access.resumed = false
            activity.rationale = true
            onResult(androidPermissions.associateWith { false })
            return true
        }
    }

    @BeforeTest
    fun declareCamera() {
        application.declareInManifest(Manifest.permission.CAMERA)
    }

    @Test
    fun `a refusal answered before the resume is read once the activity is back`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val access = ScriptedActivityAccess(activity)
        val handler: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), refusingHost(access), access)
        launch {
            delay(100)
            access.resume()
        }

        val status: PermissionStatus = handler.request(CommonPermission.Camera)

        assertEquals(PermissionStatus.Denied(explainFirst = true), status)
        assertEquals(0, access.listenerCount, "the resume listener must not outlive the request")
    }

    @Test
    fun `an activity that never comes back does not turn the refusal permanent`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val access = ScriptedActivityAccess(activity)
        val handler: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), refusingHost(access), access)

        val status: PermissionStatus = handler.request(CommonPermission.Camera)

        assertEquals(PermissionStatus.Denied(explainFirst = false), status)
        assertEquals(0, access.listenerCount)
    }

    @Test
    fun `a request cancelled while waiting for the activity leaves no listener behind`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val access = ScriptedActivityAccess(activity)
        val handler: PermissionController =
            createPermissionController(application, InMemoryKeyValueStorage(), refusingHost(access), access)

        val requesting = launch { handler.request(CommonPermission.Camera) }
        runCurrent()
        assertEquals(1, access.listenerCount, "the request should be waiting for the activity")

        requesting.cancel()
        requesting.join()

        assertEquals(0, access.listenerCount)
    }

    @Test
    fun `the rationale is asked of the activity the given access tracks`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        activity.rationale = true
        val access = ScriptedActivityAccess(activity)

        val status: PermissionStatus =
            createPermissionController(application, InMemoryKeyValueStorage(), refusingHost(access), access)
                .check(CommonPermission.Camera)

        assertEquals(PermissionStatus.Denied(explainFirst = true), status)
    }
}
