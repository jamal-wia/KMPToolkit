package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileProtectionNone
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.posix.EACCES
import platform.posix.EDQUOT
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.EISDIR
import platform.posix.ENAMETOOLONG
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.ENOTDIR
import platform.posix.ENOTEMPTY
import platform.posix.ENOTSUP
import platform.posix.EOPNOTSUPP
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.EXDEV
import platform.posix.F_FULLFSYNC
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.closedir
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fsync
import platform.posix.lstat
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink
import platform.posix.write

private const val MODE_TYPE_MASK: Int = 0xF000
private const val MODE_REGULAR: Int = 0x8000
private const val MODE_DIRECTORY: Int = 0x4000
private const val MODE_LINK: Int = 0xA000
private const val DIRECTORY_MODE: Int = 0b111_111_101 // 0775
private const val FILE_MODE: Int = 0b110_110_110 // 0666
private const val NANOS_PER_MILLI: Long = 1_000_000L
private const val MILLIS_PER_SECOND: Long = 1000L

/** [FileSystemOps] over POSIX calls, with Foundation only for data protection and free space. */
@OptIn(ExperimentalForeignApi::class, ToolkitInternalApi::class)
internal class PosixFileSystemOps : FileSystemOps {

    override val windowsPaths: Boolean = false

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? = memScoped {
        val info: stat = alloc<stat>()
        val result: Int = if (followLinks) stat(path, info.ptr) else lstat(path, info.ptr)
        if (result != 0) {
            val code: Int = errno
            if (code == ENOENT || code == ENOTDIR) return@memScoped null
            throw FileOpException(posixError(code, path))
        }
        val type: Int = info.st_mode.toInt() and MODE_TYPE_MASK
        val kind: FileKind = when (type) {
            MODE_REGULAR -> FileKind.RegularFile
            MODE_DIRECTORY -> FileKind.Directory
            MODE_LINK -> FileKind.SymbolicLink
            else -> FileKind.Other
        }
        FileMetadata(
            kind = kind,
            sizeBytes = if (kind == FileKind.RegularFile) info.st_size else 0L,
            lastModifiedMillis = info.st_mtimespec.tv_sec * MILLIS_PER_SECOND + info.st_mtimespec.tv_nsec / NANOS_PER_MILLI,
        )
    }

    override fun list(directory: String): List<String>? {
        val handle = opendir(directory)
        if (handle == null) {
            val code: Int = errno
            if (code == ENOENT) return null
            throw FileOpException(posixError(code, directory))
        }
        val names: MutableList<String> = mutableListOf()
        try {
            while (true) {
                val entry = readdir(handle) ?: break
                val name: String = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") names += name
            }
        } finally {
            closedir(handle)
        }
        return names
    }

    override fun createDirectory(path: String) {
        if (mkdir(path, DIRECTORY_MODE.convert()) != 0) throw FileOpException(posixError(errno, path))
    }

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        val descriptor: Int = open(path, O_WRONLY or O_CREAT or O_EXCL or O_CLOEXEC, FILE_MODE)
        if (descriptor < 0) throw FileOpException(posixError(errno, path))
        val file: PosixWritableFile = PosixWritableFile(descriptor, path)
        if (protection != FileProtection.Default) {
            val applied: Boolean = NSFileManager.defaultManager.setAttributes(
                attributes = mapOf(NSFileProtectionKey to protectionOf(protection)),
                ofItemAtPath = path,
                error = null,
            )
            if (!applied) {
                file.close()
                unlink(path)
                throw FileOpException(FileError.Io(path))
            }
        }
        return file
    }

    private fun protectionOf(protection: FileProtection): String? = when (protection) {
        FileProtection.Default -> null
        FileProtection.None -> NSFileProtectionNone
        FileProtection.Complete -> NSFileProtectionComplete
        FileProtection.CompleteUnlessOpen -> NSFileProtectionCompleteUnlessOpen
        FileProtection.CompleteUntilFirstUserAuthentication -> NSFileProtectionCompleteUntilFirstUserAuthentication
    }

    override fun openForRead(path: String): ReadableFile {
        val descriptor: Int = open(path, O_RDONLY or O_CLOEXEC)
        if (descriptor < 0) throw FileOpException(posixError(errno, path))
        return PosixReadableFile(descriptor, path)
    }

    override fun rename(from: String, to: String) {
        if (platform.posix.rename(from, to) != 0) throw FileOpException(posixError(errno, from))
    }

    override fun link(existing: String, newPath: String) {
        if (platform.posix.link(existing, newPath) != 0) throw FileOpException(posixError(errno, newPath, forLink = true))
    }

    override fun remove(path: String) {
        val found: FileMetadata = metadata(path, false) ?: throw FileOpException(FileError.NotFound(path))
        val result: Int = if (found.kind == FileKind.Directory) rmdir(path) else unlink(path)
        if (result != 0) throw FileOpException(posixError(errno, path))
    }

    override fun syncDirectory(directory: String, full: Boolean) {
        val descriptor: Int = open(directory, O_RDONLY or O_CLOEXEC)
        if (descriptor < 0) throw FileOpException(posixError(errno, directory))
        try {
            syncDescriptor(descriptor, full, directory)
        } finally {
            close(descriptor)
        }
    }

    override fun usableSpace(path: String): Long? {
        val attributes: Map<Any?, *> =
            NSFileManager.defaultManager.attributesOfFileSystemForPath(path, error = null) ?: return null
        return (attributes[NSFileSystemFreeSize] as? NSNumber)?.longLongValue
    }
}

