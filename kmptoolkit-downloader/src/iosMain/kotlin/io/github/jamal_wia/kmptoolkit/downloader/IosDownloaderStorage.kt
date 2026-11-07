package io.github.jamal_wia.kmptoolkit.downloader

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.Companion.WRITE_BUFFER_SIZE
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSFileProtectionNone
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSURLVolumeSupportsCaseSensitiveNamesKey
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSUserDomainMask
import platform.posix.FILE
import platform.posix.SEEK_CUR
import platform.posix.fclose
import platform.posix.ferror
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.remove
import platform.posix.rename
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.z_stream
import platform.zlib.inflate as zlibInflate

/**
 * [DownloaderStorage] over plain files under `Application Support/<config.baseDirectoryName>/`.
 *
 * A downloader implementation writing into the temp file this class names via [getTempFilePath]
 * needs nothing further from here than [markTempFileComplete] at the end — an ordinary
 * `NSFileHandle` (or POSIX `fopen`/`fwrite`) opened at that path, appending from [getTempFileSize]
 * to resume, is all a `BackgroundResourceDownloader` needs. This class only finalizes what already
 * arrived.
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
@OptIn(ExperimentalForeignApi::class)
internal class IosDownloaderStorage(
    private val config: DownloaderStorageConfig,
    private val logger: Logger,
    /** Test seam: null asks the volume, which is what production does. */
    private val caseInsensitiveOverride: Boolean? = null,
) : DownloaderStorage, RelativePathChecking {

    private val baseDir: String by lazy {
        // Application Support, not Documents: these are re-downloadable assets, and Apple
        // guidelines recommend Application Support for app-generated data that can be re-created —
        // it is also excluded from the user-facing "Documents & Data" iCloud backup by default.
        val appSupportDir: String = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        val resourceDir = "$appSupportDir/${config.baseDirectoryName}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = resourceDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        // Exclude from iCloud backup as an extra safety measure, on top of living outside
        // Documents/ already.
        excludeFromBackup(resourceDir)
        resourceDir
    }

    /**
     * Whether the volume under the base directory folds case. A volume that cannot be asked is
     * treated as case-insensitive: that only over-serializes commits and rejects absurd
     * case-variant `TMP/...` paths, while the opposite guess could let two case variants of an id
     * commit at once over the same files.
     */
    private val layout: UnitFileLayout by lazy {
        UnitFileLayout(caseInsensitive = caseInsensitiveOverride ?: !volumeIsCaseSensitive(baseDir))
    }

    private val tmpPathWarnings: FirstSightings = FirstSightings()

    private val migrationFiles: LegacyTempMigrations.Files = object : LegacyTempMigrations.Files {
        override fun exists(path: String): Boolean = fileExists(path)
        override fun renameNoReplace(from: String, to: String): Boolean =
            io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(from, to)
        override fun delete(path: String) {
            remove(path)
        }

        override fun createParentDirectory(path: String) {
            this@IosDownloaderStorage.createParentDirectory(path)
        }

        override fun lastModifiedMillis(path: String): Long? {
            val attributes: Map<Any?, *>? = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            val modified: NSDate = attributes?.get(NSFileModificationDate) as? NSDate ?: return null
            return (modified.timeIntervalSince1970 * 1000.0).toLong().takeIf { it > 0L }
        }

        override fun hasEntriesOtherThan(directory: String, name: String): Boolean =
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, error = null).orEmpty().any { it != name }

        override fun readText(path: String): String? =
            NSString.stringWithContentsOfFile(path = path, encoding = NSUTF8StringEncoding, error = null)

        override fun writeNew(path: String, text: String): Boolean {
            val temporary = "$path.${Random.nextLong().toULong()}.tmp"
            return try {
                writeAndSync(temporary, text.encodeToByteArray())
                io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(temporary, path)
            } catch (e: IllegalStateException) {
                false
            } finally {
                remove(temporary)
            }
        }
    }

    private fun volumeIsCaseSensitive(path: String): Boolean {
        val values: Map<Any?, *>? = NSURL.fileURLWithPath(path)
            .resourceValuesForKeys(listOf(NSURLVolumeSupportsCaseSensitiveNamesKey), error = null)
        return when (val value: Any? = values?.get(NSURLVolumeSupportsCaseSensitiveNamesKey)) {
            is Boolean -> value
            is NSNumber -> value.boolValue
            else -> false
        }
    }

    /**
     * The names of [unit]'s files. A unit whose names are hashed first has its 3.0.0 temp files
     * moved to them (once per process), so every method sees the files wherever they are.
     */
    private fun names(unit: DownloadUnit): UnitFileLayout.Names {
        // Before any name is returned, so before this version writes anything under tmp/ of the base.
        val cutoff: LegacyTempMigrations.Cutoff = LegacyTempMigrations.ensureLayout(baseDir, migrationFiles, logger)
        val names: UnitFileLayout.Names = layout.namesOf(unit)
        if (names.anyHashed) {
            LegacyTempMigrations.migrateOnce(baseDir, unit, layout, names, migrationFiles, logger, cutoff)
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
            fileExists("$path/${unit.archiveMarker()}")
        } else {
            fileExists(path)
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
        return "$baseDir/${unit.relativePath}"
    }

    override fun getTempFilePath(unit: DownloadUnit): String = "$baseDir/${names(unit).partial}"

    override fun tempFileState(unit: DownloadUnit): TempFileState {
        val names: UnitFileLayout.Names = names(unit)
        return when {
            fileExists("$baseDir/${names.complete}") -> TempFileState.Complete
            sizeOf("$baseDir/${names.partial}") > 0L -> TempFileState.Partial
            else -> TempFileState.None
        }
    }

    override fun markTempFileComplete(unit: DownloadUnit) {
        val names: UnitFileLayout.Names = names(unit)
        val complete = "$baseDir/${names.complete}"
        if (fileExists(complete)) return
        val partial = "$baseDir/${names.partial}"
        // Idempotent under a race: a concurrent caller that moved the partial first has already
        // done the job, so a vanished source or a lost move with the complete file in place is a
        // success, not a failure.
        if (!fileExists(partial)) {
            check(fileExists(complete)) { "No temp file to mark complete for $unit at $partial" }
            return
        }
        val moved: Boolean = NSFileManager.defaultManager.moveItemAtPath(
            srcPath = partial,
            toPath = complete,
            error = null,
        )
        if (!moved) {
            check(fileExists(complete)) { "Could not mark the temp file of $unit complete" }
            return
        }
        logger.i { "Marked the temp file of $unit complete" }
    }

    override fun getTempFileSize(unit: DownloadUnit): Long = sizeOf("$baseDir/${names(unit).partial}")

    private fun sizeOf(path: String): Long {
        val attributes: Map<Any?, *>? = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, error = null)

        val size: Long? = attributes?.get(NSFileSize) as? Long
        return size ?: 0L
    }

    override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) {
        // Before any side effect: a contradictory catalogue must leave the existing partial file and
        // its record exactly as they were.
        requireConsistentExpectations(unit, expectedSha256)
        val names: UnitFileLayout.Names = names(unit)
        val staged = "$baseDir/${names.stagedRecord}"
        createParentDirectory(staged)
        writeAndSync(staged, encodeExpectation(expectedSha256).encodeToByteArray())
        deleteTempData(names)
        // A delete that failed must not be followed by the rename: the surviving old bytes would
        // be paired with the new record.
        if (fileExists("$baseDir/${names.partial}") || fileExists("$baseDir/${names.complete}")) {
            remove(staged)
            throw IllegalStateException("Could not discard the previous temp file of $unit")
        }
        val record = "$baseDir/${names.record}"
        createParentDirectory(record)
        // The record holds only a public hash, and a background commit can run while the device is
        // locked: under the app's default protection class it would read as Unreadable, and the
        // complete download would be deleted as Corrupted. The rename keeps the attribute.
        NSFileManager.defaultManager.setAttributes(
            attributes = mapOf(NSFileProtectionKey to NSFileProtectionNone),
            ofItemAtPath = staged,
            error = null,
        )
        // POSIX rename replaces an existing record atomically; moveItemAtPath would refuse.
        check(rename(staged, record) == 0) { "Could not record the expected hash of $unit" }
        // The downloader writes straight into the partial path; its directory may be a hashed one.
        createParentDirectory("$baseDir/${names.partial}")
        logger.i { "Began the temp file of $unit (expected hash: ${expectedSha256?.hex ?: "none"})" }
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        // Data first, record last: a crash in between leaves an orphan record, which reads as no
        // temp file at all and is overwritten by the next beginTempFile.
        val names: UnitFileLayout.Names = names(unit)
        deleteTempData(names)
        // The extraction tree of an archive commit that a process death interrupted: nothing else
        // ever enumerates tmp/, so a unit that is then dropped would keep it forever. Skipped while
        // a commit of the unit runs (it may be extracting into this very tree, or moving it into
        // place), or while the lock registry is momentarily busy; the next cancel or the next
        // commit reclaims it. Under the lock the tree is only renamed away; the recursive delete
        // runs after the lock is released so it cannot stall other units.
        UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = baseDir,
            unitId = layout.lockKey(unit),
            detach = {
                val trash = "$baseDir/${layout.trashEntry(Random.nextLong().toULong())}"
                createParentDirectory(trash)
                if (rename("$baseDir/${names.staging}", trash) == 0) trash else null
            },
            dispose = { trash: String -> NSFileManager.defaultManager.removeItemAtPath(trash, error = null) },
        )
        sweepTrash()
        // Safe to call from cleanup paths, so it never throws. But a data file that survived must
        // keep its record: with the record gone, the next attempt would commit that complete file
        // unchecked.
        if (fileExists("$baseDir/${names.partial}") || fileExists("$baseDir/${names.complete}")) {
            logger.w { "Could not delete the temp file of $unit; keeping its expected-hash record" }
            return
        }
        remove("$baseDir/${names.record}")
        remove("$baseDir/${names.stagedRecord}")
    }

    /**
     * Deletes whatever the trash directory (`tmp/kmptoolkit-internal/trash/`) still holds: a
     * detached extraction tree whose delete a process kill or an I/O error cut short. No commit ever
     * uses a path under it, so it needs no lock, and a sweep racing another thread's delete of the
     * same tree is harmless. A dedicated directory, not a name prefix in `tmp/`, so that a unit
     * whose id starts with `trash-` keeps its temp file; entries are named by a number alone, so no
     * id's length can make the rename into it fail.
     */
    private fun sweepTrash() {
        val trashDir = "$baseDir/${UnitFileLayout.TRASH_DIRECTORY}"
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(trashDir, error = null).orEmpty().forEach { name ->
            NSFileManager.defaultManager.removeItemAtPath("$trashDir/$name", error = null)
        }
    }

    private fun deleteTempData(names: UnitFileLayout.Names) {
        remove("$baseDir/${names.partial}")
        remove("$baseDir/${names.complete}")
    }

    private fun readExpectation(unit: DownloadUnit, names: UnitFileLayout.Names): RecordedExpectation {
        val record = "$baseDir/${names.record}"
        if (!fileExists(record)) return RecordedExpectation.Missing
        val text: String? = NSString.stringWithContentsOfFile(
            path = record,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (text == null) {
            logger.w { "Cannot read the expected-hash record of $unit" }
            return RecordedExpectation.Unreadable
        }
        return decodeExpectation(text)
    }

    private fun createParentDirectory(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    /** Writes [bytes] to [path] and flushes them to disk before returning, so a rename can trust them. */
    private fun writeAndSync(path: String, bytes: ByteArray) {
        val file: CPointer<FILE> = fopen(path, "wb")
            ?: throw IllegalStateException("Cannot open $path for writing")
        try {
            if (bytes.isNotEmpty()) {
                val written: Long = bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file).toLong()
                }
                check(written == bytes.size.toLong()) { "Short write to $path" }
            }
            check(fflush(file) == 0) { "Could not flush $path" }
            check(fsync(fileno(file)) == 0) { "Could not sync $path to disk" }
        } finally {
            fclose(file)
        }
    }

    override fun getResourceSize(unit: DownloadUnit): Long {
        val path: String = try {
            getResourcePath(unit)
        } catch (e: InvalidDownloadUnitException) {
            return 0L
        }
        if (!fileExists(path)) return 0L
        val fm: NSFileManager = NSFileManager.defaultManager
        val attributes: Map<Any?, *>? = fm.attributesOfItemAtPath(path, error = null)

        val isDir: Boolean = (attributes?.get(NSFileType) as? String) == NSFileTypeDirectory

        if (!isDir) {
            return (attributes?.get(NSFileSize) as? Long) ?: 0L
        }

        // Recursive enumeration for directories
        val enumerator: NSDirectoryEnumerator = fm.enumeratorAtPath(path) ?: return 0L
        var total = 0L
        while (true) {
            val next: Any = enumerator.nextObject() ?: break
            val relative: String = next as? String ?: continue
            val full = "$path/$relative"
            val attrs: Map<Any?, *>? = fm.attributesOfItemAtPath(full, error = null)

            val type: String? = attrs?.get(NSFileType) as? String
            if (type == NSFileTypeRegular) {
                val size: Long = (attrs?.get(NSFileSize) as? Long) ?: 0L
                total += size
            }
        }
        return total
    }

    override fun deleteResource(unit: DownloadUnit) {
        val path: String = getResourcePath(unit)
        if (!fileExists(path)) return
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        logger.i { "Deleted resource $unit at $path" }
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
        UnitCommitLocks.withUnitLock(baseDir, layout.lockKey(unit)) {
            val tempFilePath = "$baseDir/${names.complete}"
            if (!fileExists(tempFilePath) && isResourceAvailable(unit)) {
                // A concurrent commit finalized the unit while this one waited. Its record is
                // already gone, and one present now belongs to a newer transfer: touch nothing.
                logger.i { "$unit was already committed by a concurrent commit" }
                return@withUnitLock
            }
            check(fileExists(tempFilePath)) {
                "No complete temp file for $unit — a partial transfer is resumed, never committed"
            }
            try {
                commitCompleteTempFile(unit, tempFilePath, names)
            } finally {
                // The record belongs to the temp file. Once the file is gone — committed, or deleted
                // by a failed check — the record goes with it, last. A failure that left the file in
                // place keeps its record, so the retry is still checked against it.
                if (!fileExists(tempFilePath)) remove("$baseDir/${names.record}")
            }
        }
    }

    private fun commitCompleteTempFile(unit: DownloadUnit, tempFilePath: String, names: UnitFileLayout.Names) {
        verifySha256(tempFilePath, unit, names)
        if (unit.isDirectoryResource) {
            val targetDir: String = getResourcePath(unit)
            // Per-unit, not a single shared name — two archive units extracting at the same time
            // must not collide in one staging directory.
            val stagingDir = "$baseDir/${names.staging}"
            // Clean any leftover staging from a previous failed attempt
            NSFileManager.defaultManager.removeItemAtPath(stagingDir, error = null)
            try {
                extractZip(filePath = tempFilePath, targetDir = stagingDir)
                // Handle nested directory: ZIP may contain a root directory prefix
                // (e.g., entries like "pages/page001.webp" instead of "page001.webp"),
                // causing files to land in staging/pages/ instead of staging/.
                if (!fileExists("$stagingDir/${unit.archiveMarker()}")) {
                    val entries: List<String> = NSFileManager.defaultManager
                        .contentsOfDirectoryAtPath(stagingDir, error = null)
                        ?.filterIsInstance<String>()
                        ?: emptyList()
                    if (entries.size == 1) {
                        val nestedDir = "$stagingDir/${entries[0]}"
                        if (fileExists("$nestedDir/${unit.archiveMarker()}")) {
                            logger.i { "Detected nested directory '${entries[0]}', moving contents up" }
                            val nestedContents: List<String> = NSFileManager.defaultManager
                                .contentsOfDirectoryAtPath(nestedDir, error = null)
                                ?.filterIsInstance<String>()
                                ?: emptyList()
                            for (name: String in nestedContents) {
                                NSFileManager.defaultManager.moveItemAtPath(
                                    "$nestedDir/$name",
                                    toPath = "$stagingDir/$name",
                                    error = null,
                                )
                            }
                            NSFileManager.defaultManager.removeItemAtPath(
                                nestedDir,
                                error = null
                            )
                        }
                    }
                }
                // Atomic swap: remove old target, rename staging to target
                NSFileManager.defaultManager.removeItemAtPath(targetDir, error = null)
                // moveItemAtPath and copyItemAtPath both fail when the parent is missing, which a
                // nested relativePath (e.g. "archives/pages") leaves it on first use.
                createParentDirectory(targetDir)
                val moved: Boolean = NSFileManager.defaultManager.moveItemAtPath(
                    srcPath = stagingDir,
                    toPath = targetDir,
                    error = null,
                )
                if (!moved) {
                    // Fallback: copy + delete. A failed copy must not be followed by the deletes: the
                    // staging directory and the temp file are all that is left of the download.
                    val copied: Boolean = NSFileManager.defaultManager
                        .copyItemAtPath(stagingDir, targetDir, null)
                    if (!copied) {
                        NSFileManager.defaultManager.removeItemAtPath(targetDir, error = null)
                        throw IllegalStateException("Could not move the extracted $unit into place")
                    }
                    NSFileManager.defaultManager.removeItemAtPath(stagingDir, error = null)
                }
            } catch (e: Exception) {
                NSFileManager.defaultManager.removeItemAtPath(stagingDir, error = null)
                throw e
            }
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
        } else {
            (unit.format as? ResourceFormat.SqliteDatabase)?.let { format: ResourceFormat.SqliteDatabase ->
                verifySqlite(tempFilePath, format)
            }
            moveFile(tempFilePath = tempFilePath, destinationPath = getResourcePath(unit))
        }
    }

    /**
     * Checks the file at [tempFilePath] against every hash expected of it — [DownloadUnit.sha256]
     * and the one recorded by [beginTempFile] — hashing the file once, and only when there is
     * something to compare. Deletes the file and throws [ResourceIntegrityException] on a mismatch
     * or on a record that cannot be read (never "no check"). A unit whose own
     * [DownloadUnit.sha256] getter throws is the host's mistake, not the download's, and surfaces
     * as that exception instead so it is not answered with a pointless re-download.
     */
    private fun verifySha256(tempFilePath: String, unit: DownloadUnit, names: UnitFileLayout.Names) {
        checkExpectations(
            unit = unit,
            unitSha256 = unit.sha256,
            recorded = readExpectation(unit, names),
            logger = logger,
            computeHex = { sha256Hex(tempFilePath) },
            deleteTempFile = { remove(tempFilePath) },
        )
    }

    /** Streams the file at [path] through CommonCrypto's SHA-256, never holding it in memory. */
    private fun sha256Hex(path: String): String = memScoped {
        val context: CC_SHA256_CTX = alloc()
        CC_SHA256_Init(context.ptr)
        val file: CPointer<FILE> = fopen(path, "rb")
            ?: throw IllegalStateException("Cannot open $path to hash it")
        try {
            val buffer = ByteArray(WRITE_BUFFER_SIZE)
            buffer.usePinned { pinned ->
                while (true) {
                    val read: Long = fread(
                        pinned.addressOf(0),
                        1u.convert(),
                        buffer.size.convert(),
                        file,
                    ).toLong()
                    if (read <= 0L) break
                    CC_SHA256_Update(context.ptr, pinned.addressOf(0), read.convert())
                }
            }
            // fread answers 0 for end of file and for an I/O error alike; an error mid-file must not
            // be reported as a hash mismatch.
            check(ferror(file) == 0) { "I/O error while hashing $path" }
        } finally {
            fclose(file)
        }
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { pinned -> CC_SHA256_Final(pinned.addressOf(0), context.ptr) }
        digest.asByteArray().toLowerHex()
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
    private fun verifySqlite(tempFilePath: String, format: ResourceFormat.SqliteDatabase) {
        val counts: Pair<Int, Int?>? = try {
            val connection: SQLiteConnection = BundledSQLiteDriver().open(tempFilePath)
            try {
                // Opening alone proves nothing: SQLite reads the header lazily, so bytes that are
                // not a database open fine and fail only at the first read. Read the schema.
                queryInt(connection, "SELECT COUNT(*) FROM sqlite_master")
                val table: String? = format.rowCountTable
                val metaKey: String? = format.declaredRowCountMetaKey
                if (table == null || metaKey == null) {
                    null
                } else {
                    val actual: Int = queryInt(connection, "SELECT COUNT(*) FROM $table") ?: -1
                    val declared: Int? =
                        queryText(connection, "SELECT value FROM meta WHERE key = '$metaKey'")
                            ?.toIntOrNull()
                    actual to declared
                }
            } finally {
                connection.close()
            }
        } catch (e: SQLiteException) {
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: not a valid database (${e.message})",
                e,
            )
        }
        val (actualCount: Int, declaredCount: Int?) = counts ?: return
        if (declaredCount == null || actualCount != declaredCount) {
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: " +
                    "$actualCount rows, meta declares $declaredCount",
            )
        }
    }

    private fun queryInt(connection: SQLiteConnection, sql: String): Int? {
        val statement: SQLiteStatement = connection.prepare(sql)
        return try {
            if (statement.step()) statement.getInt(0) else null
        } finally {
            statement.close()
        }
    }

    private fun queryText(connection: SQLiteConnection, sql: String): String? {
        val statement: SQLiteStatement = connection.prepare(sql)
        return try {
            if (statement.step()) statement.getText(0) else null
        } finally {
            statement.close()
        }
    }

    private fun moveFile(tempFilePath: String, destinationPath: String) {
        val parentDir: String = destinationPath.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = parentDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        // POSIX rename replaces an existing destination atomically, so a resource already in place
        // is never missing, not even for a moment. The old remove-then-move removed it first, and a
        // commit whose source was already gone then deleted a committed resource and failed.
        if (rename(tempFilePath, destinationPath) != 0) {
            // Only the fallback below removes the destination, so it must first be sure the source
            // is still there to replace it with.
            check(fileExists(tempFilePath)) {
                "The downloaded file at $tempFilePath vanished before it could be moved into place"
            }
            NSFileManager.defaultManager.removeItemAtPath(destinationPath, error = null)
            // Fallback: copy + delete. A failed copy must not delete the only copy of the download.
            val copied: Boolean = NSFileManager.defaultManager
                .copyItemAtPath(tempFilePath, destinationPath, null)
            if (!copied) {
                NSFileManager.defaultManager.removeItemAtPath(destinationPath, error = null)
                throw IllegalStateException("Could not move the downloaded file to $destinationPath")
            }
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
        }
        logger.i { "Saved resource to $destinationPath" }
    }

    // -- Streaming ZIP extraction -----------------------------------------------------------
    // Reads the ZIP file via POSIX fread in chunks instead of loading the entire archive into
    // memory. This prevents OOM on a large archive.

    /**
     * Extracts a ZIP archive at [filePath] into [targetDir] using streaming I/O.
     *
     * Parses ZIP local file headers sequentially via POSIX fread and uses zlib's streaming inflate
     * for DEFLATED entries. STORED entries are streamed directly. Never loads the entire archive
     * into memory.
     */
    private fun extractZip(filePath: String, targetDir: String) {
        logger.i { "Extracting ZIP to $targetDir..." }
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = targetDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        val zipFile: CPointer<FILE> = fopen(filePath, "rb")
            ?: error("Failed to open ZIP file: $filePath")

        val headerBuf = ByteArray(30)
        val ioBuf = ByteArray(WRITE_BUFFER_SIZE)

        try {
            while (true) {
                // Read local file header (30 bytes)
                val headerRead: Int = readExact(zipFile, headerBuf, 30, ioBuf)
                if (headerRead < 30) break

                // Check local file header signature: PK\x03\x04
                if (headerBuf[0].toInt() and 0xFF != 0x50 ||
                    headerBuf[1].toInt() and 0xFF != 0x4B ||
                    headerBuf[2].toInt() and 0xFF != 0x03 ||
                    headerBuf[3].toInt() and 0xFF != 0x04
                ) {
                    break // No more local file headers (reached central directory)
                }

                val compressionMethod: Int = readUInt16LE(headerBuf, 8)
                val compressedSize: Long = readUInt32LE(headerBuf, 18)
                val uncompressedSize: Long = readUInt32LE(headerBuf, 22)
                val fileNameLength: Int = readUInt16LE(headerBuf, 26)
                val extraFieldLength: Int = readUInt16LE(headerBuf, 28)

                // Read file name
                val fileNameBuf = ByteArray(fileNameLength)
                val nameRead: Int = readExact(zipFile, fileNameBuf, fileNameLength, ioBuf)
                if (nameRead < fileNameLength) {
                    logger.e { "ZIP corrupted: could not read file name" }
                    break
                }
                val fileName: String = fileNameBuf.decodeToString()

                // Skip extra field
                if (extraFieldLength > 0) {
                    fseek(zipFile, extraFieldLength.toLong().convert(), SEEK_CUR)
                }

                // Skip directories
                if (fileName.endsWith("/")) {
                    fseek(zipFile, compressedSize.convert(), SEEK_CUR)
                    continue
                }

                // Zip slip protection
                if (fileName.contains("..")) {
                    logger.d { "Skipping suspicious entry: $fileName" }
                    fseek(zipFile, compressedSize.convert(), SEEK_CUR)
                    continue
                }

                val outputPath = "$targetDir/$fileName"
                val parentDir: String = outputPath.substringBeforeLast('/')
                NSFileManager.defaultManager.createDirectoryAtPath(
                    path = parentDir,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )

                when (compressionMethod) {
                    0 -> {
                        // STORED — stream directly to output
                        streamStoredEntry(zipFile, outputPath, compressedSize.toInt(), ioBuf)
                    }

                    8 -> {
                        // DEFLATED — streaming zlib inflate
                        streamDeflatedEntry(
                            zipFile, outputPath,
                            compressedSize.toInt(), uncompressedSize.toInt(), ioBuf,
                        )
                    }

                    else -> {
                        logger.d { "Skipping entry with unsupported compression method $compressionMethod: $fileName" }
                        fseek(zipFile, compressedSize.convert(), SEEK_CUR)
                        continue
                    }
                }

                logger.d { "Extracted: $fileName" }
            }
        } finally {
            fclose(zipFile)
        }
        logger.i { "ZIP extraction complete" }
    }

    /** Reads exactly [count] bytes from [file] into [dest]. Returns actual bytes read. */
    private fun readExact(
        file: CPointer<FILE>,
        dest: ByteArray,
        count: Int,
        @Suppress("UNUSED_PARAMETER") ioBuf: ByteArray,
    ): Int {
        var totalRead = 0
        dest.usePinned { pinned ->
            while (totalRead < count) {
                val remaining: Int = count - totalRead
                val n: Long = fread(
                    pinned.addressOf(totalRead),
                    1u.convert(),
                    remaining.convert(),
                    file,
                ).toLong()
                if (n <= 0) break
                totalRead += n.toInt()
            }
        }
        return totalRead
    }

    /** Streams a STORED ZIP entry from [zipFile] to [outputPath]. */
    private fun streamStoredEntry(
        zipFile: CPointer<FILE>,
        outputPath: String,
        size: Int,
        ioBuf: ByteArray,
    ) {
        val outFile: CPointer<FILE> = fopen(outputPath, "wb")
            ?: error("Failed to create output file: $outputPath")
        try {
            var remaining: Int = size
            ioBuf.usePinned { pinned ->
                while (remaining > 0) {
                    val toRead: Int = minOf(remaining, ioBuf.size)
                    val n: Long = fread(
                        pinned.addressOf(0), 1u.convert(), toRead.convert(), zipFile,
                    ).toLong()
                    if (n <= 0) break
                    fwrite(pinned.addressOf(0), 1u.convert(), n.convert(), outFile)
                    remaining -= n.toInt()
                }
            }
        } finally {
            fclose(outFile)
        }
    }

    /**
     * Streams a DEFLATED ZIP entry from [zipFile] to [outputPath] using zlib.
     * Reads compressed data in chunks and inflates incrementally.
     */
    private fun streamDeflatedEntry(
        zipFile: CPointer<FILE>,
        outputPath: String,
        compressedSize: Int,
        @Suppress("UNUSED_PARAMETER") uncompressedSize: Int,
        ioBuf: ByteArray,
    ) {
        val outFile: CPointer<FILE> = fopen(outputPath, "wb")
            ?: error("Failed to create output file: $outputPath")
        val outBuf = ByteArray(WRITE_BUFFER_SIZE)

        try {
            memScoped {
                val stream: z_stream = alloc()
                // -15 = raw deflate (no zlib/gzip header)
                val initResult: Int = inflateInit2(stream.ptr, -15)
                if (initResult != Z_OK) {
                    error("inflateInit2 failed with code $initResult")
                }

                var compressedRemaining: Int = compressedSize
                var inflateResult: Int = Z_OK

                ioBuf.usePinned { pinnedIn ->
                    outBuf.usePinned { pinnedOut ->
                        while (compressedRemaining > 0 && inflateResult != Z_STREAM_END) {
                            val toRead: Int = minOf(compressedRemaining, ioBuf.size)
                            val n: Long = fread(
                                pinnedIn.addressOf(0), 1u.convert(), toRead.convert(), zipFile,
                            ).toLong()
                            if (n <= 0) break
                            compressedRemaining -= n.toInt()

                            stream.next_in = pinnedIn.addressOf(0).reinterpret()
                            stream.avail_in = n.toUInt()

                            // Inflate all available input
                            while (stream.avail_in > 0u && inflateResult != Z_STREAM_END) {
                                stream.next_out = pinnedOut.addressOf(0).reinterpret()
                                stream.avail_out = outBuf.size.toUInt()

                                inflateResult = zlibInflate(stream.ptr, Z_NO_FLUSH)
                                if (inflateResult != Z_OK && inflateResult != Z_STREAM_END) {
                                    inflateEnd(stream.ptr)
                                    error("inflate failed with code $inflateResult")
                                }

                                val produced: Int = outBuf.size - stream.avail_out.toInt()
                                if (produced > 0) {
                                    fwrite(
                                        pinnedOut.addressOf(0),
                                        1u.convert(),
                                        produced.convert(),
                                        outFile,
                                    )
                                }
                            }
                        }
                    }
                }

                inflateEnd(stream.ptr)
            }
        } finally {
            fclose(outFile)
        }
    }

    private fun readUInt16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Reads an unsigned 32-bit little-endian integer as [Long] to avoid sign issues
     * with values where the high bit is set (e.g., sizes > 2 GB).
     */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24))
    }

    private fun excludeFromBackup(path: String) {
        val url: NSURL = NSURL.fileURLWithPath(path)
        url.setResourceValue(
            value = true,
            forKey = NSURLIsExcludedFromBackupKey,
            error = null,
        )
    }

    private fun fileExists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)
}
