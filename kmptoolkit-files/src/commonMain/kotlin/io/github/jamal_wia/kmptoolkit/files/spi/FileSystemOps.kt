package io.github.jamal_wia.kmptoolkit.files.spi

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine

/**
 * One failed low-level operation. Thrown only by [FileSystemOps] implementations, caught only by the engine.
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public class FileOpException(public val error: FileError) :
    Exception(error.toString(), (error as? FileError.Io)?.cause)

/**
 * The low-level operations the engine composes. Every path is absolute and already validated by the engine.
 * Every failure is thrown as [FileOpException] carrying a typed [FileError]; nothing else may escape
 * (map IOException, SecurityException, ErrnoException, InvalidPathException, UnsupportedOperationException).
 * Internal API: it exists so `kmptoolkit-files-testing` can run the real engine over an in-memory file system, and
 * may change in any release.
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public interface FileSystemOps {
    /** True when paths follow Windows syntax (drive letters, UNC, both separators). */
    public val windowsPaths: Boolean

    /** Wall clock in epoch milliseconds, the time base of [FileMetadata.lastModifiedMillis]. */
    public fun nowMillis(): Long

    /** Metadata of the entry, or `null` when nothing exists at [path] (ENOENT, or ENOTDIR on a parent). */
    public fun metadata(path: String, followLinks: Boolean): FileMetadata?

    /** Entry names except "." and "..", in any order; `null` when absent; NotADirectory when not a directory. */
    public fun list(directory: String): List<String>?

    /** Creates exactly one directory level. AlreadyExists when anything is at [path]; NotFound when the parent is missing. */
    public fun createDirectory(path: String)

    /** Creates a new empty regular file exclusively (O_CREAT|O_EXCL) and opens it for writing. AlreadyExists on a clash. */
    public fun createNew(path: String, protection: FileProtection): WritableFile

    /** Opens an existing file for reading, following links. */
    public fun openForRead(path: String): ReadableFile

    /** rename(2): atomically replaces a non-directory [to]. CrossDevice when [from] and [to] are on different volumes. */
    public fun rename(from: String, to: String)

    /** link(2). AlreadyExists when [newPath] exists; Unsupported when the file system has no hard links. */
    public fun link(existing: String, newPath: String)

    /** Unlinks a non-directory (a link itself, never its target) or removes an empty directory. NotFound when absent. */
    public fun remove(path: String)

    /** Makes the entries of [directory] durable (fsync; full = F_FULLFSYNC where available). No-op where a directory cannot be opened (Windows). */
    public fun syncDirectory(directory: String, full: Boolean)

    /** Bytes available to this process on the volume of the existing [path]; `null` when the platform cannot tell. */
    public fun usableSpace(path: String): Long?
}

/**
 * A file open for writing, created by [FileSystemOps.createNew].
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public interface WritableFile {
    /** Writes all [length] bytes or throws. */
    public fun write(bytes: ByteArray, offset: Int, length: Int)

    /** fsync; [full] = F_FULLFSYNC where available. */
    public fun sync(full: Boolean)

    /** Closes the file; idempotent. */
    public fun close()
}

/**
 * A file open for reading, created by [FileSystemOps.openForRead]. The engine closes every one it opens, and never
 * keeps one open while it renames; [close] may run on a different thread than the open.
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public interface ReadableFile {
    /** Reads up to [length] bytes into [buffer] at [offset]; -1 at end of file. */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Closes the file; idempotent. */
    public fun close()
}

/**
 * The real engine over [ops]. Used by every platform factory and by `kmptoolkit-files-testing`.
 *
 * @since 5.0.0
 */
@ToolkitInternalApi
public fun fileAccessOver(ops: FileSystemOps, config: FileAccessConfig = FileAccessConfig()): FileAccess =
    FileEngine(ops, config)
