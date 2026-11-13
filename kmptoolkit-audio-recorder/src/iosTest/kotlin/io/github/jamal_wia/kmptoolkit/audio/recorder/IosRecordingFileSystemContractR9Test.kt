package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile

/**
 * Round 9: the iOS file system against the same contract the Android one is held to, on the cases the
 * docs name: a delete that is not recursive and never follows a link ("on iOS as on Android"), links
 * followed for what a path is, and queries that answer instead of throwing.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRecordingFileSystemContractR9Test {

    private val fileSystem = IosRecordingFileSystem()
    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    private fun scratch(): String {
        val directory: String = fileSystem.resolve(fileSystem.appPrivateDirectory(), "kmptoolkit-r9-${NSUUID().UUIDString}")
        assertTrue(fileSystem.ensureWritableDirectory(directory))
        return directory
    }

    private fun writeText(path: String, text: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile(path = path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun link(path: String, target: String): Boolean =
        fileManager.createSymbolicLinkAtPath(path, withDestinationPath = target, error = null)

    private fun isLink(path: String): Boolean =
        fileManager.destinationOfSymbolicLinkAtPath(path, error = null) != null

    @Test
    fun `delete removes a file an empty directory and a link but never a link target or a directory with content`() {
        val root: String = scratch()
        val file: String = fileSystem.resolve(root, "f.m4a").also { writeText(it, "x") }
        val emptyDirectory: String = fileSystem.resolve(root, "empty").also { assertTrue(fileSystem.ensureWritableDirectory(it)) }
        val fullDirectory: String = fileSystem.resolve(root, "full").also { assertTrue(fileSystem.ensureWritableDirectory(it)) }
        val keep: String = fileSystem.resolve(fullDirectory, "keep.txt").also { writeText(it, "keep") }
        val target: String = fileSystem.resolve(root, "target.txt").also { writeText(it, "target") }
        val toTarget: String = fileSystem.resolve(root, "to-target")
        assertTrue(link(toTarget, target))
        val toFull: String = fileSystem.resolve(root, "to-full")
        assertTrue(link(toFull, fullDirectory))

        listOf(file, emptyDirectory, fullDirectory, toTarget, toFull).forEach { fileSystem.delete(it) }

        assertFalse(fileManager.fileExistsAtPath(file))
        assertFalse(fileManager.fileExistsAtPath(emptyDirectory))
        assertTrue(fileManager.fileExistsAtPath(keep), "a directory with content is left alone")
        assertTrue(fileManager.fileExistsAtPath(target), "delete followed a link")
        assertFalse(isLink(toTarget), "the link survived")
        assertFalse(isLink(toFull), "the link to a directory survived")
        fileManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun `links are followed for what a path is and a dangling link is nothing`() {
        val root: String = scratch()
        val directory: String = fileSystem.resolve(root, "real-dir").also { assertTrue(fileSystem.ensureWritableDirectory(it)) }
        val file: String = fileSystem.resolve(root, "real-file").also { writeText(it, "x") }
        val directoryLink: String = fileSystem.resolve(root, "dir-link")
        val fileLink: String = fileSystem.resolve(root, "file-link")
        val dangling: String = fileSystem.resolve(root, "dangling")
        assertTrue(link(directoryLink, directory))
        assertTrue(link(fileLink, file))
        assertTrue(link(dangling, fileSystem.resolve(root, "no-such-target")))

        assertTrue(fileSystem.ensureWritableDirectory(directoryLink))
        assertTrue(fileSystem.freeSpaceBytes(directoryLink) > 0)
        assertFalse(fileSystem.isRegularFile(directoryLink))
        assertTrue(fileSystem.isRegularFile(fileLink))
        assertFalse(fileSystem.ensureWritableDirectory(fileLink), "a file is not a directory to record into")
        assertEquals(-1L, fileSystem.freeSpaceBytes(fileLink))
        assertFalse(fileSystem.exists(dangling))
        assertFalse(fileSystem.isRegularFile(dangling))
        assertFalse(fileSystem.ensureWritableDirectory(dangling))
        assertEquals(-1L, fileSystem.freeSpaceBytes(dangling))
        assertTrue(isLink(dangling), "a query must not repair or remove the link")
        fileManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun `no query or delete throws for a path that cannot be a file name`() {
        val root: String = scratch()
        val file: String = fileSystem.resolve(root, "plain.txt").also { writeText(it, "x") }
        val hostile: List<String> = listOf(
            "",
            " ",
            "relative/path",
            fileSystem.resolve(root, "x".repeat(400)),
            fileSystem.resolve(file, "child"),
        )
        hostile.forEach { path: String ->
            assertFalse(fileSystem.exists(path), "exists('$path')")
            assertFalse(fileSystem.isRegularFile(path), "isRegularFile('$path')")
            assertEquals(-1L, fileSystem.freeSpaceBytes(path), "freeSpaceBytes('$path')")
            assertFalse(fileSystem.ensureWritableDirectory(path), "ensureWritableDirectory('$path')")
            fileSystem.delete(path)
        }
        assertTrue(fileManager.fileExistsAtPath(file), "a hostile path damaged an innocent file")
        fileManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun `a directory below a regular file is refused and a trailing separator names the directory`() {
        val root: String = scratch()
        val file: String = fileSystem.resolve(root, "blocker").also { writeText(it, "x") }

        assertFalse(fileSystem.ensureWritableDirectory(fileSystem.resolve(file, "a/b")))
        assertTrue(fileManager.fileExistsAtPath(file))
        val spelled: String = fileSystem.resolve(root, "trailing") + "//"
        assertTrue(fileSystem.ensureWritableDirectory(spelled))
        assertTrue(fileSystem.freeSpaceBytes(spelled) > 0)
        fileManager.removeItemAtPath(root, error = null)
    }
}
