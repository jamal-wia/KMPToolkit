package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * What a process kill and a restart do to the storage's temp area when ids and relative paths are
 * odd: a fresh storage over the same directory, with the process-wide migration record forgotten
 * (a new process has none), and files left behind the way a kill leaves them.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStorageRestartChaosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "restart_chaos")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    /** A new process: no migration record, a new storage instance. */
    private fun newProcess(): DownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, NoopLogger)
    }

    private class ChaosUnit(
        override val id: String,
        override val relativePath: String,
        override val tempExtension: String = "tmp",
    ) : DownloadUnit {
        override val apiPath: String = "/x/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = "g"
            override val units: List<DownloadUnit> get() = listOf(this@ChaosUnit)
        }

        override fun toString(): String = "ChaosUnit($id)"
    }

    private fun legacyFile(relative: String, bytes: ByteArray): File =
        File(root, relative).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }

    private fun transferAndCommit(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
        runBlocking { storage.commitResource(unit) }
    }

    @Test
    fun `a kill between link and unlink leaves both names and the next process keeps one`() {
        val unit = ChaosUnit(id = "models/v1", relativePath = "files/v1.bin")
        val bytes = ByteArray(100) { 3 }
        val legacy: File = legacyFile("tmp/models/v1.tmp", bytes)
        val storage: DownloaderStorage = newProcess()
        val destination = File(storage.getTempFilePath(unit))
        // The pass already ran once for getTempFilePath; undo it, then build the post-link state.
        destination.parentFile!!.mkdirs()
        destination.delete()
        legacy.parentFile!!.mkdirs()
        if (!legacy.exists()) legacy.writeBytes(bytes)
        Files.createLink(destination.toPath(), legacy.toPath())
        assertTrue(legacy.exists() && destination.exists())

        val restarted: DownloaderStorage = newProcess()

        assertEquals(TempFileState.Partial, restarted.tempFileState(unit))
        assertEquals(100L, restarted.getTempFileSize(unit))
        assertFalse(legacy.exists(), "the legacy name survived the next pass")
        // Deleting the temp file must not let the legacy name come back in a later process.
        restarted.deleteTempFile(unit)
        val third: DownloaderStorage = newProcess()
        assertEquals(TempFileState.None, third.tempFileState(unit))
    }

    @Test
    fun `a kill after the record moved but before the partial moved still verifies against the record`() {
        val unit = ChaosUnit(id = "models/v2", relativePath = "files/v2.bin")
        val good = ByteArray(50) { 9 }
        val digest: String = java.security.MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }
        // 3.0.0 state: partial plus record naming a DIFFERENT hash, so the commit must fail.
        val wrong: String = "00".repeat(32)
        legacyFile("tmp/models/v2.tmp.complete", good)
        legacyFile("tmp/expect/models/v2.sha256", "sha256:$wrong\n".encodeToByteArray())
        val s1: DownloaderStorage = newProcess()
        // Process 1 touches (moves record + complete), then dies right after.
        s1.tempFileState(unit)
        assertEquals(TempFileState.Complete, s1.tempFileState(unit))
        val s2: DownloaderStorage = newProcess()

        val failure = runCatching { runBlocking { s2.commitResource(unit) } }.exceptionOrNull()

        assertTrue(failure is ResourceIntegrityException, "commit used the bytes unchecked: $failure ($digest)")
        assertFalse(s2.isResourceAvailable(unit))
    }

    @Test
    fun `sixteen threads in two storages first touch the same legacy unit and exactly one file remains`() {
        val unit = ChaosUnit(id = "models/v3", relativePath = "files/v3.bin")
        val bytes = ByteArray(4096) { 1 }
        legacyFile("tmp/models/v3.tmp", bytes)
        val a = AndroidDownloaderStorage(context, config, NoopLogger)
        val b = AndroidDownloaderStorage(context, config, NoopLogger)
        val pool = Executors.newFixedThreadPool(16)
        val gate = CountDownLatch(1)
        val errors = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(16)
        try {
            repeat(16) { index: Int ->
                pool.execute {
                    try {
                        gate.await()
                        val storage: DownloaderStorage = if (index % 2 == 0) a else b
                        assertEquals(4096L, storage.getTempFileSize(unit))
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }
            }
            gate.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(null, errors.get())
        assertFalse(File(root, "tmp/models/v3.tmp").exists())
        assertContentEquals(bytes, File(a.getTempFilePath(unit)).readBytes())
    }

    @Test
    fun `a 248 byte id with a legacy partial from 3_0_0 is resumed`() {
        val id: String = "a".repeat(248)
        val unit = ChaosUnit(id = id, relativePath = "files/long.bin")
        val bytes = ByteArray(10) { 5 }
        legacyFile("tmp/$id.tmp", bytes)

        val storage: DownloaderStorage = newProcess()

        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
        assertEquals(10L, storage.getTempFileSize(unit))
    }

    @Test
    fun `an interrupted archive swap with the new tree in place and the temp file left is finished by the next commit or drop`() {
        val unit = ChaosUnit(id = "arch/one", relativePath = "archives/one")
        val storage: DownloaderStorage = newProcess()
        storage.beginTempFile(unit, null)
        // The temp file stays complete (never committed), a half-built staging tree and a trash
        // entry are what the kill left behind.
        File(storage.getTempFilePath(unit)).writeBytes(ByteArray(3))
        storage.markTempFileComplete(unit)
        val trash = File(root, "tmp/kmptoolkit-internal/trash/12345/deep").apply { mkdirs() }
        File(trash, "f").writeBytes(ByteArray(1))

        val restarted: DownloaderStorage = newProcess()
        restarted.deleteTempFile(unit)

        assertFalse(File(root, "tmp/kmptoolkit-internal/trash/12345").exists(), "trash from the dead process was not swept")
        assertEquals(TempFileState.None, restarted.tempFileState(unit))
    }
}
