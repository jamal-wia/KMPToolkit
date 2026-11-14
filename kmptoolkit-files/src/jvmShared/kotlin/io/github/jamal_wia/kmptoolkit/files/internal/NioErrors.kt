package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.hostUsesWindowsPaths
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException

/** Errors identified by their exception type alone, which no locale can change; `null` for an untyped failure. */
internal fun typedNioError(e: Throwable, path: String): FileError? = when (e) {
    is NoSuchFileException -> FileError.NotFound(path)
    is FileAlreadyExistsException -> FileError.AlreadyExists(path)
    is NotDirectoryException -> FileError.NotADirectory(path)
    is DirectoryNotEmptyException -> FileError.DirectoryNotEmpty(path)
    is AccessDeniedException -> FileError.AccessDenied(path)
    is AtomicMoveNotSupportedException -> FileError.CrossDevice(path)
    is InvalidPathException -> FileError.InvalidPath(path)
    is UnsupportedOperationException -> FileError.Unsupported(path)
    is SecurityException -> FileError.AccessDenied(path)
    else -> null
}

/** Whether this host's file system counts a name in Unicode code points, not UTF-8 bytes: APFS on a macOS host. */
internal val hostCountsCodePoints: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

/**
 * Maps what java.nio throws to a typed [FileError]; [path] is the path the operation was working on.
 *
 * [namesEntry] is true for an operation that names or creates a directory entry (open, create, rename, link, remove,
 * list, stat) and false for a read, write, sync or close of a file that is already open: only the former can fail
 * because of a name, so only the former gets the length classification.
 */
internal fun mapNioError(
    e: Throwable,
    path: String,
    forLink: Boolean = false,
    windows: Boolean = hostUsesWindowsPaths,
    also: List<String> = emptyList(),
    namesEntry: Boolean = true,
    codePoints: Boolean = hostCountsCodePoints,
): FileError {
    typedNioError(e, path)?.let { return it }
    if (namesEntry && (e is FileSystemException || e is IOException)) {
        // A classification of a failure that already happened, by the length of a segment of a path the operation
        // names: no message text, so no language can change it. Never a pre-check.
        for (named: String in listOf(path) + also) {
            if (segmentTooLong(named, windows, codePoints)) return FileError.NameTooLong(named)
        }
    }
    val reason: String = ((e as? FileSystemException)?.reason ?: e.message ?: "").lowercase()
    return if (e is FileSystemException || e is IOException) mapByText(e, reason, path, forLink) else FileError.Io(path, null, e)
}

private const val MAX_SEGMENT_UNITS: Int = 255

/**
 * Whether a segment of [path] is over the file system's limit: 255 UTF-16 units on Windows, 255 code points when
 * [codePoints] (APFS on a macOS host), 255 UTF-8 bytes elsewhere. A backslash separates segments only on Windows.
 */
internal fun segmentTooLong(path: String, windows: Boolean, codePoints: Boolean = false): Boolean {
    val segments: List<String> = if (windows) path.split('/', '\\') else path.split('/')
    for (segment: String in segments) {
        if (segment.isEmpty()) continue
        if (windows) {
            if (segment.length > MAX_SEGMENT_UNITS) return true
        } else if (codePoints) {
            if (segment.codePointCount(0, segment.length) > MAX_SEGMENT_UNITS) return true
        } else {
            if (NameRules.utf8Length(segment) > MAX_SEGMENT_UNITS) return true
        }
    }
    return false
}

private val FAT_FAMILY: Set<String> = setOf("fat", "fat12", "fat16", "fat32", "vfat", "msdos", "exfat")

/**
 * Classifies a failed `Files.createLink` without trusting message text, which the OS localizes: the exception type
 * first, then the file stores (different stores: [FileError.CrossDevice]; a FAT family store: [FileError.Unsupported]),
 * and the message text only as the last resort. A store lookup that fails is ignored.
 */
internal fun classifyLinkFailure(
    e: Throwable,
    path: String,
    sourceStore: () -> FileStore?,
    targetStore: () -> FileStore?,
    windows: Boolean = hostUsesWindowsPaths,
    also: List<String> = emptyList(),
    codePoints: Boolean = hostCountsCodePoints,
): FileError {
    typedNioError(e, path)?.let { return it }
    if (e is FileSystemException) {
        val source: FileStore? = try { sourceStore() } catch (_: Exception) { null }
        val target: FileStore? = try { targetStore() } catch (_: Exception) { null }
        if (source != null && target != null && source != target) return FileError.CrossDevice(path)
        for (store: FileStore? in listOf(source, target)) {
            val type: String? = try { store?.type()?.lowercase() } catch (_: Exception) { null }
            if (type != null && type in FAT_FAMILY) return FileError.Unsupported(path)
        }
    }
    return mapNioError(e, path, forLink = true, windows = windows, also = also, codePoints = codePoints)
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
    "operation not permitted" in reason -> FileError.AccessDenied(path)
    else -> FileError.Io(path, null, e)
}

/** Runs [block], turning anything it throws into a [FileOpException] (a [FileOpException] passes through). */
@OptIn(ToolkitInternalApi::class)
internal inline fun <T> nio(path: String, forLink: Boolean = false, namesEntry: Boolean = true, block: () -> T): T =
    try {
        block()
    } catch (e: FileOpException) {
        throw e
    } catch (e: Exception) {
        throw FileOpException(mapNioError(e, path, forLink, namesEntry = namesEntry))
    }
