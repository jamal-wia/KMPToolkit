package io.github.jamal_wia.kmptoolkit.audio.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

/**
 * Round 9: the [RecordingFileSystem] contract on the real files module, case by case. "Never throws" for
 * `exists`, `isRegularFile` and `delete`, `false` rather than an exception for a directory that cannot
 * be made, `-1` for free space nobody can tell, links followed for what a path is, and a delete that is
 * never recursive and never follows a link.
 */
@RunWith(AndroidJUnit4::class)
class RecordingFileSystemContractR9Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileSystem: RecordingFileSystem = AndroidRecordingFileSystem(context)
    private lateinit var root: File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "r9fs-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun link(name: String, target: File): File? {
        val link = File(root, name)
        return try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            link
        } catch (unsupported: UnsupportedOperationException) {
            null
        } catch (denied: IOException) {
            null
        }
    }

    private fun isLink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    // --- a path the platform cannot even look at never throws out of the queries ---

    @Test
    fun `no query or delete throws for a path that cannot be a file name`() {
        val file = File(root, "plain.txt").also { it.writeText("x") }
        val hostile: List<String> = listOf(
            "",
            " ",
            "relative/path",
            "nul-\u0000-inside",
            File(root, "x".repeat(400)).path,
            File(file, "child").path,
            File(root, "bad\u0000name").path,
        )
        hostile.forEach { path: String ->
            assertFalse(fileSystem.exists(path), "exists('$path')")
            assertFalse(fileSystem.isRegularFile(path), "isRegularFile('$path')")
            assertEquals(-1L, fileSystem.freeSpaceBytes(path), "freeSpaceBytes('$path')")
            assertFalse(fileSystem.ensureWritableDirectory(path), "ensureWritableDirectory('$path')")
            fileSystem.delete(path)
        }
        assertEquals("x", file.readText(), "a hostile path damaged an innocent file")
    }

    // --- what a path is: links are followed ---

    @Test
    fun `a link to a directory is a writable directory with free space and a link to a file is a regular file`() {
        val directory = File(root, "real-dir").also { assertTrue(it.mkdirs()) }
        val file = File(root, "real-file").also { it.writeText("x") }
        val directoryLink: File = link("dir-link", directory) ?: return
        val fileLink: File = link("file-link", file) ?: return

        assertTrue(fileSystem.ensureWritableDirectory(directoryLink.path))
        assertTrue(fileSystem.freeSpaceBytes(directoryLink.path) > 0)
        assertTrue(fileSystem.exists(directoryLink.path))
        assertFalse(fileSystem.isRegularFile(directoryLink.path))
        assertTrue(fileSystem.isRegularFile(fileLink.path))
        assertFalse(fileSystem.ensureWritableDirectory(fileLink.path), "a file is not a directory to record into")
        assertEquals(-1L, fileSystem.freeSpaceBytes(fileLink.path))
    }

    @Test
    fun `a dangling link neither exists nor is a regular file nor a directory`() {
        val dangling: File = link("dangling", File(root, "no-such-target")) ?: return

        assertFalse(fileSystem.exists(dangling.path))
        assertFalse(fileSystem.isRegularFile(dangling.path))
        assertFalse(fileSystem.ensureWritableDirectory(dangling.path))
        assertEquals(-1L, fileSystem.freeSpaceBytes(dangling.path))
        assertTrue(isLink(dangling), "a query must not repair or remove the link")
    }

    @Test
    fun `a directory path with trailing separators names the directory`() {
        val directory = File(root, "trailing")
        // path-hygiene-ignore: PH1 a doubled trailing separator is the input this test is about
        val spelled: String = directory.path + File.separator + File.separator

        assertTrue(fileSystem.ensureWritableDirectory(spelled))

        assertTrue(directory.isDirectory)
        assertTrue(fileSystem.freeSpaceBytes(spelled) > 0)
    }

    @Test
    fun `a directory below a regular file is refused and nothing is created`() {
        val file = File(root, "blocker").also { it.writeText("x") }

        assertFalse(fileSystem.ensureWritableDirectory(File(file, "a/b").path))

        assertTrue(file.isFile)
        assertEquals("x", file.readText())
    }

    // --- delete is not recursive and never follows a link ---

    @Test
    fun `delete removes a file an empty directory and a link but never a link target or a directory with content`() {
        val file = File(root, "f.m4a").also { it.writeText("x") }
        val emptyDirectory = File(root, "empty").also { assertTrue(it.mkdirs()) }
        val fullDirectory = File(root, "full").also { assertTrue(it.mkdirs()) }
        File(fullDirectory, "keep.txt").writeText("keep")
        val target = File(root, "target.txt").also { it.writeText("target") }
        val toTarget: File? = link("to-target", target)
        val toFull: File? = link("to-full", fullDirectory)

        fileSystem.delete(file.path)
        fileSystem.delete(emptyDirectory.path)
        fileSystem.delete(fullDirectory.path)
        toTarget?.let { fileSystem.delete(it.path) }
        toFull?.let { fileSystem.delete(it.path) }

        assertFalse(file.exists())
        assertFalse(emptyDirectory.exists())
        assertTrue(File(fullDirectory, "keep.txt").exists(), "a directory with content is left alone")
        assertEquals("target", target.readText(), "delete followed a link")
        toTarget?.let { assertFalse(isLink(it), "the link survived") }
        toFull?.let { assertFalse(isLink(it), "the link to a directory survived") }
    }

    @Test
    fun `deleting what is not there twice is not an error`() {
        val missing: String = File(root, "never").path

        fileSystem.delete(missing)
        fileSystem.delete(missing)

        assertFalse(fileSystem.exists(missing))
    }
}
