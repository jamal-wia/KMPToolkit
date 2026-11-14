package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.io.IOException

/**
 * [OutcomeStorage] on files under the app's no-backup directory, so a response body never reaches a cloud
 * backup or a device transfer.
 *
 * A write goes through `kmptoolkit-files`: the bytes are written to a temporary file in [leftoversDir], synced, and
 * then renamed over the final name. The rename is atomic on the platform's file systems, so a reader — a worker
 * starting while another is writing, or a process killed mid-write — sees the previous entry, the whole new one, or
 * nothing.
 */
internal class AndroidOutcomeStorage(
    private val dir: File,
    private val leftoversDir: File,
    private val leftoverAgeMillis: Long = LEFTOVER_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
    private val files: FileAccess = createFileAccess(),
) : OutcomeStorage {

    override fun write(itemId: String, bytes: ByteArray): Boolean {
        val options = WriteOptions(
            durability = Durability.Synced,
            createParentDirectories = true,
            tempDirectory = leftoversDir.path,
        )
        return files.writeAtomically(File(dir, itemId).path, bytes, options).isSuccess
    }

    override fun read(itemId: String): ByteArray? =
        when (val result: FileResult<ByteArray> = files.readBytes(File(dir, itemId).path, MAX_ENCODED_ENTRY_BYTES)) {
            is FileResult.Success -> result.value
            // Larger than any entry the journal writes: not read at all (a poison file would exhaust the heap).
            is FileResult.Failure -> when (val error: FileError = result.error) {
                is FileError.TooLarge -> ByteArray(0)
                // Nothing there, or not a regular file: no entry, as before.
                is FileError.NotFound, is FileError.IsADirectory, is FileError.NotADirectory, is FileError.Unsupported,
                is FileError.InvalidPath -> null
                // An entry that exists but cannot be read right now (permission, I/O error) throws, as a failed
                // read always did: the journal then reports it unreadable and keeps it instead of treating it as absent.
                else -> throw IOException("The outcome journal entry cannot be read: $error", (error as? FileError.Io)?.cause)
            }
        }

    override fun delete(itemId: String) {
        files.delete(File(dir, itemId).path)
    }

    override fun itemIds(): List<String> = files.list(dir.path).getOrNull().orEmpty()

    override fun purgeLeftovers() {
        val cutoff: Long = saturatingSubtract(now(), leftoverAgeMillis)
        // The injected clock, not FileAccess.sweep: sweep measures age against the file system's own clock.
        for (name in files.list(leftoversDir.path).getOrNull().orEmpty()) {
            val child: String = File(leftoversDir, name).path
            val metadata: FileMetadata = files.metadata(child) ?: continue
            val modified: Long = metadata.lastModifiedMillis ?: continue
            if (modified < cutoff) files.delete(child)
        }
    }

    companion object {

        /** A leftover younger than this may belong to a write still running in another process. */
        const val LEFTOVER_AGE_MILLIS: Long = 60L * 60L * 1000L

        /**
         * The journal of outcomes under [context]'s `noBackupFilesDir`: `<directoryName>/outcomes/<itemId>`, with
         * temporary files in `<directoryName>/outcomes-partial`.
         */
        fun forContext(context: Context, directoryName: String = UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME): AndroidOutcomeStorage {
            val root = File(context.noBackupFilesDir, directoryName)
            return AndroidOutcomeStorage(dir = File(root, "outcomes"), leftoversDir = File(root, "outcomes-partial"))
        }
    }
}

/** The outcome journal for [context], stored under its no-backup directory in [directoryName]. */
internal fun androidOutcomeJournal(
    context: Context,
    logger: Logger = NoopLogger,
    directoryName: String = UploadTransportConfig.DEFAULT_JOURNAL_DIRECTORY_NAME,
): OutcomeJournal =
    OutcomeJournal(AndroidOutcomeStorage.forContext(context, directoryName), UploaderClock.System, logger)
