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
import kotlin.random.Random
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
 * Those are the names of a unit whose id is a plain file name. An id that is not (a `/`, a NUL, a
 * name too long for one path component) gets the same files under `tmp/kmptoolkit-internal/`, named
 * by a hash of the id: see [UnitFileLayout]. [DownloadUnit.relativePath] is validated by the same
 * class, and a path that cannot be used safely throws [InvalidDownloadUnitException].
 *
 * The hash a transfer must have ([beginTempFile]) is a one-line record at `tmp/expect/<id>.sha256`, keyed
 * by id alone so a free-form temp extension can never make two units share or miss a record. It is
 * written before the old temp files are deleted and moved into place by a rename, so a crash leaves
 * either the old state or the new one, never a partial file paired with the wrong record.
 */
internal class AndroidDownloaderStorage(
    private val context: Context,
    private val config: DownloaderStorageConfig,
    private val logger: Logger,
    /** Test seam: Android's `filesDir` is case-sensitive, so production never sets this. */
    caseInsensitive: Boolean = false,
) : DownloaderStorage, RelativePathChecking {

    private val baseDir: File by lazy { File(context.filesDir, config.baseDirectoryName) }

    private val layout: UnitFileLayout = UnitFileLayout(caseInsensitive)

    private val tmpPathWarnings: FirstSightings = FirstSightings()

    private val migrationFiles: LegacyTempMigrations.Files = object : LegacyTempMigrations.Files {
        override fun exists(path: String): Boolean = File(path).exists()
        override fun renameNoReplace(from: String, to: String): Boolean = io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(from, to)
        override fun delete(path: String) {
            File(path).delete()
        }

        override fun createParentDirectory(path: String) {
            File(path).parentFile?.mkdirs()
        }

        override fun lastModifiedMillis(path: String): Long? = File(path).lastModified().takeIf { it > 0L }

        override fun hasEntriesOtherThan(directory: String, name: String): Boolean =
            File(directory).list().orEmpty().any { it != name }

        override fun readText(path: String): String? = try {
            File(path).readText()
        } catch (e: IOException) {
            null
        }

        override fun writeNew(path: String, text: String): Boolean {
            val target = File(path)
            val temporary = File(target.parentFile, "${target.name}.${Random.nextLong().toULong()}.tmp")
            return try {
                temporary.writeText(text)
                io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(temporary.path, target.path)
            } catch (e: IOException) {
                false
            } finally {
                temporary.delete()
            }
        }
    }

    /**
     * The names of [unit]'s files. A unit whose names are hashed first has its 3.0.0 temp files
     * moved to them (once per process), so every method sees the files wherever they are.
     */
    private fun names(unit: DownloadUnit): UnitFileLayout.Names {
        // Before any name is returned, so before this version writes anything under tmp/ of the base.
        val cutoff: LegacyTempMigrations.Cutoff =
            LegacyTempMigrations.ensureLayout(baseDir.absolutePath, migrationFiles, logger)
        val names: UnitFileLayout.Names = layout.namesOf(unit)
        if (names.anyHashed) {
            LegacyTempMigrations.migrateOnce(baseDir.absolutePath, unit, layout, names, migrationFiles, logger, cutoff)
        }
        return names
    }

    override fun isResourceAvailable(unit: DownloadUnit): Boolean {
        val path: String = try {
            getResourcePath(unit)
        } catch (e: InvalidDownloadUnitException) {
            return false
        }
        return if (unit.isDirectoryResource) {
            val pagesDir = File(path)
            pagesDir.exists() && File(pagesDir, unit.archiveMarker()).exists()
        } else {
            File(path).exists()
        }
    }

    override fun relativePathViolation(unit: DownloadUnit): InvalidDownloadUnitException.Reason? =
        (layout.inspectRelativePath(unit) as? UnitFileLayout.PathInspection.Rejected)?.reason

    override fun getResourcePath(unit: DownloadUnit): String {
        when (val inspection: UnitFileLayout.PathInspection = layout.inspectRelativePath(unit)) {
            is UnitFileLayout.PathInspection.Rejected -> throw InvalidDownloadUnitException(unit, inspection.reason)
            UnitFileLayout.PathInspection.AcceptedUnderTmp -> {
                if (tmpPathWarnings.isFirst(unit.relativePath)) {
                    logger.w { "relativePath '${unit.relativePath}' of $unit is under tmp/, which the storage reserves" }
                }
            }
            UnitFileLayout.PathInspection.Accepted -> Unit
        }
        return File(baseDir, unit.relativePath).absolutePath
    }

    override fun getTempFilePath(unit: DownloadUnit): String =
        File(baseDir, names(unit).partial).absolutePath

    override fun tempFileState(unit: DownloadUnit): TempFileState {
        val names: UnitFileLayout.Names = names(unit)
        // The partial file is read BEFORE the complete one: marking complete renames partial into
        // complete, so a partial that was absent at the first read was already complete at the
        // second. The other order can miss both files while one rename lands between the reads.
        val partialLength: Long = File(baseDir, names.partial).length() // 0 when absent
        if (File(baseDir, names.complete).exists()) return TempFileState.Complete
        return if (partialLength > 0) TempFileState.Partial else TempFileState.None
    }

    override fun getTempFileSize(unit: DownloadUnit): Long {
        val tempFile = File(baseDir, names(unit).partial)
        return if (tempFile.exists()) tempFile.length() else 0L
    }

    override fun markTempFileComplete(unit: DownloadUnit) {
        val names: UnitFileLayout.Names = names(unit)
        val complete = File(baseDir, names.complete)
        if (complete.exists()) return
        val partial = File(baseDir, names.partial)
        // Idempotent under a race: a concurrent caller that renamed the partial first has already
        // done the job, so a vanished source or a lost rename with the complete file in place is a
        // success, not a failure.
        if (!partial.exists()) {
            check(complete.exists()) { "No temp file to mark complete for $unit at ${partial.path}" }
            return
        }
        if (!partial.renameTo(complete)) {
            check(complete.exists()) { "Could not mark the temp file of $unit complete" }
            return
        }
        logger.i { "Marked the temp file of $unit complete (${complete.length()} bytes)" }
    }

    override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) {
        // Before any side effect: a contradictory catalogue must leave the existing partial file and
        // its record exactly as they were.
        requireConsistentExpectations(unit, expectedSha256)
        val names: UnitFileLayout.Names = names(unit)
        val staged = File(baseDir, names.stagedRecord)
        staged.parentFile?.mkdirs()
        FileOutputStream(staged).use { output: FileOutputStream ->
            output.write(encodeExpectation(expectedSha256).encodeToByteArray())
            output.fd.sync()
        }
        deleteTempData(names)
        // A delete that failed must not be followed by the rename: the surviving old bytes would
        // be paired with the new record.
        if (File(baseDir, names.partial).exists() || File(baseDir, names.complete).exists()) {
            staged.delete()
            throw IllegalStateException("Could not discard the previous temp file of $unit")
        }
        val record = File(baseDir, names.record)
        record.parentFile?.mkdirs()
        check(staged.renameTo(record)) { "Could not record the expected hash of $unit" }
        // The downloader writes straight into the partial path; its directory may be a hashed one.
        File(baseDir, names.partial).parentFile?.mkdirs()
        logger.i { "Began the temp file of $unit (expected hash: ${expectedSha256?.hex ?: "none"})" }
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        // Data first, record last: a crash in between leaves an orphan record, which reads as no
        // temp file at all and is overwritten by the next beginTempFile.
        val names: UnitFileLayout.Names = names(unit)
        deleteTempData(names)
        // The extraction tree of an archive commit that a process death interrupted: nothing else
        // ever enumerates tmp/, so a unit that is then dropped would keep it forever. Skipped while
        // a commit of the unit runs (it may be extracting into this very tree, or renaming it into
        // place), or while the lock registry is momentarily busy; the next cancel or the next
        // commit reclaims it. Under the lock the tree is only renamed away; the recursive delete
        // runs after the lock is released so it cannot stall other units.
        UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = baseDir.absolutePath,
            unitId = layout.lockKey(unit),
            detach = {
                val staging = File(baseDir, names.staging)
                val trash = File(baseDir, layout.trashEntry(Random.nextLong().toULong()))
                trash.parentFile?.mkdirs()
                if (staging.renameTo(trash)) trash else null
            },
            dispose = { trash: File -> deleteTree(trash) },
        )
        sweepTrash()
        // Safe to call from cleanup paths, so it never throws. But a data file that survived must
        // keep its record: with the record gone, the next attempt would commit that complete file
        // unchecked.
        if (File(baseDir, names.partial).exists() || File(baseDir, names.complete).exists()) {
            logger.w { "Could not delete the temp file of $unit; keeping its expected-hash record" }
            return
        }
        File(baseDir, names.record).delete()
        File(baseDir, names.stagedRecord).delete()
    }

    /**
     * Deletes whatever the trash directory (`tmp/kmptoolkit-internal/trash/`) still holds: a detached
     * extraction tree whose delete a process kill or an I/O error cut short. No commit ever uses a
     * path under it, so it needs no lock, and a sweep racing another thread's delete of the same
     * tree is harmless. A dedicated directory, not a name prefix in `tmp/`, so that a unit whose id
     * starts with `trash-` keeps its temp file; entries are named by a number alone, so no id's
     * length can make the rename into it fail.
     */
    private fun sweepTrash() {
        File(baseDir, UnitFileLayout.TRASH_DIRECTORY).listFiles()?.forEach { entry: File -> deleteTree(entry) }
    }

    /**
     * Deletes [file] and everything under it, treating an entry that another thread already removed
     * as gone. Kotlin's `deleteRecursively` walks with `FileTreeWalk`, which asserts (an
     * `AssertionError`, live in every test JVM) when a tree vanishes under its walk; two cleanups of
     * one tree, or a cleanup racing a recommit, must never surface that. A symbolic link is removed
     * as a link, never followed.
     */
    private fun deleteTree(file: File) {
        if (!isSymbolicLink(file)) {
            file.listFiles()?.forEach { child: File -> deleteTree(child) }
        }
        file.delete()
    }

    /** The size of the files under [file]; an entry that vanished meanwhile counts as zero. */
    private fun treeSize(file: File): Long {
        val children: Array<File>? = file.listFiles()
        return if (children == null) file.length() else children.sumOf { child: File -> treeSize(child) }
    }

    private fun isSymbolicLink(file: File): Boolean = try {
        val parent: File? = file.parentFile?.canonicalFile
        val candidate: File = if (parent == null) file else File(parent, file.name)
        candidate.canonicalFile != candidate.absoluteFile
    } catch (e: java.io.IOException) {
        false
    }

    private fun deleteTempData(names: UnitFileLayout.Names) {
        File(baseDir, names.partial).delete()
        File(baseDir, names.complete).delete()
    }

    private fun readExpectation(unit: DownloadUnit, names: UnitFileLayout.Names): RecordedExpectation {
        val record = File(baseDir, names.record)
        if (!record.exists()) return RecordedExpectation.Missing
        return try {
            decodeExpectation(record.readText())
        } catch (e: IOException) {
            logger.w { "Cannot read the expected-hash record of $unit: ${e.message}" }
            RecordedExpectation.Unreadable
        }
    }

    override fun getResourceSize(unit: DownloadUnit): Long {
        val target = File(
            try {
                getResourcePath(unit)
            } catch (e: InvalidDownloadUnitException) {
                return 0L
            },
        )
        if (!target.exists()) return 0L
        return if (target.isDirectory) {
            treeSize(target)
        } else {
            target.length()
        }
    }

    override fun deleteResource(unit: DownloadUnit) {
        val target = File(getResourcePath(unit))
        if (!target.exists()) return
        if (target.isDirectory) {
            deleteTree(target)
        } else {
            target.delete()
        }
        logger.i { "Deleted resource $unit at ${target.absolutePath}" }
    }

    override suspend fun commitResource(
        unit: DownloadUnit,
    ): Unit = withContext(Dispatchers.IO) {
        // Before the lock and before any side effect: a relativePath that cannot be used must leave
        // the temp file where it is, and the names are resolved outside the lock.
        getResourcePath(unit)
        val names: UnitFileLayout.Names = names(unit)
        // Serialized per unit across every instance over this directory — see UnitCommitLocks for
        // who races here. Everything below is decided only once the lock is held.
        UnitCommitLocks.withUnitLock(baseDir.absolutePath, layout.lockKey(unit)) {
            val tempFile = File(baseDir, names.complete)
            if (!tempFile.exists() && isResourceAvailable(unit)) {
                // A concurrent commit finalized the unit while this one waited. Its record is
                // already gone, and one present now belongs to a newer transfer: touch nothing.
                logger.i { "$unit was already committed by a concurrent commit" }
                return@withUnitLock
            }
            check(tempFile.exists()) {
                "No complete temp file for $unit — a partial transfer is resumed, never committed"
            }
            try {
                commitCompleteTempFile(unit, tempFile, names)
            } finally {
                // The record belongs to the temp file. Once the file is gone — committed, or deleted
                // by a failed check — the record goes with it, last. A failure that left the file in
                // place keeps its record, so the retry is still checked against it.
                if (!tempFile.exists()) File(baseDir, names.record).delete()
            }
        }
    }

    private fun commitCompleteTempFile(unit: DownloadUnit, tempFile: File, names: UnitFileLayout.Names) {
        verifySha256(tempFile, unit, names)
        if (unit.isDirectoryResource) {
            val targetDir = File(getResourcePath(unit))
            // Per-unit, not a single shared name — two archive units extracting at the same time
            // must not collide in one staging directory.
            val stagingDir = File(baseDir, names.staging)
            // Clean any leftover staging from a previous failed attempt
            deleteTree(stagingDir)
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
                deleteTree(targetDir)
                if (!stagingDir.renameTo(targetDir)) {
                    // Fallback: copy + delete (cross-filesystem)
                    stagingDir.copyRecursively(target = targetDir, overwrite = true)
                    deleteTree(stagingDir)
                }
            } catch (e: Exception) {
                deleteTree(stagingDir)
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
    private fun verifySha256(tempFile: File, unit: DownloadUnit, names: UnitFileLayout.Names) {
        checkExpectations(
            unit = unit,
            unitSha256 = unit.sha256,
            recorded = readExpectation(unit, names),
            logger = logger,
            computeHex = { sha256Hex(tempFile) },
            deleteTempFile = { tempFile.delete() },
        )
    }

    private fun sha256Hex(file: File): String {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(WRITE_BUFFER_SIZE)
        FileInputStream(file).use { input: FileInputStream ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toLowerHex()
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
