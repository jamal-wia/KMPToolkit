package io.github.jamal_wia.kmptoolkit.uploader

import android.content.Context
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * [OutcomeStorage] on files under the app's no-backup directory, so a response body never reaches a cloud
 * backup or a device transfer.
 *
 * A write goes to a file in [leftoversDir] first, is synced, and is then renamed over the final name:
 * `rename` is atomic on the platform's file systems, so a reader — a worker starting while another is
 * writing, or a process killed mid-write — sees the previous entry, the whole new one, or nothing.
 */
internal class AndroidOutcomeStorage(
    private val dir: File,
    private val leftoversDir: File,
    private val leftoverAgeMillis: Long = LEFTOVER_AGE_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) : OutcomeStorage {

    override fun write(itemId: String, bytes: ByteArray): Boolean {
        // `mkdirs` returns false for the thread that lost the race to create the directory: the directory existing
        // afterwards is what counts.
        if (!(dir.mkdirs() || dir.isDirectory) || !(leftoversDir.mkdirs() || leftoversDir.isDirectory)) return false
        val partial = File(leftoversDir, "$itemId.${UUID.randomUUID()}")
        try {
            FileOutputStream(partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!partial.renameTo(File(dir, itemId))) return false
            return true
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    override fun read(itemId: String): ByteArray? {
        val file = File(dir, itemId)
        return if (file.isFile) file.readBytes() else null
    }

    override fun delete(itemId: String) {
        File(dir, itemId).delete()
    }

    override fun itemIds(): List<String> = dir.list()?.sorted().orEmpty()

    override fun purgeLeftovers() {
        val cutoff: Long = now() - leftoverAgeMillis
        leftoversDir.listFiles()?.forEach { file -> if (file.lastModified() < cutoff) file.delete() }
    }

    companion object {

        /** A leftover younger than this may belong to a write still running in another process. */
        const val LEFTOVER_AGE_MILLIS: Long = 60L * 60L * 1000L

        /** The journal of outcomes under [context]'s `noBackupFilesDir`: `kmptoolkit-uploader/outcomes/<itemId>`. */
        fun forContext(context: Context): AndroidOutcomeStorage {
            val root = File(context.noBackupFilesDir, "kmptoolkit-uploader")
            return AndroidOutcomeStorage(dir = File(root, "outcomes"), leftoversDir = File(root, "outcomes-partial"))
        }
    }
}

/** The outcome journal for [context], stored under its no-backup directory. */
internal fun androidOutcomeJournal(context: Context, logger: Logger = NoopLogger): OutcomeJournal =
    OutcomeJournal(AndroidOutcomeStorage.forContext(context), UploaderClock.System, logger)
