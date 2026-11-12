package io.github.jamal_wia.kmptoolkit.files

/**
 * What an entry is. [sizeBytes] is the file's length for [FileKind.RegularFile] and 0 for every other kind.
 *
 * @property lastModifiedMillis Epoch milliseconds, or `null` when the platform cannot report it.
 * @since 5.0.0
 */
public data class FileMetadata(
    public val kind: FileKind,
    public val sizeBytes: Long,
    public val lastModifiedMillis: Long?,
)

/**
 * The kind of a file-system entry.
 *
 * @since 5.0.0
 */
public enum class FileKind {
    /** A regular file. */
    RegularFile,

    /** A directory. */
    Directory,

    /** A symbolic link (reported only when links are not followed). */
    SymbolicLink,

    /** Anything else: a device, a pipe, a socket. */
    Other,
}
