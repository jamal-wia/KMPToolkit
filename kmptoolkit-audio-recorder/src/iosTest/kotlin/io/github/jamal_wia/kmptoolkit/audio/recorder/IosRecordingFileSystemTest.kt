package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile

/**
 * [IosRecordingFileSystem] against a real `NSFileManager` in the simulator — the iOS counterpart of
 * [AndroidRecordingFileSystemTest], and the only part of the iOS side that can be exercised without
 * a microphone. `AvAudioRecorderEngine` needs real audio hardware and an `AVAudioSession`, so it is
 * left to a device.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRecordingFileSystemTest {

    private val fileSystem = IosRecordingFileSystem()
    private val fileManager: NSFileManager get() = NSFileManager.defaultManager

    @Test
    fun `the app private directory is an absolute existing path`() {
        val path: String = fileSystem.appPrivateDirectory()

        assertTrue(path.startsWith("/"), "expected an absolute path but was '$path'")
    }

    @Test
    fun `the application identifier is never blank`() {
        // A test host has no CFBundleIdentifier, so this exercises the documented fallback rather
        // than a real bundle id — the point is that a recording always has somewhere to go.
        assertTrue(fileSystem.applicationIdentifier().isNotBlank())
    }

    @Test
    fun `resolve joins a directory and a name`() {
        assertEquals("/a/b/c.m4a", fileSystem.resolve("/a/b", "c.m4a"))
    }

    @Test
    fun `parentOf reports the directory holding a file`() {
        assertEquals("/a/b", fileSystem.parentOf("/a/b/c.m4a"))
    }

    @Test
    fun `ensureWritableDirectory creates missing intermediate directories`() {
        val target: String = uniquePath("deeply/nested/recordings")
        assertFalse(fileManager.fileExistsAtPath(target))

        assertTrue(fileSystem.ensureWritableDirectory(target))

        assertTrue(fileManager.fileExistsAtPath(target))
    }

    @Test
    fun `ensureWritableDirectory accepts a directory that already exists`() {
        val target: String = uniquePath("existing")
        fileSystem.ensureWritableDirectory(target)

        assertTrue(fileSystem.ensureWritableDirectory(target))
    }

    @Test
    fun `free space is reported for a directory that exists`() {
        assertTrue(fileSystem.freeSpaceBytes(fileSystem.appPrivateDirectory()) > 0)
    }

    @Test
    fun `free space is unknown for a path that does not exist`() {
        assertEquals(-1L, fileSystem.freeSpaceBytes(uniquePath("never-created")))
    }

    @Test
    fun `delete removes an existing file`() {
        val directory: String = uniquePath("deletable")
        fileSystem.ensureWritableDirectory(directory)
        val file: String = fileSystem.resolve(directory, "doomed.m4a")
        writeText(file)
        assertTrue(fileManager.fileExistsAtPath(file))

        fileSystem.delete(file)

        assertFalse(fileManager.fileExistsAtPath(file))
    }

    @Test
    fun `deleting a file that is not there is not an error`() {
        fileSystem.delete(uniquePath("never-existed.m4a"))
    }

    @Test
    fun `parentOf reports null for a bare file name`() {
        assertNull(fileSystem.parentOf("c.m4a"))
    }

    // A UUID, not a counter: the simulator's Documents directory survives between runs, so a
    // counter would collide with the previous run's leftovers and the "did not exist before"
    // assertions would fail on the second execution.
    private fun uniquePath(name: String): String = fileSystem.resolve(
        fileSystem.appPrivateDirectory(),
        "kmptoolkit-test-${NSUUID().UUIDString}/$name",
    )

    private fun writeText(path: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        (("audio" as NSString)).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }
}
