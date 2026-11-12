@file:OptIn(ToolkitInternalApi::class, ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.PosixFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.posix.W_OK
import platform.posix.access
import platform.posix.chmod
import platform.posix.symlink

internal actual object TestEnv {
    actual fun newRoot(): String {
        val path: String = NSTemporaryDirectory().trimEnd('/') + "/kmptoolkit-files-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)) { "no test root" }
        return path
    }

    actual fun deleteTree(path: String) {
        chmod(path, 0x1FD.convert())
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    actual fun realOps(): FileSystemOps = PosixFileSystemOps()

    actual fun createSymbolicLink(link: String, target: String): Boolean = symlink(target, link) == 0

    actual fun setLastModified(path: String, millis: Long): Boolean =
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)),
            path,
            null,
        )

    actual fun makeUnwritable(directory: String): AutoCloseable? {
        if (chmod(directory, 0x16D.convert()) != 0) return null
        if (access(directory, W_OK) == 0) {
            chmod(directory, 0x1FD.convert())
            return null
        }
        return AutoCloseable { chmod(directory, 0x1FD.convert()) }
    }

    actual fun openDescriptorCount(): Long? = null

    actual fun skip(reason: String): Nothing = throw AssertionError("cannot skip on iOS: $reason")
}
