package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * "Logged once per read and Android string" (05-platform-notes): eight real threads reading a status whose
 * platform reads all fail at the same moment still produce one warning per read and string, on a level
 * where the notification switch is read and on one where it is not, and the failing reads keep being
 * attempted (a read that failed once is not cached as failed: the platform may recover).
 */
@RunWith(AndroidJUnit4::class)
class PlatformFailureWarnedOnceThreadsTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class SafeLogger : Logger {
        val warnings = ConcurrentLinkedQueue<String>()
        override val tag: String = "safe"
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            if (level == LogLevel.WARN) warnings += message()
        }
    }

    private fun onEightThreads(block: suspend () -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        List(8) {
            Thread {
                start.await(5, TimeUnit.SECONDS)
                runBlocking { withTimeout(20_000) { repeat(40) { block() } } }
                done.countDown()
            }.also { it.isDaemon = true; it.start() }
        }
        start.countDown()
        assertTrue(done.await(40, TimeUnit.SECONDS), "the threads did not finish")
    }

    @Test
    fun `the notification switch below API 33 fails on eight threads and is reported once, and read every time`() {
        val logger = SafeLogger()
        val reads = AtomicInteger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            sdkInt = Build.VERSION_CODES.R,
            notificationsEnabled = { reads.incrementAndGet(); throw IllegalStateException("switch") },
            logger = logger,
        )

        onEightThreads { assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Notifications)) }

        assertEquals(1, logger.warnings.count { it.contains("notification switch") }, "warnings: ${logger.warnings}")
        assertEquals(8 * 40, reads.get(), "a failed read must be attempted again on the next check")
    }

    @Test
    fun `a failing policy and rationale lookup are reported once each per string on eight threads`() {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val logger = SafeLogger()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = InMemoryKeyValueStorage(),
            shouldShowRationale = { throw IllegalStateException("rationale") },
            isRevokedByPolicy = { throw IllegalStateException("policy") },
            logger = logger,
        )

        onEightThreads {
            controller.check(CommonPermission.Camera)
            controller.check(CommonPermission.Microphone)
        }

        for (string in listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) {
            assertEquals(1, logger.warnings.count { it.contains("policy lookup of $string") }, "policy $string: ${logger.warnings}")
            assertEquals(1, logger.warnings.count { it.contains("rationale lookup of $string") }, "rationale $string: ${logger.warnings}")
        }
    }
}
