package io.github.jamal_wia.kmptoolkit.files.internal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import java.io.File
import java.io.FileDescriptor

private const val DIRECTORY_MODE: Int = 0b111_111_101 // 0775
private const val FILE_MODE: Int = 0b110_110_110 // 0666
private const val MILLIS_PER_SECOND: Long = 1000L

/**
 * [FileSystemOps] for Android API 24-25, where java.nio.file does not exist: `android.system.Os` for everything
 * that needs a descriptor or an exact errno, `java.io.File` for the free-space query. A device check is an owner
 * item: `Os` is a stub under Robolectric (the spike of the T1 report), so no host test can run this class.
 */
@OptIn(ToolkitInternalApi::class)
internal class LegacyAndroidFileSystemOps : FileSystemOps {

    override val windowsPaths: Boolean = false

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
        val stat: StructStat = try {
            if (followLinks) Os.stat(path) else Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) return null
            throw FileOpException(errnoError(e.errno, path))
        }
        val kind: FileKind = when {
            OsConstants.S_ISREG(stat.st_mode) -> FileKind.RegularFile
            OsConstants.S_ISDIR(stat.st_mode) -> FileKind.Directory
            OsConstants.S_ISLNK(stat.st_mode) -> FileKind.SymbolicLink
            else -> FileKind.Other
        }
        return FileMetadata(
            kind = kind,
            sizeBytes = if (kind == FileKind.RegularFile) stat.st_size else 0L,
            lastModifiedMillis = stat.st_mtime * MILLIS_PER_SECOND,
        )
    }

    override fun list(directory: String): List<String>? {
        val found: FileMetadata = metadata(directory, true) ?: return null
        if (found.kind != FileKind.Directory) throw FileOpException(FileError.NotADirectory(directory))
        val names: Array<String>? = File(directory).list()
        if (names != null) return names.toList()
        // A directory that vanished since the check above is absent, not refused.
        if (metadata(directory, true) == null) return null
        throw FileOpException(FileError.AccessDenied(directory))
    }

    override fun createDirectory(path: String) {
        os(path) { Os.mkdir(path, DIRECTORY_MODE) }
    }

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        val descriptor: FileDescriptor =
            os(path) { Os.open(path, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, FILE_MODE) }
        return OsWritableFile(descriptor, path)
    }

    override fun openForRead(path: String): ReadableFile {
        val descriptor: FileDescriptor = os(path) { Os.open(path, OsConstants.O_RDONLY, 0) }
        return OsReadableFile(descriptor, path)
    }

    override fun rename(from: String, to: String) {
        os(from) { Os.rename(from, to) }
    }

    override fun link(existing: String, newPath: String) {
        os(newPath, forLink = true) { Os.link(existing, newPath) }
    }

    override fun remove(path: String) {
        // remove(3) unlinks a file or a link and removes an empty directory.
        os(path) { Os.remove(path) }
    }

    override fun syncDirectory(directory: String, full: Boolean) {
        val descriptor: FileDescriptor = os(directory) { Os.open(directory, OsConstants.O_RDONLY, 0) }
        try {
            os(directory) { Os.fsync(descriptor) }
        } finally {
            closeQuietly(descriptor)
        }
    }

    override fun usableSpace(path: String): Long? = try {
        File(path).usableSpace
    } catch (_: Exception) {
        null
    }
}

private fun closeQuietly(descriptor: FileDescriptor) {
    try {
        Os.close(descriptor)
    } catch (_: Exception) {
        // Nothing left to release.
    }
}

private fun errnoError(errno: Int, path: String, forLink: Boolean = false): FileError = when (errno) {
    OsConstants.ENOENT -> FileError.NotFound(path)
    OsConstants.EEXIST -> FileError.AlreadyExists(path)
    OsConstants.ENOTDIR -> FileError.NotADirectory(path)
    OsConstants.EISDIR -> FileError.IsADirectory(path)
    OsConstants.ENOTEMPTY -> FileError.DirectoryNotEmpty(path)
    OsConstants.EPERM, OsConstants.ENOTSUP, OsConstants.EOPNOTSUPP ->
        if (forLink) FileError.Unsupported(path) else FileError.AccessDenied(path)
    OsConstants.EACCES, OsConstants.EROFS -> FileError.AccessDenied(path)
    OsConstants.ENOSPC, OsConstants.EDQUOT -> FileError.NoSpace(path)
    OsConstants.ENAMETOOLONG -> FileError.NameTooLong(path)
    OsConstants.EXDEV -> FileError.CrossDevice(path)
    else -> FileError.Io(path, errno, null)
}

/** Runs [block], retrying an interrupted call and turning an [ErrnoException] into a [FileOpException]. */
@OptIn(ToolkitInternalApi::class)
private inline fun <T> os(path: String, forLink: Boolean = false, block: () -> T): T {
    while (true) {
        try {
            return block()
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EINTR) continue
            throw FileOpException(errnoError(e.errno, path, forLink))
        }
    }
}

@OptIn(ToolkitInternalApi::class)
private class OsWritableFile(private val descriptor: FileDescriptor, private val path: String) : WritableFile {
    private var closed: Boolean = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var done: Int = 0
        while (done < length) {
            done += os(path) { Os.write(descriptor, bytes, offset + done, length - done) }
        }
    }

    override fun sync(full: Boolean) {
        os(path) { Os.fsync(descriptor) }
    }

    override fun close() {
        if (closed) return
        closed = true
        closeOnce(descriptor, path)
    }
}

/** close(2) is never retried: libcore clears the descriptor first, so a retry would close descriptor -1. */
@OptIn(ToolkitInternalApi::class)
private fun closeOnce(descriptor: FileDescriptor, path: String) {
    try {
        Os.close(descriptor)
    } catch (e: ErrnoException) {
        throw FileOpException(errnoError(e.errno, path))
    }
}

@OptIn(ToolkitInternalApi::class)
private class OsReadableFile(private val descriptor: FileDescriptor, private val path: String) : ReadableFile {
    private var closed: Boolean = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count: Int = os(path) { Os.read(descriptor, buffer, offset, length) }
        return if (count <= 0 && length > 0) -1 else count
    }

    override fun close() {
        if (closed) return
        closed = true
        closeOnce(descriptor, path)
    }
}
