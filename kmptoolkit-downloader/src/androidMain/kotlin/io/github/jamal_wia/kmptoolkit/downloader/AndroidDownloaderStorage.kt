package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.Companion.WRITE_BUFFER_SIZE
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * [DownloaderStorage] over plain files under `filesDir/<config.baseDirectoryName>/`.
 *
 * A downloader implementation writing into the temp file this class names via [getTempFilePath]
 * needs nothing further from here than [markTempFileComplete] at the end — ordinary
 * `FileOutputStream` (append mode, resuming from [getTempFileSize]) is all a
 * `BackgroundResourceDownloader` needs to stream bytes onto disk. This class only finalizes what
 * already arrived.
 *
 * A complete transfer is a different file from a partial one: [markTempFileComplete] renames
 * `tmp/<id>.<ext>` to `tmp/<id>.<ext>.complete`, an atomic rename within one directory, so no crash
 * can leave a partial file that reads as complete. A `tmp/<id>.<ext>` left by an earlier version of
 * this library is therefore partial, and is resumed rather than committed.
 *
 * The hash a transfer must have ([beginTempFile]) is a one-line record at `tmp/expect/<id>`, keyed
 * by id alone so a free-form temp extension can never make two units share or miss a record. It is
 * written before the old temp files are deleted and moved into place by a rename, so a crash leaves
 * either the old state or the new one, never a partial file paired with the wrong record.
 */
