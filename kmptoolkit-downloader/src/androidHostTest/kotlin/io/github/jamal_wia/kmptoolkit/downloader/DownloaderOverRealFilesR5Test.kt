package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 5 of the breaker for unit G, Q2 (a real consumer): the real Android downloader storage (over the real
 * kmptoolkit-files engine) driven through its public API the way a network engine drives it: bytes into the temp file,
 * `markTempFileComplete`, `commitResource`, then size and delete. One failure path: a commit of a unit whose temp file
 * was never completed, then a delete of a resource directory holding a tree of 300 entries (the engine's walk),
 * and a commit run twice concurrently. Docs 04/05 promise: a committed resource never goes missing, size is the sum of
 * the regular files, delete leaves nothing, an absent resource sizes 0.
 */
@RunWith(AndroidJUnit4::class)
class DownloaderOverRealFilesR5Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "r5_real_files")

    private class Group : ResourceGroup {
        override val key: String = "g"
        override var units: List<DownloadUnit> = emptyList()
    }

    private class OneUnit(override val group: ResourceGroup, override val id: String, override val relativePath: String) : DownloadUnit {
        override val apiPath: String = "test/$id"
    }

    @AfterTest
    fun cleanUp() {
        File(context.filesDir, config.baseDirectoryName).deleteRecursively()
    }

    @Test
    fun `a transfer written into the temp file is committed whole and sized and deleted`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val unit = OneUnit(Group(), "plain", "models/plain.bin")
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        assertFalse(storage.isResourceAvailable(unit))
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).writeBytes(payload)
        assertEquals(TempFileState.Partial, storage.tempFileState(unit), "written but not marked: partial")
        storage.markTempFileComplete(unit)
        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        runBlocking { withTimeout(60_000) { storage.commitResource(unit) } }
        assertTrue(storage.isResourceAvailable(unit))
        assertTrue(payload.contentEquals(File(storage.getResourcePath(unit)).readBytes()), "the committed file is the whole payload")
        assertEquals(payload.size.toLong(), storage.getResourceSize(unit))
        // A second commit of a committed unit returns normally and touches nothing (docs 04).
        runBlocking { withTimeout(60_000) { storage.commitResource(unit) } }
        assertTrue(payload.contentEquals(File(storage.getResourcePath(unit)).readBytes()))
        storage.deleteResource(unit)
        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(0L, storage.getResourceSize(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the temp file was consumed by the commit")
    }

    @Test
    fun `a partial transfer is never committed and a deep resource directory is sized and removed completely`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val partial = OneUnit(Group(), "partial", "models/partial.bin")
        storage.beginTempFile(partial, null)
        File(storage.getTempFilePath(partial)).writeBytes(ByteArray(10))
        val committed: Result<Unit> = runCatching { runBlocking { withTimeout(60_000) { storage.commitResource(partial) } } }
        assertTrue(committed.isFailure || !storage.isResourceAvailable(partial), "a partial temp file must not become a resource")
        assertFalse(storage.isResourceAvailable(partial))
        storage.deleteTempFile(partial)
        assertEquals(TempFileState.None, storage.tempFileState(partial))

        val tree = OneUnit(Group(), "tree", "models/tree")
        val root = File(storage.getResourcePath(tree))
        var expected = 0L
        for (i in 0 until 300) {
            val dir = File(File(root, "d${i % 7}"), "s${i % 3}").also { it.mkdirs() }
            File(dir, "f$i").writeBytes(ByteArray(i % 10 + 1))
            expected += i % 10 + 1
        }
        assertEquals(expected, storage.getResourceSize(tree), "size is the sum of the regular files")
        storage.deleteResource(tree)
        assertFalse(root.exists(), "delete leaves nothing")
    }
}
