package io.github.jamal_wia.kmptoolkit.downloader

import androidx.sqlite.SQLiteConnection
import io.github.jamal_wia.kmptoolkit.files.SqliteFiles
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
import kotlinx.cinterop.UByteVar
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
import kotlin.text.CharacterCodingException
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
import platform.Foundation.decomposedStringWithCanonicalMapping
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSUserDomainMask
import platform.posix.EISDIR
import platform.posix.ENAMETOOLONG
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.FILE
import platform.posix.SEEK_CUR
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.ftell
import platform.posix.fclose
import platform.posix.errno
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
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.crc32
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
) : DownloaderStorage, RelativePathChecking, TransferLockScope {

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
    private val volumeFoldsCase: Boolean by lazy { caseInsensitiveOverride ?: !volumeIsCaseSensitive(baseDir) }

    private val layout: UnitFileLayout by lazy { UnitFileLayout(caseInsensitive = volumeFoldsCase) }

    override val transferLockScope: String get() = lockScope

    override fun transferLockKey(unit: DownloadUnit): String = layout.lockKey(unit)

    /** [baseDir] as the volume sees it: spelled in one case on a volume that folds case, so every spelling shares the locks. */
    private val lockScope: String by lazy { layout.lockScope(baseDir) }

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
            markerIsFile(path, unit.archiveMarker())
        } else {
            fileExists(path)
        }
    }

    override fun relativePathViolation(unit: DownloadUnit): InvalidDownloadUnitException.Reason? =
        (layout.inspectRelativePath(unit) as? UnitFileLayout.PathInspection.Rejected)?.reason

    override fun getResourcePath(unit: DownloadUnit): String {
        val accepted: UnitFileLayout.PathInspection.Accepted =
            when (val inspection: UnitFileLayout.PathInspection = layout.inspectRelativePath(unit)) {
                is UnitFileLayout.PathInspection.Rejected -> throw InvalidDownloadUnitException(unit, inspection.reason)
                is UnitFileLayout.PathInspection.Accepted -> inspection
            }
        if (accepted.underTmp && tmpPathWarnings.isFirst(unit.relativePath)) {
            logger.w { "relativePath '${unit.relativePath}' of $unit is under tmp/, which the storage reserves" }
        }
        // Every file operation uses this path, never unit.relativePath: the check and the use agree.
        val normalizedPath: String = accepted.normalizedPath
        return "$baseDir/$normalizedPath"
    }

    override fun getTempFilePath(unit: DownloadUnit): String = "$baseDir/${names(unit).partial}"

    override fun tempFileState(unit: DownloadUnit): TempFileState {
        val names: UnitFileLayout.Names = names(unit)
        // The partial file is read BEFORE the complete one: marking complete moves partial into
        // complete, so a partial that was absent at the first read was already complete at the
        // second. The other order can miss both files while one move lands between the reads.
        val partialSize: Long = sizeOf("$baseDir/${names.partial}")
        return when {
            fileExists("$baseDir/${names.complete}") -> TempFileState.Complete
            partialSize > 0L -> TempFileState.Partial
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
        // place); the next cancel or the next commit reclaims it. A merely busy lock registry
        // is retried, never taken for a running commit. Under the lock the tree is only renamed away; the recursive delete
        // runs after the lock is released so it cannot stall other units.
        UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = lockScope,
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
        UnitCommitLocks.withUnitLock(lockScope, layout.lockKey(unit)) {
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
                if (!markerIsFile(stagingDir, unit.archiveMarker())) moveSingleRootUp(stagingDir, unit)
                // The tree must be what the unit declares before anything at the final path is touched:
                // a directory without its marker is not "ready", and it must not replace one that is.
                if (!markerIsFile(stagingDir, unit.archiveMarker())) {
                    throw ResourceIntegrityException("Archive of $unit has no '${unit.archiveMarker()}' after extraction")
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
            } catch (e: ResourceIntegrityException) {
                // Bytes that are not what the unit declares: the temp file is worthless, and one fresh download
                // is the fix. Everything above ran before the committed resource was touched.
                NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
                NSFileManager.defaultManager.removeItemAtPath(stagingDir, error = null)
                throw e
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
     * Moves the contents of the archive's single root directory up into [stagingDir] when the marker is not at the top.
     * Candidates are the non-empty top-level directories (files and empty directories never are); it acts only for
     * exactly one candidate that holds the marker. The root is first renamed to a free sibling name, so a child named
     * like the root lands where it belongs; every move is checked, a child replaces only an empty directory of its
     * name, and one that meets a file or a non-empty directory fails the commit. The root's shell is removed only when
     * it is empty: a directory that still holds data is never deleted.
     */
    private fun moveSingleRootUp(stagingDir: String, unit: DownloadUnit) {
        val manager: NSFileManager = NSFileManager.defaultManager
        val topLevel: List<String> = manager.contentsOfDirectoryAtPath(stagingDir, error = null)
            ?.filterIsInstance<String>() ?: emptyList()
        val candidates: List<String> = topLevel.filter { name: String -> isNonEmptyDirectory("$stagingDir/$name") }
        if (candidates.size != 1) return
        val rootName: String = candidates[0]
        val root = "$stagingDir/$rootName"
        if (!markerIsFile(root, unit.archiveMarker())) return
        logger.i { "Detected nested directory '$rootName', moving contents up" }
        val children: List<String> = manager.contentsOfDirectoryAtPath(root, error = null)
            ?.filterIsInstance<String>() ?: emptyList()
        val taken: Set<String> = (topLevel + children).map { name: String -> name.lowercase() }.toSet()
        var suffix = 0
        var temporaryName: String = TEMPORARY_ROOT_NAME
        while (temporaryName.lowercase() in taken) temporaryName = "$TEMPORARY_ROOT_NAME-${++suffix}"
        val temporary = "$stagingDir/$temporaryName"
        check(manager.moveItemAtPath(root, toPath = temporary, error = null)) {
            "Could not move the root directory '$rootName' of $unit aside"
        }
        for (child: String in children) {
            val destination = "$stagingDir/$child"
            if (fileExists(destination)) {
                if (isEmptyDirectory(destination)) {
                    check(manager.removeItemAtPath(destination, error = null)) {
                        "Could not replace the empty directory '$child' of $unit"
                    }
                } else {
                    throw ResourceIntegrityException(
                        "Archive of $unit: '$child' of root '$rootName' collides with a top-level entry",
                    )
                }
            }
            check(manager.moveItemAtPath("$temporary/$child", toPath = destination, error = null)) {
                "Could not move '$child' of the root directory of $unit up"
            }
        }
        // Never delete a directory that still holds data.
        check(isEmptyDirectory(temporary)) { "The root directory of $unit still holds entries after moving its contents up" }
        check(manager.removeItemAtPath(temporary, error = null)) { "Could not remove the emptied root directory of $unit" }
    }

    private fun isRegularFile(path: String): Boolean =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeRegular

    private fun isDirectoryAt(path: String): Boolean =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeDirectory

    /**
     * True when [marker] (relative to [directory]) is a regular file spelled exactly like that. A directory of the
     * marker's name does not count, and neither does `MARKER.TXT` for `marker.txt` on a volume that folds case, where
     * `fileExists` alone would say yes (a case-sensitive volume says no). Unicode normalization is not told apart: the
     * volume treats the spellings as one name for every read too.
     */
    private fun markerIsFile(directory: String, marker: String): Boolean {
        if (!isRegularFile("$directory/$marker")) return false
        if (!volumeFoldsCase) return true
        var current: String = directory
        for (segment: String in marker.split('/').filter { part: String -> part.isNotEmpty() && part != "." }) {
            val names: List<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(current, error = null)
                ?.filterIsInstance<String>() ?: return false
            val wanted: String = canonicalForm(segment)
            val found: String = names.firstOrNull { name: String -> canonicalForm(name) == wanted } ?: return false
            current = "$current/$found"
        }
        return true
    }

    private fun canonicalForm(name: String): String = (name as NSString).decomposedStringWithCanonicalMapping

    /** True when [path] is a directory with something in it. */
    private fun isNonEmptyDirectory(path: String): Boolean =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)?.isNotEmpty() == true

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
     * The file must start with the 16-byte SQLite header and be at least 100 bytes long (an empty or cut body
     * fails). Opening it is the next check — bytes that are not a database fail here. When the format
     * also names a table and a `meta` key, the real row count must match the count the file itself
     * declares, which catches a truncated download that still happens to parse. Which table and
     * which key those are is the host's own domain knowledge, which is why they are values on the
     * unit's [ResourceFormat.SqliteDatabase] rather than anything this library assumes: the table is used as a
     * name (resolved through `sqlite_master` and quoted) and the key is bound as a parameter.
     *
     * Deletes the temp file and throws [ResourceIntegrityException] on any failure: nothing invalid
     * may reach the final path, not even momentarily.
     */
    private fun verifySqlite(tempFilePath: String, format: ResourceFormat.SqliteDatabase) {
        requireSqliteHeader(tempFilePath)
        // A leftover -wal would be replayed into this file, and opening it may leave its own: neither stays.
        deleteSqliteSidecars(tempFilePath)
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
                    // The table is a name: resolved through sqlite_master and quoted. A value that names no table
                    // is used as SQL text, as 3.0.0 did (a pre-quoted or schema-qualified name keeps working).
                    // future major release: drop the verbatim fallback
                    val stored: String? = queryText(connection, SQLITE_RESOLVE_TABLE_SQL, table)
                    val countSql: String = if (stored != null) countRowsSql(stored) else countRowsVerbatimSql(table)
                    val actual: Int = queryInt(connection, countSql) ?: -1
                    val declared: Int? = queryText(connection, SQLITE_META_VALUE_SQL, metaKey)?.toIntOrNull()
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
        } finally {
            deleteSqliteSidecars(tempFilePath)
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

    /** Removes the write-ahead log, shared-memory and journal files SQLite keeps next to [databasePath]. */
    private fun deleteSqliteSidecars(databasePath: String) {
        for (sidecar: String in SqliteFiles.sidecarsOf(databasePath)) NSFileManager.defaultManager.removeItemAtPath(sidecar, error = null)
    }

    /** Deletes the file and throws when it does not start with the 16-byte SQLite header or is shorter than 100 bytes. */
    private fun requireSqliteHeader(tempFilePath: String) {
        val head = ByteArray(SQLITE_MIN_BYTES)
        val file: CPointer<FILE>? = fopen(tempFilePath, "rb")
        val read: Int = if (file == null) {
            0
        } else {
            try {
                readExact(file, head, head.size)
            } finally {
                fclose(file)
            }
        }
        // `read` is capped at 100: all hasSqliteHeader needs to know is whether the file is shorter than that.
        if (!hasSqliteHeader(head, read.toLong())) {
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: not a valid database (no SQLite header)",
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

    private fun queryText(connection: SQLiteConnection, sql: String, argument: String): String? {
        val statement: SQLiteStatement = connection.prepare(sql)
        return try {
            statement.bindText(1, argument)
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
    // memory. This prevents OOM on a large archive. The walk is the one java.util.zip.ZipInputStream
    // makes on Android: local headers in order, the central directory never read, so for every archive
    // the two platforms extract the same tree or fail.

    /** True when [path] is a directory with nothing in it. */
    private fun isEmptyDirectory(path: String): Boolean =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, error = null)?.isEmpty() == true

    /** Where one entry's bytes go: counts them, keeps their CRC-32 and writes them to [file] unless it is null. */
    private class EntrySink(val file: CPointer<FILE>?, val path: String) {
        var crc: ULong = 0u
        var size: Long = 0L
    }

    /** Logs and throws: the archive cannot be extracted as delivered (bytes that are not what a valid archive holds). */
    private fun zipFail(entry: String?, reason: String): Nothing {
        val message: String = if (entry == null) "ZIP archive: $reason" else "ZIP entry '$entry': $reason"
        logger.e { message }
        throw ResourceIntegrityException(message)
    }

    /**
     * Extracts a ZIP archive at [filePath] into [targetDir] using streaming I/O.
     *
     * Parses ZIP local file headers sequentially via POSIX fread and uses zlib's streaming inflate
     * for DEFLATED entries. STORED entries are streamed directly. Never loads the entire archive
     * into memory.
     *
     * Every entry is extracted or the call fails with [ResourceIntegrityException]; nothing is skipped.
     * A truncated header, name, data or data descriptor, a size or CRC-32 that disagrees with the
     * bytes, a stream that does not end where it declares, an unsupported method, an encrypted or
     * ZIP64 entry, a name that leaves [targetDir], two entries the volume would store as one (names that differ only
     * in case or Unicode normalization, or one name that is a file and a directory) and a name or path the volume cannot
     * hold all fail. A full disk is [IllegalStateException]: the bytes were fine.
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
        try {
            requireEndRecord(zipFile)
        } catch (e: Throwable) {
            fclose(zipFile)
            throw e
        }

        val entryLayout = ZipEntryLayout()
        val headerBuf = ByteArray(LOCAL_HEADER_SIZE)
        val ioBuf = ByteArray(WRITE_BUFFER_SIZE)
        val outBuf = ByteArray(WRITE_BUFFER_SIZE)

        try {
            while (true) {
                val headerRead: Int = readExact(zipFile, headerBuf, LOCAL_HEADER_SIZE)
                if (headerRead == 0) break // the archive ends right after an entry
                // A remainder shorter than a header ends the walk, like ZipInputStream. The end record was
                // checked before the walk, so a cut archive never gets here.
                if (headerRead < LOCAL_HEADER_SIZE) break
                // A signature other than a local file header (the central directory) ends the entries.
                if (!hasLocalHeaderSignature(headerBuf)) break

                val flags: Int = readUInt16LE(headerBuf, 6)
                val compressionMethod: Int = readUInt16LE(headerBuf, 8)
                val headerCrc: Long = readUInt32LE(headerBuf, 14)
                val compressedSize: Long = readUInt32LE(headerBuf, 18)
                val uncompressedSize: Long = readUInt32LE(headerBuf, 22)
                val fileNameLength: Int = readUInt16LE(headerBuf, 26)
                val extraFieldLength: Int = readUInt16LE(headerBuf, 28)
                val hasDescriptor: Boolean = flags and FLAG_DATA_DESCRIPTOR != 0

                val fileNameBuf = ByteArray(fileNameLength)
                if (readExact(zipFile, fileNameBuf, fileNameLength) < fileNameLength) {
                    zipFail(null, "the archive ends inside an entry name")
                }
                // Names are UTF-8 whether or not the archive sets the flag; anything else is not extracted as delivered.
                val fileName: String = try {
                    fileNameBuf.decodeToString(throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    zipFail(null, "an entry name is not valid UTF-8")
                }

                // Not interpreted (the ZIP64 field is never needed: see below), but a cut must not pass.
                if (extraFieldLength > 0 && readExact(zipFile, ByteArray(extraFieldLength), extraFieldLength) < extraFieldLength) {
                    zipFail(fileName, "the archive ends inside the extra field")
                }

                if (flags and FLAG_ENCRYPTED != 0) zipFail(fileName, "encrypted entries are not supported")
                if (compressionMethod != METHOD_STORED && compressionMethod != METHOD_DEFLATED) {
                    zipFail(fileName, "unsupported compression method $compressionMethod")
                }
                if (hasDescriptor && compressionMethod != METHOD_DEFLATED) {
                    zipFail(fileName, "only DEFLATED entries can have a data descriptor")
                }
                if (!hasDescriptor && (compressedSize == ZIP64_MARKER || uncompressedSize == ZIP64_MARKER)) {
                    zipFail(fileName, "ZIP64 entries are not supported on iOS")
                }
                if (fileName.indexOf('\u0000') >= 0) zipFail(fileName, "the name contains a NUL character")

                val isDirectory: Boolean = fileName.endsWith("/")
                val segments: List<String> = resolveEntrySegments(fileName)
                    ?: zipFail(fileName, "ZIP entry attempts path traversal")
                if (segments.isEmpty() && !isDirectory) zipFail(fileName, "the name has no file part")
                if (segments.isNotEmpty()) {
                    val conflict: String? = entryLayout.conflict(segments, isDirectory) { path: String ->
                        fileExists("$targetDir/$path")
                    }
                    if (conflict != null) zipFail(fileName, conflict)
                }
                val outputPath: String = if (segments.isEmpty()) targetDir else "$targetDir/${segments.joinToString("/")}"

                // A directory entry is created (an empty directory is part of the tree) under the same rule
                // as files; a `./` or `/` entry is the target itself. Its data, if any, is still read and checked.
                var outFile: CPointer<FILE>? = null
                if (isDirectory) {
                    NSFileManager.defaultManager.createDirectoryAtPath(
                        path = outputPath,
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null,
                    )
                    if (!isDirectoryAt(outputPath)) zipFail(fileName, "the directory cannot be created on this volume")
                } else {
                    NSFileManager.defaultManager.createDirectoryAtPath(
                        path = outputPath.substringBeforeLast('/'),
                        withIntermediateDirectories = true,
                        attributes = null,
                        error = null,
                    )
                    outFile = fopen(outputPath, "wb") ?: run {
                        val code: Int = errno
                        // A name or path the volume cannot hold is the archive's fault; a full disk is not.
                        if (code == ENAMETOOLONG || code == ENOENT || code == ENOTDIR || code == EISDIR) {
                            zipFail(fileName, "the file cannot be created on this volume (errno $code)")
                        }
                        error("Failed to create output file: $outputPath (errno $code)")
                    }
                }
                val sink = EntrySink(outFile, outputPath)
                var completed = false
                try {
                    if (compressionMethod == METHOD_STORED) {
                        if (compressedSize != uncompressedSize) {
                            zipFail(fileName, "invalid entry size (declared $uncompressedSize, stored $compressedSize)")
                        }
                        streamStoredEntry(zipFile, sink, compressedSize, ioBuf, fileName)
                        checkEntry(fileName, sink, headerCrc, uncompressedSize)
                    } else if (hasDescriptor) {
                        val consumed: Long = streamDeflatedEntry(zipFile, sink, null, ioBuf, outBuf, fileName)
                        checkDescriptor(zipFile, fileName, sink, consumed)
                    } else {
                        val consumed: Long = streamDeflatedEntry(zipFile, sink, compressedSize, ioBuf, outBuf, fileName)
                        if (consumed != compressedSize) {
                            zipFail(fileName, "invalid compressed size (declared $compressedSize, used $consumed)")
                        }
                        checkEntry(fileName, sink, headerCrc, uncompressedSize)
                    }
                    completed = true
                } finally {
                    // A failed close is a lost write. When the entry already failed, that failure is the one to report.
                    if (outFile != null && fclose(outFile) != 0 && completed) {
                        throw IllegalStateException("Failed to write $outputPath")
                    }
                }
                logger.d { "Extracted: $fileName" }
            }
        } finally {
            fclose(zipFile)
        }
        logger.i { "ZIP extraction complete" }
    }

    private fun hasLocalHeaderSignature(buf: ByteArray): Boolean =
        buf[0].toInt() and 0xFF == 0x50 &&
            buf[1].toInt() and 0xFF == 0x4B &&
            buf[2].toInt() and 0xFF == 0x03 &&
            buf[3].toInt() and 0xFF == 0x04

    /** Fails when the bytes written for [entry] are not the CRC-32 and size its header declared. */
    private fun checkEntry(entry: String, sink: EntrySink, expectedCrc: Long, expectedSize: Long) {
        if (sink.size != expectedSize) {
            zipFail(entry, "invalid entry size (declared $expectedSize, got ${sink.size} bytes)")
        }
        if (sink.crc.toLong() and MASK_32 != expectedCrc) zipFail(entry, "invalid entry CRC-32")
    }

    /**
     * Reads the data descriptor that follows a streamed DEFLATED entry (an optional `PK\u0007\u0008` signature,
     * the CRC-32, then the compressed and uncompressed sizes) and compares it with what was read and written.
     * A ZIP64 descriptor (8-byte sizes) does not match and fails, with the other ZIP64 layouts.
     */
    private fun checkDescriptor(zipFile: CPointer<FILE>, entry: String, sink: EntrySink, consumed: Long) {
        val buf = ByteArray(16)
        if (readExact(zipFile, buf, 4) < 4) zipFail(entry, "the archive ends inside the data descriptor")
        val crc: Long
        val compressed: Long
        val uncompressed: Long
        if (readUInt32LE(buf, 0) == DESCRIPTOR_SIGNATURE) {
            if (readExact(zipFile, buf, 12) < 12) zipFail(entry, "the archive ends inside the data descriptor")
            crc = readUInt32LE(buf, 0)
            compressed = readUInt32LE(buf, 4)
            uncompressed = readUInt32LE(buf, 8)
        } else {
            // No signature: those four bytes are the CRC-32.
            crc = readUInt32LE(buf, 0)
            if (readExact(zipFile, buf, 8) < 8) zipFail(entry, "the archive ends inside the data descriptor")
            compressed = readUInt32LE(buf, 0)
            uncompressed = readUInt32LE(buf, 4)
        }
        if (sink.crc.toLong() and MASK_32 != crc) zipFail(entry, "invalid entry CRC-32 (data descriptor)")
        if (consumed != compressed) {
            zipFail(entry, "invalid compressed size (descriptor $compressed, used $consumed)")
        }
        if (sink.size != uncompressed) {
            zipFail(entry, "invalid entry size (descriptor $uncompressed, got ${sink.size} bytes)")
        }
    }

    /**
     * Fails with [ResourceIntegrityException] when the archive has no self-consistent end of central directory record
     * ([hasZipEndRecord]): a file cut anywhere before it, its central directory included, is a half-written archive.
     * Decided from the file alone before the walk, so both platforms give one archive one answer. Leaves [file] at its start.
     */
    private fun requireEndRecord(file: CPointer<FILE>) {
        check(fseek(file, 0, SEEK_END) == 0) { "Failed to seek in the ZIP archive" }
        val length: Long = ftell(file)
        check(length >= 0) { "Failed to measure the ZIP archive" }
        val size: Int = minOf(length, ZIP_END_RECORD_WINDOW).toInt()
        val tail = ByteArray(size)
        check(fseek(file, (length - size).convert(), SEEK_SET) == 0) { "Failed to seek in the ZIP archive" }
        check(readExact(file, tail, size) == size) { "Failed to read the end of the ZIP archive" }
        check(fseek(file, 0, SEEK_SET) == 0) { "Failed to seek in the ZIP archive" }
        if (!hasZipEndRecord(tail, length - size)) {
            zipFail(null, "the archive has no end of central directory record")
        }
    }

    /** Reads exactly [count] bytes from [file] into [dest]. Returns actual bytes read, fewer only at the end of the file. */
    private fun readExact(file: CPointer<FILE>, dest: ByteArray, count: Int): Int {
        var totalRead = 0
        if (count == 0) return 0
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

    /** Takes [count] bytes of [data] into [sink]: counted, checksummed and (for a file entry) written; a short write throws. */
    private fun consume(sink: EntrySink, data: ByteArray, count: Int) {
        if (count == 0) return
        data.usePinned { pinned ->
            sink.crc = crc32(sink.crc, pinned.addressOf(0).reinterpret<UByteVar>(), count.convert())
            sink.size += count
            val file: CPointer<FILE>? = sink.file
            if (file != null) {
                val written: Long = fwrite(pinned.addressOf(0), 1u.convert(), count.convert(), file).toLong()
                if (written != count.toLong()) throw IllegalStateException("Failed to write ${sink.path}")
            }
        }
    }

    /** Streams a STORED ZIP entry of [size] bytes from [zipFile] into [sink]. */
    private fun streamStoredEntry(zipFile: CPointer<FILE>, sink: EntrySink, size: Long, ioBuf: ByteArray, entry: String) {
        var remaining: Long = size
        while (remaining > 0) {
            val toRead: Int = minOf(remaining, ioBuf.size.toLong()).toInt()
            if (readExact(zipFile, ioBuf, toRead) < toRead) zipFail(entry, "the archive ends inside the entry data")
            consume(sink, ioBuf, toRead)
            remaining -= toRead
        }
    }

    /**
     * Inflates one DEFLATED ZIP entry from [zipFile] into [sink] using zlib, reading compressed data in chunks.
     * Succeeds only when the deflate stream ends (`Z_STREAM_END`) and returns how many compressed bytes it used.
     * With a [budget] (the declared compressed size) no more than that is read, and a stream that has not ended
     * within it fails; without one (a streamed entry whose size follows the data) the unused part of the last
     * chunk is given back to the file, so the data descriptor can be read next.
     */
    private fun streamDeflatedEntry(
        zipFile: CPointer<FILE>,
        sink: EntrySink,
        budget: Long?,
        ioBuf: ByteArray,
        outBuf: ByteArray,
        entry: String,
    ): Long {
        var consumed = 0L
        memScoped {
            val stream: z_stream = alloc()
            // -15 = raw deflate (no zlib/gzip header)
            val initResult: Int = inflateInit2(stream.ptr, -15)
            if (initResult != Z_OK) {
                error("inflateInit2 failed with code $initResult")
            }
            try {
                var read = 0L
                var ended = false
                var needInput = true
                ioBuf.usePinned { pinnedIn ->
                    outBuf.usePinned { pinnedOut ->
                        while (!ended) {
                            if (needInput) {
                                val want: Int = if (budget == null) {
                                    ioBuf.size
                                } else {
                                    minOf(budget - read, ioBuf.size.toLong()).toInt()
                                }
                                if (want <= 0) zipFail(entry, "the deflate stream does not end within the declared compressed size")
                                val n: Long = fread(
                                    pinnedIn.addressOf(0), 1u.convert(), want.convert(), zipFile,
                                ).toLong()
                                if (n <= 0) zipFail(entry, "the archive ends inside the entry data")
                                read += n
                                stream.next_in = pinnedIn.addressOf(0).reinterpret()
                                stream.avail_in = n.toUInt()
                            }
                            stream.next_out = pinnedOut.addressOf(0).reinterpret()
                            stream.avail_out = outBuf.size.toUInt()
                            val result: Int = zlibInflate(stream.ptr, Z_NO_FLUSH)
                            // Z_BUF_ERROR with all input used and nothing pending only asks for more input.
                            if (result != Z_OK && result != Z_STREAM_END && !(result == Z_BUF_ERROR && stream.avail_in == 0u)) {
                                zipFail(entry, "the deflate stream is corrupt (inflate code $result)")
                            }
                            consume(sink, outBuf, outBuf.size - stream.avail_out.toInt())
                            if (result == Z_STREAM_END) ended = true
                            // Pending output (the buffer came back full) needs another call, not more input.
                            needInput = stream.avail_in == 0u && stream.avail_out != 0u
                        }
                    }
                }
                val unused: Long = stream.avail_in.toLong()
                if (budget == null && unused > 0 && fseek(zipFile, (-unused).convert(), SEEK_CUR) != 0) {
                    error("Failed to seek in the ZIP archive")
                }
                consumed = read - unused
            } finally {
                inflateEnd(stream.ptr)
            }
        }
        return consumed
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

private const val LOCAL_HEADER_SIZE: Int = 30
private const val SQLITE_MIN_BYTES: Int = 100
private const val FLAG_ENCRYPTED: Int = 0x0001
private const val FLAG_DATA_DESCRIPTOR: Int = 0x0008
private const val METHOD_STORED: Int = 0
private const val METHOD_DEFLATED: Int = 8
private const val ZIP64_MARKER: Long = 0xFFFFFFFFL
private const val MASK_32: Long = 0xFFFFFFFFL
private const val DESCRIPTOR_SIGNATURE: Long = 0x08074B50L

/** The sibling name the single root directory is moved to while its children go up; a free variant is chosen. */
private const val TEMPORARY_ROOT_NAME: String = ".kmptoolkit-up"
