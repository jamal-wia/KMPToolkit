package io.github.jamal_wia.kmptoolkit.downloader

/**
 * Where downloaded bytes live on the device. Implemented per platform inside this library
 * (`AndroidDownloaderStorage`, `IosDownloaderStorage`, both behind a `createDownloaderStorage(...)`
 * factory — see `docs/01-architecture.md`'s "platform factories, not `expect fun`" convention); a
 * contract rather than an internal class so a host can bind and fake it. A host never calls the
 * temp-file half, but consumers do read [getResourcePath] to open what was fetched, and a
 * cache-management screen uses [getResourceSize] / [deleteResource].
 *
 * Every method dispatches on the unit's own [DownloadUnit.relativePath], [DownloadUnit.id] and
 * [DownloadUnit.format] — never on which unit it is — so a host adding a resource never touches
 * this file. That also fixes the identity rule an implementation must keep: two units with the
 * same [DownloadUnit.id] refer to the same bytes on disk, whatever objects they happen to be.
 * (Deriving every path from the unit's properties, as both shipped implementations do, gives this
 * for free; an implementation that keyed anything on the object itself would break it.)
 */
public interface DownloaderStorage {

    public companion object {
        /** Copy buffer for streaming a download to disk and for unpacking an archive. */
        public const val WRITE_BUFFER_SIZE: Int = 65_536
    }

    /**
     * True when [unit]'s resource is present AND complete: a plain file must exist, a directory
     * resource must contain its [ResourceFormat.ZipArchive.availabilityMarker], so an extraction
     * interrupted halfway does not read as available.
     */
    public fun isResourceAvailable(unit: DownloadUnit): Boolean

    /** Absolute path of the committed resource — a file, or a directory for an archive unit. */
    public fun getResourcePath(unit: DownloadUnit): String

    /**
     * Absolute path a [BackgroundResourceDownloader] writes [unit]'s bytes into while the transfer
     * runs. Whatever lies here is [TempFileState.Partial] until [markTempFileComplete] is called.
     */
    public fun getTempFilePath(unit: DownloadUnit): String

    /**
     * What is left on disk of [unit]'s transfer. Recovery decides from this alone: only
     * [TempFileState.Complete] is ever committed without a transfer, and [TempFileState.Partial] is
     * resumed, never committed, however large it is.
     */
    public fun tempFileState(unit: DownloadUnit): TempFileState

    /** Size of the in-progress file, or 0 when absent. Drives the HTTP `Range` resume offset. */
    public fun getTempFileSize(unit: DownloadUnit): Long

    /**
     * Declares [unit]'s transfer finished: the file at [getTempFilePath] holds every byte, and
     * [tempFileState] reports [TempFileState.Complete] from now on, across process death. Atomic,
     * so a crash leaves either a partial file or a complete one, never a complete-looking partial
     * one.
     *
     * The [BackgroundResourceDownloader] calls this when the last byte is written and before it
     * emits [BackgroundDownloadEvent.FileReady] or commits on its own; it is the one party that saw
     * the whole response, and so the one that can check the length against `Content-Length` first.
     * Idempotent once complete. Throws when there is no file to mark.
     */
    public fun markTempFileComplete(unit: DownloadUnit)

    /** Deletes [unit]'s temp file, partial or complete. For error and cancel; safe when absent. */
    public fun deleteTempFile(unit: DownloadUnit)

    /**
     * Finalizes a completed download: moves the [TempFileState.Complete] temp file into place, or
     * — for [ResourceFormat.ZipArchive] — extracts it into the target directory and deletes the
     * archive. Throws when there is no complete temp file, and when the resource cannot be
     * finalized.
     *
     * Before anything reaches the final path the bytes are checked against [DownloadUnit.sha256]
     * when the unit states one, and against [ResourceFormat.SqliteDatabase]'s checks for a
     * database. A failed check deletes the temp file and throws [ResourceIntegrityException],
     * which the engine answers with a fresh download rather than a failure. A custom
     * implementation must keep the same rule: nothing that fails a check may reach the final path,
     * not even momentarily.
     */
    public suspend fun commitResource(unit: DownloadUnit)

    /** Bytes [unit] occupies on disk (summed recursively for a directory), or 0 when absent. */
    public fun getResourceSize(unit: DownloadUnit): Long

    /** Removes [unit]'s committed resource, file or directory. Safe when it is not present. */
    public fun deleteResource(unit: DownloadUnit)
}

/**
 * What is left on disk of a unit's transfer — see [DownloaderStorage.tempFileState].
 *
 * Partial and complete are different states with different files, not one file judged by its size:
 * a transfer killed mid-way leaves a file that exists and is not empty, and before this split it
 * was committed as if it were the whole resource.
 */
public sealed interface TempFileState {

    /** Nothing to resume or commit: no file, or an empty one. */
    public data object None : TempFileState

    /** Some bytes of an unfinished transfer. Resumed from [DownloaderStorage.getTempFileSize]. */
    public data object Partial : TempFileState

    /** The transfer finished and was marked so ([DownloaderStorage.markTempFileComplete]). */
    public data object Complete : TempFileState
}

/**
 * Thrown by [DownloaderStorage.commitResource] when the downloaded bytes fail a check: a
 * [DownloadUnit.sha256] mismatch, or a [ResourceFormat.SqliteDatabase] that does not open or does
 * not hold the rows it declares. The temp file is already gone when this is thrown.
 *
 * Its own type because the right answer differs from every other commit failure: downloading again
 * is the fix for corrupt bytes, and not for a full disk. The engine retries once and then reports
 * [DownloadError.Corrupted].
 */
public class ResourceIntegrityException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Which directory a [DownloaderStorage] implementation uses on device — the one thing about the
 * shipped storage that a host might need to change, so two libraries (or two versions of one) that
 * both end up in the same process never collide.
 *
 * @param baseDirectoryName the folder name committed and in-progress resources live under, relative
 *   to the platform's own app-storage root (`filesDir` on Android, `Application Support` on iOS).
 *   Nothing in this module hardcodes an identifier of its own — see `docs/01-architecture.md`.
 */
public data class DownloaderStorageConfig(
    public val baseDirectoryName: String = "kmptoolkit_downloader",
) {
    init {
        require(baseDirectoryName.isNotBlank()) {
            "baseDirectoryName must not be blank, was '$baseDirectoryName'"
        }
        require(baseDirectoryName.none { it in FORBIDDEN_CHARACTERS }) {
            "baseDirectoryName must not contain a path separator or a null character, " +
                "was '$baseDirectoryName'"
        }
    }

    private companion object {
        val FORBIDDEN_CHARACTERS: Set<Char> = setOf('/', '\\', '\u0000')
    }
}
