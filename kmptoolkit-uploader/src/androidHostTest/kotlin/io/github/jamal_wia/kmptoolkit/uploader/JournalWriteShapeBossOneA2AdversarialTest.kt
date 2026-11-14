package io.github.jamal_wia.kmptoolkit.uploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * A2 boss round 1, claim of 05-platform-notes (outcome journal): entries are written through `kmptoolkit-files`
 * (`writeAtomically`, synced to disk before the rename); the temporary files of a write are in `outcomes-partial`, a
 * sibling of `outcomes`, and Android names them `.kmptoolkit-<digits>.tmp`.
 *
 * The temporary name itself is made inside `kmptoolkit-files` (`<tempPrefix><digits>.tmp`, default prefix `.kmptoolkit-`)
 * and is pinned by that module's own tests (FileNamesTest, RealFileAccessTests); this class pins what the uploader
 * asks of it: the real `FileAccess` is wrapped and every `writeAtomically` call is recorded.
 */
@RunWith(AndroidJUnit4::class)
class JournalWriteShapeBossOneA2AdversarialTest {

    private val root: File = createTempDirectory("a2boss1-journal").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Recording(private val inner: FileAccess) : FileAccess by inner {
        val writes: MutableList<Pair<String, WriteOptions>> = mutableListOf()
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            writes += path to options
            return inner.writeAtomically(path, bytes, options)
        }
    }

    @Test
    fun `an entry is written synced through writeAtomically with its temporary file in the partial sibling`() {
        val recording = Recording(createFileAccess())
        val outcomes = File(root, "outcomes")
        val partial = File(root, "outcomes-partial")
        val storage = AndroidOutcomeStorage(dir = outcomes, leftoversDir = partial, files = recording)

        assertTrue(storage.write("item-1", byteArrayOf(1, 2, 3)))

        val (path: String, options: WriteOptions) = recording.writes.single()
        assertEquals(File(outcomes, "item-1").path, path)
        assertEquals(Durability.Synced, options.durability, "synced to disk before the rename")
        assertEquals(partial.path, options.tempDirectory, "temporary files live in outcomes-partial")
        assertEquals(outcomes.parentFile, partial.parentFile, "outcomes-partial is a sibling of outcomes")
        assertTrue(options.tempPrefix == null || options.tempPrefix == ".kmptoolkit-", "documented prefix: ${options.tempPrefix}")
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "a finished write leaves no temporary file")
    }

    @Test
    fun `the default journal directories are outcomes and its partial sibling under the directory name`() {
        val context: android.content.Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()
        val storage: AndroidOutcomeStorage = AndroidOutcomeStorage.forContext(context, "a2boss1-dir")
        try {
            assertTrue(storage.write("item-1", byteArrayOf(1)))
            val base = File(context.noBackupFilesDir, "a2boss1-dir")
            assertTrue(File(File(base, "outcomes"), "item-1").isFile)
            assertTrue(File(base, "outcomes-partial").let { !it.exists() || it.list().isNullOrEmpty() })
        } finally {
            File(context.noBackupFilesDir, "a2boss1-dir").deleteRecursively()
        }
    }
}
