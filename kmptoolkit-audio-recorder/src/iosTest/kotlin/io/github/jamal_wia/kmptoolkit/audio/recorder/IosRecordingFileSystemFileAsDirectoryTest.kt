package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile

/**
 * `RecordingFileSystem.ensureWritableDirectory` reports "whether the result is a directory that can be
 * written to", so a path that is a regular writable file is not one.
 */
@OptIn(ExperimentalForeignApi::class)
class IosRecordingFileSystemFileAsDirectoryTest {

    private val fileSystem = IosRecordingFileSystem()

    @Test
    fun `ensureWritableDirectory refuses a path that is a regular file`() {
        val directory: String = fileSystem.resolve(fileSystem.appPrivateDirectory(), "c4-13-" + NSUUID().UUIDString)
        fileSystem.ensureWritableDirectory(directory)
        val file: String = fileSystem.resolve(directory, "plain.txt")
        @Suppress("CAST_NEVER_SUCCEEDS")
        (("x" as NSString).writeToFile(file, atomically = true, encoding = NSUTF8StringEncoding, error = null))

        assertFalse(fileSystem.ensureWritableDirectory(file), "a regular file is not a directory that can be written to")

        NSFileManager.defaultManager.removeItemAtPath(directory, error = null)
    }
}
