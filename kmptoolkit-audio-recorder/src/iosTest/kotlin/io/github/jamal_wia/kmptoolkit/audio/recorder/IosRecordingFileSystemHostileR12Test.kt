package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

/**
 * Round 12, Q1 on the platform where an Objective-C exception cannot be caught: hostile strings through the real
 * iOS file system. Every query answers (false, -1, null) and never raises; a path with a NUL, ten thousand
 * characters, only separators or a directory in place of a file is never reported as a writable directory, an
 * existing file or a regular file, and never deleted when it is a directory that holds something.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRecordingFileSystemHostileR12Test {

    private val fileSystem = IosRecordingFileSystem()
    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    private fun scratch(): String {
        val directory: String = fileSystem.resolve(fileSystem.appPrivateDirectory(), "kmptoolkit-r12-${NSUUID().UUIDString}")
        assertTrue(fileSystem.ensureWritableDirectory(directory))
        return directory
    }

    @Test
    fun `queries about hostile paths answer and never raise`() {
        val root: String = scratch()
        try {
            val hostile: List<String> = listOf(
                fileSystem.resolve(root, "a\u0000b.m4a"),
                fileSystem.resolve(root, "\u0000"),
                fileSystem.resolve(root, "n".repeat(10_000) + ".m4a"),
                fileSystem.resolve(root, "ẹ́.m4a"),
                fileSystem.resolve(root, "a\nb.m4a"),
                "/" + "d/".repeat(3_000) + "x.m4a",
                "//", "/", "\u0000", "/\u0000",
            )
            for (path: String in hostile) {
                val label: String = "path of length ${path.length} starting ${path.take(12).replace("\u0000", "<NUL>")}"
                assertFalse(fileSystem.exists(path) && path.contains('\u0000'), "$label: exists claimed a NUL path")
                assertFalse(fileSystem.isRegularFile(path), "$label: regular file")
                // The roots are real directories and answer with their real free space; every other string here
                // names nothing, so its free space is unknown.
                if (path != "/" && path != "//") {
                    assertEquals(-1L, fileSystem.freeSpaceBytes(path), "$label: free space of a non-directory is unknown")
                }
                if (path.contains('\u0000') || path.length > 4_000) {
                    assertFalse(fileSystem.ensureWritableDirectory(path), "$label: writable directory")
                }
                fileSystem.delete(path)
                fileSystem.parentOf(path)
                fileSystem.isAbsolute(path)
            }
            assertTrue(fileManager.fileExistsAtPath(root), "the scratch root was touched")
        } finally {
            fileSystem.delete(root)
        }
    }

    @Test
    fun `a directory with content in place of the output file is neither a regular file nor deleted`() {
        val root: String = scratch()
        val victim: String = fileSystem.resolve(root, "victim.m4a")
        assertTrue(fileSystem.ensureWritableDirectory(victim))
        val keep: String = fileSystem.resolve(victim, "keep.txt")
        assertTrue(fileManager.createFileAtPath(keep, contents = null, attributes = null))
        try {
            assertFalse(fileSystem.isRegularFile(victim))
            assertTrue(fileSystem.exists(victim))
            fileSystem.delete(victim)
            assertTrue(fileManager.fileExistsAtPath(keep), "delete removed the content of a directory")
        } finally {
            fileManager.removeItemAtPath(root, error = null)
        }
    }
}