internal class AndroidDownloaderStorage(
    private val context: Context,
    private val config: DownloaderStorageConfig,
    private val logger: Logger,
) : DownloaderStorage {

    private val baseDir: File by lazy { File(context.filesDir, config.baseDirectoryName) }

    override fun isResourceAvailable(unit: DownloadUnit): Boolean {
        return if (unit.isDirectoryResource) {
            val pagesDir = File(getResourcePath(unit))
            pagesDir.exists() && File(pagesDir, unit.archiveMarker()).exists()
        } else {
            File(getResourcePath(unit)).exists()
        }
    }

    override fun getResourcePath(unit: DownloadUnit): String {
        return File(baseDir, unit.relativePath).absolutePath
    }

    override fun getTempFilePath(unit: DownloadUnit): String {
        return File(baseDir, "tmp/${unit.id}.${unit.tempExtension}").absolutePath
    }

    private fun completeTempFile(unit: DownloadUnit): File =
        File(baseDir, "tmp/${unit.id}.${unit.tempExtension}.complete")

    override fun tempFileState(unit: DownloadUnit): TempFileState {
        if (completeTempFile(unit).exists()) return TempFileState.Complete
        val partial = File(getTempFilePath(unit))
        return if (partial.exists() && partial.length() > 0) {
            TempFileState.Partial
        } else {
            TempFileState.None
        }
    }

    override fun getTempFileSize(unit: DownloadUnit): Long {
        val tempFile = File(getTempFilePath(unit))
        return if (tempFile.exists()) tempFile.length() else 0L
    }

    override fun markTempFileComplete(unit: DownloadUnit) {
        val complete: File = completeTempFile(unit)
        if (complete.exists()) return
        val partial = File(getTempFilePath(unit))
        check(partial.exists()) { "No temp file to mark complete for $unit at ${partial.path}" }
        check(partial.renameTo(complete)) { "Could not mark the temp file of $unit complete" }
        logger.i { "Marked the temp file of $unit complete (${complete.length()} bytes)" }
    }

    override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) {
        // Before any side effect: a contradictory catalogue must leave the existing partial file and
        // its record exactly as they were.
        requireConsistentExpectations(unit, expectedSha256)
        val staged: File = stagedExpectationFile(unit)
        staged.parentFile?.mkdirs()
        FileOutputStream(staged).use { output: FileOutputStream ->
            output.write(encodeExpectation(expectedSha256).encodeToByteArray())
            output.fd.sync()
        }
        deleteTempData(unit)
        // A delete that failed must not be followed by the rename: the surviving old bytes would
        // be paired with the new record.
        if (File(getTempFilePath(unit)).exists() || completeTempFile(unit).exists()) {
            staged.delete()
            throw IllegalStateException("Could not discard the previous temp file of $unit")
        }
        val record: File = expectationFile(unit)
        record.parentFile?.mkdirs()
        check(staged.renameTo(record)) { "Could not record the expected hash of $unit" }
        logger.i { "Began the temp file of $unit (expected hash: ${expectedSha256?.hex ?: "none"})" }
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        // Data first, record last: a crash in between leaves an orphan record, which reads as no
        // temp file at all and is overwritten by the next beginTempFile.
        deleteTempData(unit)
        expectationFile(unit).delete()
        stagedExpectationFile(unit).delete()
    }

    private fun deleteTempData(unit: DownloadUnit) {
        File(getTempFilePath(unit)).delete()
        completeTempFile(unit).delete()
    }

    private fun expectationFile(unit: DownloadUnit): File = File(baseDir, "tmp/expect/${unit.id}")

    private fun stagedExpectationFile(unit: DownloadUnit): File =
        File(baseDir, "tmp/expect-staged/${unit.id}")

    private fun readExpectation(unit: DownloadUnit): RecordedExpectation {
        val record: File = expectationFile(unit)
        if (!record.exists()) return RecordedExpectation.Missing
        return try {
            decodeExpectation(record.readText())
        } catch (e: IOException) {
            logger.w { "Cannot read the expected-hash record of $unit: ${e.message}" }
            RecordedExpectation.Unreadable
        }
    }

    override fun getResourceSize(unit: DownloadUnit): Long {
        val target = File(getResourcePath(unit))
        if (!target.exists()) return 0L
        return if (target.isDirectory) {
            target.walkBottomUp()
                .filter { f: File -> f.isFile }
                .sumOf { f: File -> f.length() }
        } else {
            target.length()
        }
    }

    override fun deleteResource(unit: DownloadUnit) {
        val target = File(getResourcePath(unit))
        if (!target.exists()) return
        if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
        logger.i { "Deleted resource $unit at ${target.absolutePath}" }
    }

    override suspend fun commitResource(
        unit: DownloadUnit,
    ): Unit = withContext(Dispatchers.IO) {
        val tempFile: File = completeTempFile(unit)
        check(tempFile.exists()) {
            "No complete temp file for $unit — a partial transfer is resumed, never committed"
        }
        try {
            commitCompleteTempFile(unit, tempFile)
        } finally {
            // The record belongs to the temp file. Once the file is gone — committed, or deleted by
            // a failed check — the record goes with it, last. A failure that left the file in place
            // keeps its record, so the retry is still checked against it.
            if (!tempFile.exists()) expectationFile(unit).delete()
        }
    }

    private fun commitCompleteTempFile(unit: DownloadUnit, tempFile: File) {
        verifySha256(tempFile, unit)
        if (unit.isDirectoryResource) {
            val targetDir = File(getResourcePath(unit))
            // Per-unit, not a single shared name — two archive units extracting at the same time
            // must not collide in one staging directory.
            val stagingDir = File(baseDir, "tmp/staging-${unit.id}")
            // Clean any leftover staging from a previous failed attempt
            stagingDir.deleteRecursively()
            try {
                extractZip(
                    tempFile = tempFile,
                    targetDir = stagingDir.absolutePath,
                )
                // Handle nested directory: ZIP may contain a root directory prefix
                // (e.g., entries like "pages/page001.webp" instead of "page001.webp"),
                // causing files to land in staging/pages/ instead of staging/.
                if (!File(stagingDir, unit.archiveMarker()).exists()) {
                    val subdirs: Array<File> =
                        stagingDir.listFiles { f: File -> f.isDirectory } ?: emptyArray()
                    if (subdirs.size == 1) {
                        val nestedDir: File = subdirs[0]
                        logger.i { "Detected nested directory '${nestedDir.name}', moving contents up" }
                        nestedDir.listFiles()?.forEach { file: File ->
                            file.renameTo(File(stagingDir, file.name))
                        }
                        nestedDir.delete()
                    }
                }
                // Atomic swap: remove old target, rename staging to target
                targetDir.deleteRecursively()
                if (!stagingDir.renameTo(targetDir)) {
                    // Fallback: copy + delete (cross-filesystem)
                    stagingDir.copyRecursively(target = targetDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
            } catch (e: Exception) {
                stagingDir.deleteRecursively()
                throw e
            }
            tempFile.delete()
        } else {
            (unit.format as? ResourceFormat.SqliteDatabase)?.let { format ->
                verifySqlite(tempFile, format)
            }
            moveFile(
                tempFile = tempFile,
                destinationPath = getResourcePath(unit),
            )
        }
    }

    /**
     * Checks [tempFile] against every hash expected of it — [DownloadUnit.sha256] and the one
     * recorded by [beginTempFile] — hashing the file once, and only when there is something to
     * compare. Deletes the file and throws [ResourceIntegrityException] on a mismatch or on a
     * record that cannot be read (never "no check"). A unit whose own [DownloadUnit.sha256] getter
     * throws is the host's mistake, not the download's, and surfaces as that exception instead so
     * it is not answered with a pointless re-download.
     */
    private fun verifySha256(tempFile: File, unit: DownloadUnit) {
        val unitSha256: Sha256? = unit.sha256
        val recorded: RecordedExpectation = readExpectation(unit)
        if (recorded == RecordedExpectation.Unreadable) {
            tempFile.delete()
            throw ResourceIntegrityException(UNREADABLE_RECORD_MESSAGE)
        }
        if (recorded == RecordedExpectation.Missing) {
            logger.w {
                "No expected-hash record for $unit: a partial from before records existed, or a " +
                    "downloader that never called beginTempFile. Only DownloadUnit.sha256 is checked."
            }
        }
        val required: List<Sha256> = requiredHashes(unitSha256, recorded)
        if (required.isEmpty()) return
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(WRITE_BUFFER_SIZE)
        FileInputStream(tempFile).use { input: FileInputStream ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val failure: ResourceIntegrityException? =
            integrityFailure(actualHex = digest.digest().toLowerHex(), required = required)
        if (failure != null) {
            tempFile.delete()
            throw failure
        }
    }

    /**
     * Verifies a committed-to-be [ResourceFormat.SqliteDatabase] before it is moved into place.
     *
     * Opening it is the baseline check — bytes that are not a database fail here. When the format
     * also names a table and a `meta` key, the real row count must match the count the file itself
     * declares, which catches a truncated download that still happens to parse. Which table and
     * which key those are is the host's own domain knowledge, which is why they are values on the
     * unit's [ResourceFormat.SqliteDatabase] rather than anything this library assumes.
     *
     * Deletes the temp file and throws [ResourceIntegrityException] on any failure: nothing invalid
     * may reach the final path, not even momentarily.
     */
    private fun verifySqlite(tempFile: File, format: ResourceFormat.SqliteDatabase) {
        val counts: Pair<Int, Int?>? = try {
            SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { db: SQLiteDatabase ->
                    // Opening alone proves nothing: SQLite reads the header lazily, so bytes
                    // that are not a database open fine and fail only at the first read.
                    queryInt(db, "SELECT COUNT(*) FROM sqlite_master")
                    val table: String? = format.rowCountTable
                    val metaKey: String? = format.declaredRowCountMetaKey
                    if (table == null || metaKey == null) {
                        null
                    } else {
                        queryInt(db, "SELECT COUNT(*) FROM $table") to
                            queryText(db, "SELECT value FROM meta WHERE key = '$metaKey'")?.toIntOrNull()
                    }
                }
        } catch (e: SQLiteException) {
            tempFile.delete()
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: not a valid database (${e.message})",
                e,
            )
        }
        val (actualCount: Int, declaredCount: Int?) = counts ?: return
        if (declaredCount == null || actualCount != declaredCount) {
            tempFile.delete()
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: " +
                    "$actualCount rows, meta declares $declaredCount",
            )
        }
    }

    private fun queryInt(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
    }

    private fun queryText(db: SQLiteDatabase, sql: String): String? {
        db.rawQuery(sql, null).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun moveFile(tempFile: File, destinationPath: String) {
        val destination = File(destinationPath)
        destination.parentFile?.mkdirs()
        if (!tempFile.renameTo(destination)) {
            // Fallback: copy + delete (cross-filesystem move)
            tempFile.inputStream()
                .buffered()
                .use { input: BufferedInputStream ->
                    destination.outputStream()
                        .buffered()
                        .use { output: BufferedOutputStream ->
                            input.copyTo(output)
                        }
                }
            tempFile.delete()
        }
        logger.i { "Saved resource to $destinationPath (${destination.length()} bytes)" }
    }

    private fun extractZip(tempFile: File, targetDir: String) {
        logger.i { "Extracting ZIP to $targetDir..." }
        val dir = File(targetDir)
        dir.mkdirs()

        // Single reusable buffer for all entries to minimize allocations — a large archive with
        // hundreds of small files can otherwise trigger OOM once the app is backgrounded and heap
        // is tight.
        val copyBuffer = ByteArray(WRITE_BUFFER_SIZE)

        ZipInputStream(
            FileInputStream(tempFile).buffered()
        ).use { zipStream: ZipInputStream ->
            var entry: ZipEntry? = zipStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName: String = entry.name
                    val outputFile = File(dir, entryName)
                    // Zip slip protection
                    require(
                        outputFile.canonicalPath.startsWith(
                            dir.canonicalPath + File.separator
                        )
                    ) {
                        "ZIP entry attempts path traversal: $entryName"
                    }
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { output: FileOutputStream ->
                        var bytesRead: Int
                        while (zipStream.read(copyBuffer).also { bytesRead = it } != -1) {
                            output.write(copyBuffer, 0, bytesRead)
                        }
                    }
                    logger.d { "Extracted: $entryName" }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
        }
        logger.i { "ZIP extraction complete" }
    }
}
