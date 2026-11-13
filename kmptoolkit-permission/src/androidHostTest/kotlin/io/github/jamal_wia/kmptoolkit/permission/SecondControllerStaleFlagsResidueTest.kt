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
 * The documented residue of the failed-clear fix (04-api-reference, `logger`): the memory of a refusal flag whose
 * removal failed belongs to the one controller that met the failure, not to the storage. A SECOND controller over
 * the same storage (a controller per screen, or another process of the app) reads the stale flags as a real refusal
 * after a later system reset, until a controller that knows the failure (or a granted check) clears the storage.
 * This test pins what the code does today so that a future fix (a tombstone kept in the storage itself) flips it
 * deliberately; the opposite assertion is the round 13 suspect, kept out of the tree.
 */
@RunWith(AndroidJUnit4::class)
class SecondControllerStaleFlagsResidueTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA

    private class FlakyStorage(val inner: KeyValueStorage) : KeyValueStorage {
        var failRemove: Boolean = false
        var failGet: Boolean = false
        override suspend fun get(key: String): StorageResult<String?> =
            if (failGet) StorageResult.Failure(StorageError.Unavailable()) else inner.get(key)
        override suspend fun put(key: String, value: String): StorageResult<Unit> = inner.put(key, value)
        override suspend fun remove(key: String): StorageResult<Unit> =
            if (failRemove) StorageResult.Failure(StorageError.Unavailable()) else inner.remove(key)
        override suspend fun clear(): StorageResult<Unit> = inner.clear()
    }

    @Test
    fun `a second controller reads the stale flags until a granted check clears them`() = runBlocking {
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        var rationale = false
        val storage = FlakyStorage(InMemoryKeyValueStorage())
        fun controller(): PermissionController = testController(
            application, storage, host = RecordingHost(answers = { false }), shouldShowRationale = { rationale },
        )
        val first: PermissionController = controller()
        first.request(CommonPermission.Camera)
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), first.check(CommonPermission.Camera))
        first.request(CommonPermission.Camera)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, first.check(CommonPermission.Camera))
        // The user grants it in Settings; the one check that sees it granted meets a storage hiccup.
        shadowOf(application).grantPermissions(camera)
        storage.failRemove = true
        assertEquals(PermissionStatus.Granted, first.check(CommonPermission.Camera))
        storage.failRemove = false
        assertEquals(
            StorageResult.Success("true"),
            storage.inner.get(rationaleSeenKey("test", CommonPermission.Camera)),
            "precondition: the failed remove left the rationale flag stored",
        )
        // The system resets the permission; the app's other controller (another screen) is the one asked.
        shadowOf(application).denyPermissions(camera)
        val second: PermissionController = controller()
        // Asked first, before the controller that saw the failure retries the removal on its next read.
        assertEquals(PermissionStatus.PermanentlyDenied, second.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.NotDetermined, first.check(CommonPermission.Camera), "control: the controller that saw the failure")
        // Healed for everyone: that read removed the flags from the storage.
        assertEquals(PermissionStatus.NotDetermined, second.check(CommonPermission.Camera))
    }
}
