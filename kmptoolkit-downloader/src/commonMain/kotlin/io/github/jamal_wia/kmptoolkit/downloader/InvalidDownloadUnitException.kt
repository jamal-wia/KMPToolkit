package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Thrown by a [DownloaderStorage] when [unit] names a place it can never safely use: a
 * [DownloadUnit.relativePath] that is empty, contains a NUL character, climbs out of the storage's
 * base directory, or points at a name the storage manages itself.
 *
 * It is an input error of the host's catalogue, not a transfer failure, so nothing about it is
 * retried. The engine reports it, before any byte is fetched, as a
 * [DownloadFailedException] carrying [DownloadError.Unknown] whose `cause` is this exception;
 * [Downloader.pathOf] lets it propagate, and [Downloader.isAvailable] answers `false`.
 *
 * Only a [DownloadUnit.relativePath] is ever rejected. A [DownloadUnit.id] is never an error: an id
 * that cannot be used as a file name (a `/`, a NUL, a name that is too long) is stored under a
 * hashed name instead, see `docs/kmptoolkit-downloader/05-platform-notes.md`.
 *
 * The message is deliberately absent — this library carries no user-facing text. [toString] names
 * the unit and the [reason] for logs.
 *
 * @property unit the unit whose [DownloadUnit.relativePath] was rejected.
 * @property reason which rule it broke.
 * @since 4.0.0
 */
public class InvalidDownloadUnitException(
    public val unit: DownloadUnit,
    public val reason: Reason,
) : IllegalArgumentException() {

    /**
     * Which rule a [DownloadUnit.relativePath] broke.
     *
     * @since 4.0.0
     */
    public enum class Reason {
        /** The path is empty, or normalizes to nothing (`""`, `"/"`, `"."`, `"a/.."`): it would be the base directory itself. */
        EMPTY,

        /** The path contains a NUL character. */
        INVALID_CHARACTER,

        /** The path leaves the base directory (`"../x"`, `"a/../../x"`). */
        ESCAPES_BASE_DIRECTORY,

        /**
         * The path is a name the storage creates and deletes itself: `tmp`, `tmp/expect`,
         * `tmp/expect-staged`, `tmp/kmptoolkit-internal`, anything under `tmp/staging-…`, or one of
         * the unit's own temp files.
         */
        RESERVED_INTERNAL_PATH,
    }

    override fun toString(): String = "InvalidDownloadUnitException(unit=${unit.id}, reason=$reason)"
}
