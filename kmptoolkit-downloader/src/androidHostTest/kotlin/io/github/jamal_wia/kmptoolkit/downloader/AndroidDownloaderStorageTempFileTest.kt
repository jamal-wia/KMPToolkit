package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The temp-file half of the [DownloaderStorage] contract on Android: a partial transfer and a
 * complete one are different states, only a complete one commits, and a commit checks the bytes
 * against [DownloadUnit.sha256] before anything reaches the final path.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStorageTempFileTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "temp_file_test")
    private val storage: DownloaderStorage = createDownloaderStorage(context, config)
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `no temp file and an empty one are both None`() {
        val unit = TestUnit(id = "model")
        assertEquals(TempFileState.None, storage.tempFileState(unit))

        writePartial(unit, ByteArray(0))

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `bytes written to the temp path are Partial however many there are`() {
        val unit = TestUnit(id = "model")

        writePartial(unit, ByteArray(4096))

        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
        assertEquals(4096L, storage.getTempFileSize(unit))
    }

    @Test
    fun `marking a partial file complete makes it Complete and survives a new storage instance`() {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))

        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        // What process death leaves behind: a fresh instance must read the same state from disk.
        assertEquals(TempFileState.Complete, createDownloaderStorage(context, config).tempFileState(unit))
    }

    @Test
    fun `marking twice is harmless and marking nothing throws`() {
        val unit = TestUnit(id = "model")
        assertFailsWith<IllegalStateException> { storage.markTempFileComplete(unit) }

        writePartial(unit, ByteArray(10))
        storage.markTempFileComplete(unit)
        storage.markTempFileComplete(unit)

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
    }

    @Test
    fun `a partial temp file is never committed`() = runTest {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))

        assertFailsWith<IllegalStateException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.Partial, storage.tempFileState(unit), "the partial bytes stay for a resume")
    }

    @Test
    fun `deleteTempFile removes a complete file as well as a partial one`() {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))
        storage.markTempFileComplete(unit)
        writePartial(unit, ByteArray(5))

        storage.deleteTempFile(unit)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))
    }

    @Test
    fun `a complete file whose SHA-256 matches is committed`() = runTest {
        val unit = TestUnit(id = "model", sha256Text = ABC_SHA256.uppercase())
        writePartial(unit, "abc".toByteArray())
        storage.markTempFileComplete(unit)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals("abc", File(storage.getResourcePath(unit)).readText())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `a complete file whose SHA-256 differs is deleted and nothing is committed`() = runTest {
        val unit = TestUnit(id = "model", sha256Text = ABC_SHA256)
        writePartial(unit, "abd".toByteArray())
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertFalse(File(storage.getResourcePath(unit)).exists())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `a unit whose hash getter throws is a host error, not an integrity failure`() = runTest {
        val unit = TestUnit(id = "model", sha256Text = "not-a-hash")
        writePartial(unit, "abc".toByteArray())
        storage.markTempFileComplete(unit)

        assertFailsWith<IllegalArgumentException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `bytes that are not a database fail as an integrity failure`() = runTest {
        val unit = TestUnit(id = "quran-db", format = ResourceFormat.SqliteDatabase())
        writePartial(unit, ByteArray(2048) { 7 })
        storage.markTempFileComplete(unit)

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    @Test
    fun `deleting the temp file also removes the staging tree an interrupted extraction left`() {
        val unit = TestUnit(id = "model")
        writePartial(unit, ByteArray(10))
        storage.markTempFileComplete(unit)
        val staging = File(root, "tmp/staging-model/nested").apply { mkdirs() }
        File(staging, "marker").writeText("half-extracted")
        val other = File(root, "tmp/staging-other").apply { mkdirs() }

        storage.deleteTempFile(unit)

        assertFalse(File(root, "tmp/staging-model").exists())
        assertTrue(other.exists(), "another unit's staging tree is not touched")
        assertEquals(
            emptyList<String>(),
            File(root, "tmp/kmptoolkit-internal/trash").list()?.toList().orEmpty(),
            "the detached tree must be deleted, not left as trash",
        )
    }

    @Test
    fun `deleting a temp file sweeps a trash tree a killed process left, whatever unit it belonged to`() {
        val leftover = File(root, "tmp/kmptoolkit-internal/trash/gone-123/nested").apply { mkdirs() }
        File(leftover, "file").writeText("half-deleted")
        val other = File(root, "tmp/staging-other").apply { mkdirs() }
        // A unit whose id merely starts with "trash-" owns a temp file that must survive the sweep.
        val lookalike = TestUnit(id = "trash-model")
        writePartial(lookalike, ByteArray(10))

        storage.deleteTempFile(TestUnit(id = "unrelated"))

        assertFalse(File(root, "tmp/kmptoolkit-internal/trash/gone-123").exists(), "the leftover trash tree is reclaimed")
        assertTrue(other.exists(), "another unit's staging tree is not touched")
        assertTrue(File(storage.getTempFilePath(lookalike)).exists(), "a unit id starting with trash- is not trash")
    }

    @Test
    fun `deleting the temp file leaves the staging tree alone while a commit of the unit runs`() = runTest {
        val unit = TestUnit(id = "model")
        val staging = File(root, "tmp/staging-model/nested").apply { mkdirs() }
        val gate = CompletableDeferred<Unit>()
        val commit: Job = launch(Dispatchers.Unconfined) {
            UnitCommitLocks.withUnitLock(root.absolutePath, "model") { gate.await() }
        }

        storage.deleteTempFile(unit)

        assertTrue(staging.exists(), "an extraction in progress must survive a cancel")
        gate.complete(Unit)
        withTimeout(5_000) { commit.join() }
        storage.deleteTempFile(unit)
        assertFalse(File(root, "tmp/staging-model").exists(), "reclaimed once no commit runs")
    }

    // 18
    @Test
    fun `on a case-insensitive volume a cancel of one case variant leaves the staging tree alone while a commit of the other runs`() = runTest {
        val folding: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger, caseInsensitive = true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committing = HoldingUnit(id = "Model", entered = entered, release = release)
        File(folding.getTempFilePath(committing)).apply { parentFile!!.mkdirs() }.writeBytes("abc".toByteArray())
        folding.markTempFileComplete(committing)
        val staging = File(root, "tmp/staging-model/nested").apply { mkdirs() }
        val commit: Deferred<Unit> = async(Dispatchers.IO) {
            runCatching { folding.commitResource(committing) }
            Unit
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the commit never reached its hash check")

            folding.deleteTempFile(TestUnit(id = "model"))

            assertTrue(staging.exists(), "a cancel of 'model' must not detach the tree while 'Model' commits")
        } finally {
            release.countDown()
        }
        // Real time: the commit runs on another thread, and runTest's virtual clock would skip ahead.
        withContext(Dispatchers.Default) { withTimeout(5_000) { commit.await() } }
    }

    private class HoldingUnit(
        override val id: String,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : DownloadUnit {
        /** Read inside the commit's lock, so it parks the commit exactly there; bounded so it can never hang. */
        override val sha256: Sha256?
            get() {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                return null
            }
        override val apiPath: String = "/resources/$id"
        override val relativePath: String = "resources/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@HoldingUnit)
        }
        override fun toString(): String = "HoldingUnit($id)"
    }

    private fun writePartial(unit: DownloadUnit, bytes: ByteArray) {
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
    }

    private class TestUnit(
        override val id: String,
        /** The hash as text; parsed lazily so a malformed one throws from the getter, as a host's would. */
        private val sha256Text: String? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val sha256: Sha256? get() = sha256Text?.let { Sha256.parse(it) }
        override val apiPath: String = "/resources/$id"
        override val relativePath: String = "resources/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@TestUnit)
        }
        override fun toString(): String = "TestUnit($id)"
    }

    private companion object {
        /** SHA-256 of the three ASCII bytes "abc", the FIPS 180-2 test vector. */
        const val ABC_SHA256: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
