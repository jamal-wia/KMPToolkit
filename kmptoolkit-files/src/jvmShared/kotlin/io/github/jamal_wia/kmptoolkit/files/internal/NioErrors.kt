package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/** Maps what java.nio throws to a typed [FileError]; [path] is the path the operation was working on. */
internal fun mapNioError(e: Throwable, path: String, forLink: Boolean = false): FileError {
    val reason: String = ((e as? FileSystemException)?.reason ?: e.message ?: "").lowercase()
    return when {
        e is NoSuchFileException -> FileError.NotFound(path)
        e is FileAlreadyExistsException -> FileError.AlreadyExists(path)
        e is NotDirectoryException -> FileError.NotADirectory(path)
        e is DirectoryNotEmptyException -> FileError.DirectoryNotEmpty(path)
        e is AccessDeniedException -> FileError.AccessDenied(path)
        e is AtomicMoveNotSupportedException -> FileError.CrossDevice(path)
        e is InvalidPathException -> FileError.InvalidPath(path)
        e is UnsupportedOperationException -> FileError.Unsupported(path)
        e is SecurityException -> FileError.AccessDenied(path)
        e is FileSystemException || e is IOException -> mapByText(e, reason, path, forLink)
        else -> FileError.Io(path, null, e)
    }
}

private fun mapByText(e: Throwable, reason: String, path: String, forLink: Boolean): FileError = when {
    "no space left" in reason || "disk quota exceeded" in reason || "not enough space on the disk" in reason ->
        FileError.NoSpace(path)
    "file name too long" in reason || "filename or extension is too long" in reason -> FileError.NameTooLong(path)
    "invalid cross-device link" in reason || "different disk drive" in reason -> FileError.CrossDevice(path)
    "directory not empty" in reason -> FileError.DirectoryNotEmpty(path)
    "is a directory" in reason -> FileError.IsADirectory(path)
    "not a directory" in reason -> FileError.NotADirectory(path)
    "no such file" in reason -> FileError.NotFound(path)
    "read-only file system" in reason || "permission denied" in reason -> FileError.AccessDenied(path)
    forLink && (
        "operation not supported" in reason || "operation not permitted" in reason ||
            "incorrect function" in reason || "not supported" in reason
        ) -> FileError.Unsupported(path)
    else -> FileError.Io(path, null, e)
}

/** Runs [block], turning anything it throws into a [FileOpException] (a [FileOpException] passes through). */
@OptIn(ToolkitInternalApi::class)
internal inline fun <T> nio(path: String, forLink: Boolean = false, block: () -> T): T =
    try {
        block()
    } catch (e: FileOpException) {
        throw e
    } catch (e: Exception) {
        throw FileOpException(mapNioError(e, path, forLink))
    }
