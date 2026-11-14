package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A refusal flag the storage failed to clear is read as absent, but a refusal the user makes afterwards is
 * real: it is recorded again and takes precedence over the stale mark, even while the storage still cannot
 * remove anything.
 */
@RunWith(AndroidJUnit4::class)
class NewRefusalAfterFailedClearTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA

    private class RemoveFailingStorage(val inner: KeyValueStorage) : KeyValueStorage {
        var failRemove: Boolean = false
        override suspend fun get(key: String): StorageResult<String?> = inner.get(key)
        override suspend fun put(key: String, value: String): StorageResult<Unit> = inner.put(key, value)
        override suspend fun remove(key: String): StorageResult<Unit> =
            if (failRemove) StorageResult.Failure(StorageError.Unavailable()) else inner.remove(key)
        override suspend fun clear(): StorageResult<Unit> = inner.clear()
    }

    @Test
    fun `a new refusal after a failed clear is recorded again and wins over the stale mark`() = runBlocking {
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        var rationale = false
        val storage = RemoveFailingStorage(InMemoryKeyValueStorage())
        val controller: PermissionController = testController(
            application, storage, host = RecordingHost(answers = { false }), shouldShowRationale = { rationale },
        )
        controller.request(CommonPermission.Camera)
        rationale = true
        controller.request(CommonPermission.Camera)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
        // Granted in Settings while the storage cannot remove: the flags stay stored, marked stale.
        shadowOf(application).grantPermissions(camera)
        storage.failRemove = true
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
        // The system resets the permission: the stale flags read as absent.
        shadowOf(application).denyPermissions(camera)
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
        // The user refuses twice again: these refusals are real, whatever the stale mark said.
        controller.request(CommonPermission.Camera)
        rationale = true
        controller.request(CommonPermission.Camera)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
        storage.failRemove = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
    }
}
