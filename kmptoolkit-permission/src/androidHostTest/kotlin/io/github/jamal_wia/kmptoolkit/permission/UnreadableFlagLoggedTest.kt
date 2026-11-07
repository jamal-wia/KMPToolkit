package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `createPermissionController`'s `logger` is documented as "where a dialog that could not be shown, an
 * undeclared permission, an unreadable flag or a screen that could not be opened is reported".
 */
@RunWith(AndroidJUnit4::class)
class UnreadableFlagLoggedTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class BrokenStorage : KeyValueStorage {
        override suspend fun get(key: String): StorageResult<String?> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun clear(): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
    }

    @Test
    fun `a flag the storage cannot read is reported to the logger and nothing throws`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val logger = RecordingLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = BrokenStorage(),
            host = RecordingHost(answers = { false }),
            logger = logger,
        )

        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))

        assertTrue(
            logger.entries.any { it.level >= LogLevel.WARN },
            "an unreadable flag was swallowed without a word: ${logger.entries}",
        )
    }

    @Test
    fun `a flag the storage cannot write is reported to the logger`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val logger = RecordingLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = BrokenStorage(),
            host = RecordingHost(answers = { false }),
            logger = logger,
        )

        controller.request(CommonPermission.Camera)

        assertTrue(
            logger.entries.any { it.level >= LogLevel.WARN },
            "a refusal that could not be remembered was swallowed without a word: ${logger.entries}",
        )
    }
}
