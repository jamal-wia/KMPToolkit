package io.github.jamal_wia.kmptoolkit.files

/**
 * A typed file-system failure. Never carries display text; [path] is the path the operation was working on (for
 * [FileAccess.move], see its documentation).
 *
 * @since 5.0.0
 */
public sealed interface FileError {
    /** The path the failed operation was working on, or `null` when there is none. */
    public val path: String?

    /** Nothing exists at [path]. */
    public data class NotFound(override val path: String) : FileError

    /** Something already exists at [path]. */
    public data class AlreadyExists(override val path: String) : FileError

    /** [path] or one of its parents is not a directory. */
    public data class NotADirectory(override val path: String) : FileError

    /** [path] is a directory where a file was expected. */
    public data class IsADirectory(override val path: String) : FileError

    /** The directory at [path] is not empty. */
    public data class DirectoryNotEmpty(override val path: String) : FileError

    /** EACCES, EPERM, EROFS, or Windows access denied after the retries documented for the JVM rename. */
    public data class AccessDenied(override val path: String) : FileError

    /** ENOSPC or EDQUOT. */
    public data class NoSpace(override val path: String) : FileError

    /** A name in [path] is longer than the file system allows. */
    public data class NameTooLong(override val path: String) : FileError

    /** The operation would cross volumes (EXDEV, or the JVM refusing an atomic move). */
    public data class CrossDevice(override val path: String) : FileError

    /** The file at [path] is [sizeBytes] long (as judged before or while reading), more than [maxBytes]. */
    public data class TooLarge(
        override val path: String,
        public val sizeBytes: Long,
        public val maxBytes: Long,
    ) : FileError

    /** The file system or platform cannot do this (no hard links; backup exclusion on Android). */
    public data class Unsupported(override val path: String) : FileError

    /** Relative, empty, NUL-containing, or (Windows) a reserved device name or a segment ending in '.' or ' '. */
    public data class InvalidPath(override val path: String) : FileError

    /** The operation was cancelled through its [CancellationCheck]. */
    public data object Cancelled : FileError {
        override val path: String?
            get() = null
    }

    /** Anything else. [platformCode] is the errno (or Win32 code) when known. */
    public data class Io(
        override val path: String?,
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : FileError
}
