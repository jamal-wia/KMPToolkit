package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Round 5 of the breaker for unit G, Q2 (a real consumer): the recorder's real Android file layer over the real
 * kmptoolkit-files engine. Docs (recorder 05-platform-notes "Free space", "Directory creation", 04 `availableBytes`): free
 * space comes from the platform's usable space of the output directory, a path that is not an existing directory is
 * reported unknown (-1), directory creation goes through createDirectories and then checks the path is a directory (links
 * followed) and writable, deleting is best effort and never throws.
 */
@RunWith(AndroidJUnit4::class)
class RecordingFileSystemOverRealFilesR5Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileSystem = AndroidRecordingFileSystem(context)
    private val base: File = Files.createTempDirectory("kmptoolkit-rec-r5").toRealPath().toFile()

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    @Test
    fun `free space of a real directory is the usable space and of anything else is unknown`() {
        val free: Long = fileSystem.freeSpaceBytes(base.path)
        // docs: from File.usableSpace of the directory; the volume moves a little between two reads
        assertTrue(abs(free - base.usableSpace) < 256L * 1024 * 1024, "free=$free usable=${base.usableSpace}")
        val file = File(base, "f.m4a").also { it.writeText("x") }
        assertEquals(-1L, fileSystem.freeSpaceBytes(file.path), "a file is not a directory: unknown, not a spurious 0")
        assertEquals(-1L, fileSystem.freeSpaceBytes(File(base, "missing").path), "a missing directory is unknown")
        assertEquals(-1L, fileSystem.freeSpaceBytes(File(File(base, "f.m4a"), "x").path))
        // A link to a directory is followed.
        val link = File(base, "link")
        assumeTrue("symbolic links are not available here", runCatching { Files.createSymbolicLink(link.toPath(), base.toPath()) }.isSuccess)
        assertTrue(fileSystem.freeSpaceBytes(link.path) > 0 || fileSystem.freeSpaceBytes(link.path) == -1L)
        assertTrue(abs(fileSystem.freeSpaceBytes(link.path) - base.usableSpace) < 256L * 1024 * 1024)
    }

    @Test
    fun `directory creation makes nested directories and refuses a path under a file`() {
        val nested = File(File(File(base, "a"), "b"), "c")
        assertTrue(fileSystem.ensureWritableDirectory(nested.path))
        assertTrue(nested.isDirectory)
        assertTrue(fileSystem.ensureWritableDirectory(nested.path), "idempotent")
        val file = File(base, "plain").also { it.writeText("x") }
        assertFalse(fileSystem.ensureWritableDirectory(file.path), "a file is not a directory")
        assertFalse(fileSystem.ensureWritableDirectory(File(file, "sub").path), "a path under a file cannot be created")
        assertEquals("x", file.readText(), "the file was not touched")
        // a trailing separator names the same directory
        // path-hygiene-ignore: PH1 the trailing separator is the subject of this check
        assertTrue(fileSystem.ensureWritableDirectory(nested.path + File.separator))
    }

    @Test
    fun `exists and regular file and delete treat links and directories as documented`() {
        val file = File(base, "rec.m4a").also { it.writeText("audio") }
        assertTrue(fileSystem.exists(file.path) && fileSystem.isRegularFile(file.path))
        val dangling = File(base, "dangling")
        assumeTrue("symbolic links are not available here", runCatching { Files.createSymbolicLink(dangling.toPath(), File(base, "nowhere").toPath()) }.isSuccess)
        assertFalse(fileSystem.exists(dangling.path), "a dangling link does not exist (files docs)")
        assertFalse(fileSystem.isRegularFile(dangling.path))
        val toFile = File(base, "tolink")
        Files.createSymbolicLink(toFile.toPath(), file.toPath())
        assertTrue(fileSystem.isRegularFile(toFile.path), "a link to a regular file is a regular file (links followed)")
        fileSystem.delete(toFile.path)
        assertTrue(file.exists(), "deleting a link never deletes its target")
        assertFalse(toFile.exists())
        val dir = File(base, "nonempty").also { it.mkdirs() }
        File(dir, "x").writeText("x")
        fileSystem.delete(dir.path) // best effort: a non-empty directory stays, nothing throws
        assertTrue(dir.exists())
        fileSystem.delete(File(base, "absent").path)
        fileSystem.delete(file.path)
        assertFalse(file.exists())
    }
}