/** fsync, or F_FULLFSYNC when [full] (falling back to fsync where the file system refuses it). */
@OptIn(ExperimentalForeignApi::class, ToolkitInternalApi::class)
private fun syncDescriptor(descriptor: Int, full: Boolean, path: String) {
    while (true) {
        val result: Int
        if (full) {
            val flushed: Int = fcntl(descriptor, F_FULLFSYNC)
            result = if (flushed == -1 && (errno == ENOTSUP || errno == EINVAL)) fsync(descriptor) else flushed
        } else {
            result = fsync(descriptor)
        }
        if (result == 0) return
        val code: Int = errno
        if (code == EINTR) continue
        throw FileOpException(posixError(code, path))
    }
}

@OptIn(ExperimentalForeignApi::class, ToolkitInternalApi::class)
private class PosixWritableFile(private val descriptor: Int, private val path: String) : WritableFile {
    private var closed: Boolean = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var done: Int = 0
        while (done < length) {
            val count: Long = bytes.usePinned { pinned ->
                write(descriptor, pinned.addressOf(offset + done), (length - done).convert())
            }
            if (count < 0) {
                val code: Int = errno
                if (code == EINTR) continue
                throw FileOpException(posixError(code, path))
            }
            done += count.toInt()
        }
    }

    override fun sync(full: Boolean) {
        syncDescriptor(descriptor, full, path)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor) != 0) throw FileOpException(posixError(errno, path))
    }
}

@OptIn(ExperimentalForeignApi::class, ToolkitInternalApi::class)
private class PosixReadableFile(private val descriptor: Int, private val path: String) : ReadableFile {
    private var closed: Boolean = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val count: Long = buffer.usePinned { pinned ->
                read(descriptor, pinned.addressOf(offset), length.convert())
            }
            if (count < 0) {
                val code: Int = errno
                if (code == EINTR) continue
                throw FileOpException(posixError(code, path))
            }
            return if (count == 0L) -1 else count.toInt()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        close(descriptor)
    }
}

private fun posixError(code: Int, path: String, forLink: Boolean = false): FileError = when (code) {
    ENOENT -> FileError.NotFound(path)
    EEXIST -> FileError.AlreadyExists(path)
    ENOTDIR -> FileError.NotADirectory(path)
    EISDIR -> FileError.IsADirectory(path)
    ENOTEMPTY -> FileError.DirectoryNotEmpty(path)
    EPERM, ENOTSUP, EOPNOTSUPP -> if (forLink) FileError.Unsupported(path) else FileError.AccessDenied(path)
    EACCES, EROFS -> FileError.AccessDenied(path)
    ENOSPC, EDQUOT -> FileError.NoSpace(path)
    ENAMETOOLONG -> FileError.NameTooLong(path)
    EXDEV -> FileError.CrossDevice(path)
    else -> FileError.Io(path, code, null)
}
