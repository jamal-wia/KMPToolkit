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
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

/**
 * Two controllers at once over one real activity (the shared host case and an app-owned platform request; Robolectric does not enforce the framework rule that a second dialog is refused at once, so that case cannot be run here). The platform allows ONE permission
 * dialog per activity: a second `requestPermissions` while one is up is cancelled at once with empty
 * results ("Can request only one set of permissions at a time"). The contract: a dialog that returned
 * nothing leaves no trace and the status is whatever it was, so the controller that lost the race reads
 * its old status, records no refusal for a dialog the user never saw, and can ask again afterwards.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TwoControllersOneActivityTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO
    private val accesses: MutableList<ActivityAccess> = mutableListOf()
    private val screens: MutableList<ActivityController<ComponentActivity>> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        screens.forEach { it.close() }
    }

    /** A runTest whose still-running requests are cancelled when the body ends, so a failed assertion is what is reported. */
    private fun race(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) {
        try {
            block()
        } finally {
            coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.cancel() }
        }
    }

    private class WriteCountingStorage(private val inner: KeyValueStorage = InMemoryKeyValueStorage()) : KeyValueStorage by inner {
        var writes: Int = 0
        override suspend fun put(key: String, value: String): StorageResult<Unit> {
            writes++
            return inner.put(key, value)
        }

        override suspend fun remove(key: String): StorageResult<Unit> {
            writes++
            return inner.remove(key)
        }
    }

    private fun screen(): ComponentActivity {
        application.declareInManifest(camera, microphone)
        shadowOf(application).denyPermissions(camera, microphone)
        val controller: ActivityController<ComponentActivity> =
            Robolectric.buildActivity(ComponentActivity::class.java).also { screens += it }.setup()
        return controller.get()
    }

    private fun access(): ActivityAccess = createActivityAccess(application).also { accesses += it }

    private fun endPlatformRequest(activity: Activity) {
        val flag = Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest")
        flag.isAccessible = true
        flag.setBoolean(activity, false)
    }

    /** The user's answer to the dialog that is up: [granted] strings are granted, the rest refused. */
    private fun answer(activity: ComponentActivity, granted: Set<String>) {
        val up: ShadowActivity.PermissionsRequest = assertNotNull(shadowOf(activity).lastRequestedPermission)
        shadowOf(application).grantPermissions(*up.requestedPermissions.filter { it in granted }.toTypedArray())
        endPlatformRequest(activity)
        @Suppress("DEPRECATION")
        activity.onRequestPermissionsResult(
            up.requestCode,
            up.requestedPermissions,
            IntArray(up.requestedPermissions.size) {
                if (up.requestedPermissions[it] in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            },
        )
        endPlatformRequest(activity)
    }

    private fun controller(
        access: ActivityAccess,
        storage: KeyValueStorage = InMemoryKeyValueStorage(),
        host: PermissionRequestHost = ActivityResultPermissionRequestHost(access),
    ): PermissionController = createPermissionController(
        context = application,
        storage = storage,
        activityAccess = access,
        host = host,
    )

    @Test
    fun `two controllers sharing one host serialise by refusing the second launch and leave no flag`() = race {
            val shared: ActivityAccess = access()
            val activity: ComponentActivity = screen()
            val host = ActivityResultPermissionRequestHost(shared)
            val storageB = WriteCountingStorage()
            val a: PermissionController = controller(shared, host = host)
            val b: PermissionController = controller(shared, storageB, host)

            val first: Deferred<PermissionStatus> = async { a.request(CommonPermission.Camera) }
            assertFalse(first.isCompleted)
            val second: PermissionStatus = b.request(CommonPermission.Microphone)

            assertEquals(PermissionStatus.NotDetermined, second)
            assertEquals(0, storageB.writes)
            answer(activity, granted = setOf(camera))
            assertEquals(PermissionStatus.Granted, first.await())

            // The host is free again: the loser's retry shows its dialog.
            val retry: Deferred<PermissionStatus> = async { b.request(CommonPermission.Microphone) }
            assertFalse(retry.isCompleted, "the host did not free up after the first request ended")
            answer(activity, granted = setOf(microphone))
            assertEquals(PermissionStatus.Granted, retry.await())
        }

    @Test
    fun `a request through a host while the platform still has a dialog of another app component up returns at once`() = race {
            // The app itself started a permission request outside the toolkit; the platform flag is set.
            val shared: ActivityAccess = access()
            val activity: ComponentActivity = screen()
            val storage = WriteCountingStorage()
            val controller: PermissionController = controller(shared, storage)
            @Suppress("DEPRECATION")
            androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 4242)

            val result: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }

            assertTrue(result.isCompleted, "the request hung behind a dialog the toolkit does not own")
            assertEquals(PermissionStatus.NotDetermined, result.await())
            assertEquals(0, storage.writes)
        }
}
