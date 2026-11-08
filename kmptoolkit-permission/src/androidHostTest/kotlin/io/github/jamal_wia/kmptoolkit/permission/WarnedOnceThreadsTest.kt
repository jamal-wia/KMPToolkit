package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Warnings the controller promises to give once ("a status observed on every resume warns once"; "a storage
 * that stays down warns once per flag") stay single when eight real threads read the same status at the
 * same moment.
 */
@RunWith(AndroidJUnit4::class)
class WarnedOnceThreadsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class SafeLogger : Logger {
        val warnings = ConcurrentLinkedQueue<String>()
        override val tag: String = "safe"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            if (level == LogLevel.WARN) warnings += message()
        }
    }

    private class BrokenStorage : KeyValueStorage {
        override suspend fun get(key: String): StorageResult<String?> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
        override suspend fun clear(): StorageResult<Unit> = StorageResult.Failure(StorageError.Unavailable())
    }

    private fun onEightThreads(block: suspend () -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        List(8) {
            Thread {
                start.await(5, TimeUnit.SECONDS)
                runBlocking { withTimeout(20_000) { repeat(50) { block() } } }
                done.countDown()
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        assertTrue(done.await(40, TimeUnit.SECONDS), "the threads did not finish")
    }

    @Test
    fun `a storage that stays down is reported once per flag, however many threads read it at once`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val logger = SafeLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = BrokenStorage(),
            logger = logger,
        )

        onEightThreads { controller.check(CommonPermission.Camera) }

        assertEquals(1, logger.warnings.count { it.contains("permission flag") }, "warnings: ${logger.warnings}")
    }

    @Test
    fun `an undeclared permission is reported once however many threads check it at once`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val logger = SafeLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage(),
            declaredPermissions = { setOf(Manifest.permission.RECORD_AUDIO) },
            logger = logger,
        )

        onEightThreads { controller.check(CommonPermission.Camera) }

        assertEquals(1, logger.warnings.count { it.contains("declare") }, "warnings: ${logger.warnings}")
    }

    @Test
    fun `a scan without the never-for-location declaration is reported once however many threads check it at once`() {
        val logger = SafeLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage(),
            sdkInt = Build.VERSION_CODES.S,
            disavowsLocationForScan = { false },
            logger = logger,
        )

        onEightThreads { controller.check(CommonPermission.BluetoothScan) }

        assertEquals(1, logger.warnings.count { it.contains("neverForLocation") }, "warnings: ${logger.warnings}")
    }
}
