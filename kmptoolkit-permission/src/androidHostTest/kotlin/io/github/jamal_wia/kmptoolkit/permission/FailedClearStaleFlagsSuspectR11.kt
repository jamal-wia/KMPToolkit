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
 * Round 11 suspect (failure injection): a transient storage failure at the one `check` that sees the
 * permission granted leaves both refusal flags stored. The permission is later reset by the system (auto-reset of
 * an unused app, or a one-time grant ending): the platform would show its dialog again, but the stale flags make
 * the controller read PermanentlyDenied and so send the user to Settings. 04-api-reference: a flag the storage
 * cannot read, write or remove "is treated as not set" - after the failed remove it is still set.
 */
@RunWith(AndroidJUnit4::class)
class FailedClearStaleFlagsSuspectR11 {

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

    private fun journey(configure: (FlakyStorage) -> Unit, restore: (FlakyStorage) -> Unit) = runBlocking {
        application.declareInManifest(camera)
        shadowOf(application).denyPermissions(camera)
        var rationale = false
        val storage = FlakyStorage(InMemoryKeyValueStorage())
        val controller: PermissionController = testController(
            application, storage, host = RecordingHost(answers = { false }), shouldShowRationale = { rationale },
        )
        // Two refusals: the first leaves a rationale, the second the platform's "will not ask again".
        controller.request(CommonPermission.Camera)
        rationale = true
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.check(CommonPermission.Camera))
        controller.request(CommonPermission.Camera)
        rationale = false
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
        // The user grants it in Settings; the one check that sees it granted meets a storage hiccup.
        shadowOf(application).grantPermissions(camera)
        configure(storage)
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Camera))
        restore(storage)
        // The system resets the permission (an unused app, a one-time grant): the platform would ask again.
        shadowOf(application).denyPermissions(camera)
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a failed remove at the only granted check does not turn a later reset into PermanentlyDenied`() =
        journey({ it.failRemove = true }, { it.failRemove = false })

    @Test
    fun `an unreadable flag at the only granted check does not turn a later reset into PermanentlyDenied`() =
        journey({ it.failGet = true }, { it.failGet = false })
}
