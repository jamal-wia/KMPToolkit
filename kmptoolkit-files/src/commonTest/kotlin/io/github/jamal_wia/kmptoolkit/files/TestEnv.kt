@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps

/** What the real-file tests need from the host; one actual per test source set. */
internal expect object TestEnv {
    /** A fresh empty directory (absolute native path). */
    fun newRoot(): String

    /** Removes [path] and everything under it without using the code under test. */
    fun deleteTree(path: String)

    /** The platform's real [FileSystemOps]. */
    fun realOps(): FileSystemOps

    /** Creates a symbolic link; false when the host cannot (Windows without the privilege). */
    fun createSymbolicLink(link: String, target: String): Boolean

    /** Sets the modification time; false when the host refused. */
    fun setLastModified(path: String, millis: Long): Boolean

    /** Makes [directory] refuse new entries; null when that is impossible here (running as root). */
    fun makeUnwritable(directory: String): AutoCloseable?

    /** The number of open descriptors of this process, or null when the host cannot say. */
    fun openDescriptorCount(): Long?

    /** Whether the host is Linux, whose common file systems (ext4, XFS, Btrfs, tmpfs, F2FS) limit a name to 255 UTF-8 bytes. */
    val isLinuxHost: Boolean

    /** Skips the running test (reported as skipped where the platform can; fails on iOS, which cannot). */
    fun skip(reason: String): Nothing
}
