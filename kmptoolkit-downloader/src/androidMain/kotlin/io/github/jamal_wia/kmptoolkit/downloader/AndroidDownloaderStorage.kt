package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.Companion.WRITE_BUFFER_SIZE
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.SqliteFiles
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
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
    /** The file-system primitives of tree walks and links; production uses the platform default. */
    private val files: FileAccess = createFileAccess(),
    /** Test seam: the rename that moves a tree aside and into place; production uses [File.renameTo]. */
    private val rename: (from: File, to: File) -> Boolean = { from: File, to: File -> from.renameTo(to) },
) : DownloaderStorage, RelativePathChecking, TransferLockScope {

    private val baseDir: File by lazy { File(context.filesDir, config.baseDirectoryName) }

    private val layout: UnitFileLayout = UnitFileLayout(caseInsensitive)

    override val transferLockScope: String get() = baseDir.absolutePath

    override fun transferLockKey(unit: DownloadUnit): String = layout.lockKey(unit)

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
            pagesDir.exists() && File(pagesDir, unit.archiveMarker()).isFile
        } else {
            // A plain file, or a link to one. A directory at the path (an archive unit of the same id, or a
            // stray one) is not the resource: the commit moves it aside.
            File(path).isFile
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
        return File(baseDir, normalizedPath).absolutePath
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
        // place); the next cancel or the next commit reclaims it. A merely busy lock registry is
        // retried, never taken for a running commit. Under the lock the tree is only renamed away; the recursive delete
        // runs after the lock is released so it cannot stall other units.
        UnitCommitLocks.tryDetachThenDispose(
            baseDirectory = baseDir.absolutePath,
            unitId = layout.lockKey(unit),
            detach = {
                val staging = File(baseDir, names.staging)
                val trash: File = newTrashEntry()
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
        realTrashDirectory().listFiles()?.forEach { entry: File -> deleteTree(entry) }
    }

    /**
     * The trash directory, after a symbolic link standing in its place has been removed as a link (never
     * followed, its target untouched), so a sweep never deletes files outside the base directory and a
     * move into the trash lands inside it: the next `mkdirs` creates a real directory. Runs before every use
     * of the trash. `tmp/` and `tmp/kmptoolkit-internal/` as links are out of scope: a link there redirects
     * every internal file, not only deletes, and no deletion goes through them.
     */
    private fun realTrashDirectory(): File {
        val dir = File(baseDir, UnitFileLayout.TRASH_DIRECTORY)
        if (files.metadata(dir.path, followLinks = false)?.kind == FileKind.SymbolicLink) files.delete(dir.path)
        return dir
    }

    /** A fresh entry path under the real trash directory, whose parents are created. */
    private fun newTrashEntry(): File {
        realTrashDirectory()
        val trash = File(baseDir, layout.trashEntry(Random.nextLong().toULong()))
        trash.parentFile?.mkdirs()
        return trash
    }

    /**
     * Deletes [file] and everything under it, treating an entry that another thread already removed
     * as gone. `FileAccess.deleteRecursively` walks without following any symbolic link and counts an
     * entry that vanished under its walk as removed; two cleanups of one tree, or a cleanup racing a
     * recommit, must never surface a failure. A symbolic link is removed as a link.
     */
    private fun deleteTree(file: File) {
        // The result is ignored: a tree that cannot be removed completely is left for the next sweep.
        files.deleteRecursively(file.path)
    }

    /** The size of the files under [file]; an entry that vanished meanwhile counts as zero. */
    private fun treeSize(file: File): Long = files.treeSize(file.path).getOrNull() ?: 0L

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
            // A link AT the unit's path is the resource (as for isResourceAvailable); the tree walk does not follow a
            // link, not even its own start, so it starts from the resolved path. Links inside the tree add nothing.
            //
            // A size query never throws: canonicalPath can fail with an IOException when the link is swapped or removed
            // between isDirectory and here, or on an I/O error. The walk then starts from the plain path, which yields
            // 0 for a path that no longer resolves (the answer for an absent resource) and the tree otherwise.
            val start: String = try {
                target.canonicalPath
            } catch (e: IOException) {
                target.path
            }
            treeSize(File(start))
        } else {
            target.length()
        }
    }

    override fun deleteResource(unit: DownloadUnit) {
        val target = File(getResourcePath(unit))
        if (!target.exists()) return
        if (target.isDirectory) {
            // Moved aside in one rename, then deleted: a process killed inside the walk leaves the
            // partial tree under trash/, never at the resource path, where a surviving marker would
            // read as a complete resource with files missing. No lock: the rename is atomic, and a
            // commit racing this delete finds either the whole tree or nothing to move aside.
            //
            // A rename that fails is retried while the target is still there (bounded): a commit of the same
            // unit may have renamed our tree away first and put its new tree in place before the re-check,
            // and that tree must be moved aside whole too, never walked in place. Only a rename that keeps
            // failing on a present tree falls back to the in-place delete.
            var detached: File? = null
            var attempts = 0
            while (detached == null && attempts < MAX_DETACH_ATTEMPTS && target.exists()) {
                detached = detachTree(target)
                attempts++
            }
            if (detached != null) {
                deleteTree(detached)
                sweepTrash()
            } else if (target.exists()) {
                // Unreachable on a sandbox volume, and this call has never thrown: fall back to the
                // in-place delete rather than start now.
                logger.w { "Could not move $unit aside; deleting it in place" }
                deleteTree(target)
            }
        } else {
            target.delete()
        }
        logger.i { "Deleted resource $unit at ${target.absolutePath}" }
    }

    /**
     * Renames [dir] to a fresh entry of the trash directory (`tmp/kmptoolkit-internal/trash/`) and returns
     * it; null when the rename failed, which includes [dir] being absent (the caller tells them apart by
     * [File.exists] after the failure). There is deliberately no [File.exists] pre-check: it follows links, so
     * a dangling symbolic link would read as absent, while the rename moves the link itself.
     * The rename is atomic and stays on one volume, so [dir] holds its whole tree or nothing at every instant.
     */
    private fun detachTree(dir: File): File? {
        val trash: File = newTrashEntry()
        return if (rename(dir, trash)) trash else null
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
        // An old archive tree replaced by this commit is only renamed away under the lock; it is deleted
        // after the lock is released (UnitCommitLocks), also when the commit failed after the rename.
        var detached: File? = null
        try {
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
                    commitCompleteTempFile(unit, tempFile, names) { tree: File -> detached = tree }
                } finally {
                    // The record belongs to the temp file. Once the file is gone — committed, or deleted
                    // by a failed check — the record goes with it, last. A failure that left the file in
                    // place keeps its record, so the retry is still checked against it.
                    if (!tempFile.exists()) File(baseDir, names.record).delete()
                }
            }
        } finally {
            detached?.let { tree: File ->
                deleteTree(tree)
                sweepTrash()
            }
        }
    }

    private fun commitCompleteTempFile(
        unit: DownloadUnit,
        tempFile: File,
        names: UnitFileLayout.Names,
        onDetached: (File) -> Unit,
    ) {
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
                    unit = unit,
                    tempFile = tempFile,
                    targetDir = stagingDir.absolutePath,
                )
                // Handle nested directory: ZIP may contain a root directory prefix
                // (e.g., entries like "pages/page001.webp" instead of "page001.webp"),
                // causing files to land in staging/pages/ instead of staging/.
                if (!File(stagingDir, unit.archiveMarker()).isFile) moveSingleRootUp(stagingDir, unit)
                // The tree must be what the unit declares before anything at the final path is touched:
                // a directory without its marker is not "ready", and it must not replace one that is.
                if (!File(stagingDir, unit.archiveMarker()).isFile) {
                    throw ResourceIntegrityException("Archive of $unit has no '${unit.archiveMarker()}' after extraction")
                }
                // The swap, in renames only: the resource path holds the whole old tree, nothing, or the
                // whole new tree, so a process killed at any step never leaves a marker over a partial tree.
                // The parent of a nested relativePath (e.g. "archives/pages") is absent on a first commit.
                targetDir.parentFile?.mkdirs()
                // The old tree moves into the trash; it is never renamed back (a sweep of another unit may
                // already be deleting it) and never deleted in place. No exists() pre-check decides whether
                // to try: it follows links, so a dangling link at the path would read as absent and block
                // the move into place forever. A failed rename with nothing at the path is nothing to detach.
                val oldTree: File? = detachTree(targetDir)
                check(oldTree != null || !targetDir.exists()) { "Could not move the committed $unit aside" }
                if (oldTree != null) onDetached(oldTree)
                // No copy fallback: staging and target both sit under baseDir, one volume. A copy writes
                // top-down, so it could land the marker before the rest.
                check(rename(stagingDir, targetDir)) { "Could not move the extracted $unit into place" }
            } catch (e: ResourceIntegrityException) {
                // Bytes that are not what the unit declares: the temp file is worthless, and one fresh download
                // is the fix. Everything above ran before the committed resource was touched.
                tempFile.delete()
                deleteTree(stagingDir)
                throw e
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
                unit = unit,
                onDetached = onDetached,
            )
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
    private fun moveSingleRootUp(stagingDir: File, unit: DownloadUnit) {
        val candidates: Array<File> =
            stagingDir.listFiles { f: File -> f.isDirectory && !f.list().isNullOrEmpty() } ?: emptyArray()
        if (candidates.size != 1) return
        val root: File = candidates[0]
        if (!File(root, unit.archiveMarker()).isFile) return
        logger.i { "Detected nested directory '${root.name}', moving contents up" }
        val taken: Set<String> = (stagingDir.list().orEmpty().toList() + root.list().orEmpty().toList())
            .map { name: String -> name.lowercase() }
            .toSet()
        var suffix = 0
        var temporaryName: String = TEMPORARY_ROOT_NAME
        while (temporaryName.lowercase() in taken) temporaryName = "$TEMPORARY_ROOT_NAME-${++suffix}"
        val temporary = File(stagingDir, temporaryName)
        check(root.renameTo(temporary)) { "Could not move the root directory '${root.name}' of $unit aside" }
        for (child: File in temporary.listFiles().orEmpty()) {
            val destination = File(stagingDir, child.name)
            if (destination.exists()) {
                if (destination.isDirectory && destination.list().isNullOrEmpty()) {
                    check(destination.delete()) { "Could not replace the empty directory '${child.name}' of $unit" }
                } else {
                    throw ResourceIntegrityException(
                        "Archive of $unit: '${child.name}' of root '${root.name}' collides with a top-level entry",
                    )
                }
            }
            check(child.renameTo(destination)) { "Could not move '${child.name}' of the root directory of $unit up" }
        }
        // File.delete() refuses a directory that still holds anything.
        check(temporary.delete()) { "Could not remove the emptied root directory of $unit" }
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
    private fun verifySqlite(tempFile: File, format: ResourceFormat.SqliteDatabase) {
        requireSqliteHeader(tempFile)
        // A leftover -wal would be replayed into this file, and opening it may leave its own: neither stays.
        deleteSqliteSidecars(tempFile)
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
                        // The table is a name: resolved through sqlite_master and quoted. A value that names no table
                        // is used as SQL text, as 3.0.0 did (a pre-quoted or schema-qualified name keeps working).
                        // future major release: drop the verbatim fallback
                        val stored: String? = queryText(db, SQLITE_RESOLVE_TABLE_SQL, table)
                        val countSql: String = if (stored != null) countRowsSql(stored) else countRowsVerbatimSql(table)
                        queryInt(db, countSql) to
                            queryText(db, SQLITE_META_VALUE_SQL, metaKey)?.toIntOrNull()
                    }
                }
        } catch (e: SQLiteException) {
            tempFile.delete()
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: not a valid database (${e.message})",
                e,
            )
        } finally {
            deleteSqliteSidecars(tempFile)
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

    /** Removes the write-ahead log, shared-memory and journal files SQLite keeps next to [database]. */
    private fun deleteSqliteSidecars(database: File) {
        for (sidecar: String in SqliteFiles.sidecarsOf(database.path)) File(sidecar).delete()
    }

    /** Deletes [tempFile] and throws when it does not start with the 16-byte SQLite header or is shorter than 100 bytes. */
    private fun requireSqliteHeader(tempFile: File) {
        val length: Long = tempFile.length()
        val head = ByteArray(SQLITE_HEADER_READ_SIZE)
        val read: Int = FileInputStream(tempFile).use { input: FileInputStream -> input.read(head) }
        if (read < head.size || !hasSqliteHeader(head, length)) {
            tempFile.delete()
            throw ResourceIntegrityException(
                "Downloaded resource failed integrity check: not a valid database (no SQLite header)",
            )
        }
    }

    private fun queryInt(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else -1 }
    }

    private fun queryText(db: SQLiteDatabase, sql: String, argument: String): String? {
        db.rawQuery(sql, arrayOf(argument)).use { cursor -> return if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun moveFile(tempFile: File, destinationPath: String, unit: DownloadUnit, onDetached: (File) -> Unit) {
        val destination = File(destinationPath)
        destination.parentFile?.mkdirs()
        // A directory at the path (an archive unit of the same id, or a stray one) cannot be replaced by a file
        // rename. It moves into the trash in one rename, under the unit lock, and is deleted after the lock; it
        // is never deleted in place. A plain file or a dangling link is still replaced by the rename itself.
        if (destination.isDirectory) {
            val oldTree: File? = detachTree(destination)
            check(oldTree != null || !destination.isDirectory) { "Could not move the committed $unit aside" }
            if (oldTree != null) onDetached(oldTree)
        }
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

    /**
     * Extracts [tempFile] into [targetDir]. Every entry is extracted or the call fails: an archive that cannot be
     * read as delivered (a cut or corrupt entry, a size or CRC-32 mismatch, an unsupported method, an encrypted
     * entry, a name that leaves [targetDir] or holds a NUL, a path longer than [MAX_ENTRY_PATH_BYTES] bytes) throws
     * [ResourceIntegrityException]. Only the READ side
     * is classified that way; a failure to write (a full disk) stays the plain [IOException] it is.
     */
    private fun extractZip(unit: DownloadUnit, tempFile: File, targetDir: String) {
        logger.i { "Extracting ZIP to $targetDir..." }
        requireEndRecord(unit, tempFile)
        val dir = File(targetDir)
        dir.mkdirs()
        val dirPath: String = dir.canonicalPath

        // Single reusable buffer for all entries to minimize allocations — a large archive with
        // hundreds of small files can otherwise trigger OOM once the app is backgrounded and heap
        // is tight.
        val copyBuffer = ByteArray(WRITE_BUFFER_SIZE)

        val entryLayout = ZipEntryLayout()
        ZipInputStream(FileInputStream(tempFile).buffered()).use { zipStream: ZipInputStream ->
            var entry: ZipEntry? = readingArchive(unit) { zipStream.nextEntry }
            while (entry != null) {
                val entryName: String = entry.name
                // A NUL would reach the file system inside a segment, and FileOutputStream rejects such a path with a plain
                // FileNotFoundException, which is not a storage failure but the archive's fault.
                if (entryName.indexOf('\u0000') >= 0) {
                    throw ResourceIntegrityException("Archive of $unit has an entry name with a NUL character")
                }
                // The entry is laid out from its resolved segments and the raw name is never handed to the file system:
                // File.canonicalFile on archive-controlled text refuses a string over PATH_MAX with a plain IOException
                // on OpenJDK (and, most likely, on ART), which would be a storage failure instead of the archive's fault.
                // A name that climbs out of the extraction directory resolves to null and is a traversal.
                val resolved: List<String> = resolveEntrySegments(entryName)
                    ?: throw ResourceIntegrityException("Archive of $unit has an entry that attempts path traversal: ${shortEntryName(entryName)}")
                // Before anything touches the file system: the layout check and mkdirs each walk the whole path, and a
                // name of tens of thousands of segments would spend minutes or the heap on a path no file system holds.
                if (entryPathBytes(dirPath, resolved) > MAX_ENTRY_PATH_BYTES) {
                    throw ResourceIntegrityException(
                        "Archive of $unit cannot be extracted: '${shortEntryName(entryName)}' is longer than this file system allows",
                    )
                }
                // So `a/../x` is written as `x`. dirPath is canonical and the staging tree holds no links (the extractor
                // writes only regular files and directories), so this is the path canonicalFile gave before.
                val output: File = resolved.fold(File(dirPath)) { parent: File, segment: String -> File(parent, segment) }
                if (entry.isDirectory) {
                    // An empty directory is part of the tree. A `./` or `/` entry is the target itself.
                    if (resolved.isNotEmpty()) {
                        requireInside(unit, output, dirPath, entryName)
                        requireLayout(unit, entryLayout, dir, resolved, isDirectory = true)
                        if (!output.isDirectory && !output.mkdirs() && !output.isDirectory) {
                            throw ResourceIntegrityException("Archive of $unit cannot be extracted: '${shortEntryName(entryName)}' cannot be created on this file system")
                        }
                    }
                } else {
                    // Zip slip protection, a second lexical defence after the resolution above
                    requireInside(unit, output, dirPath, entryName)
                    requireLayout(unit, entryLayout, dir, resolved, isDirectory = false)
                    output.parentFile?.mkdirs()
                    openOutput(unit, output, entryName).use { out: FileOutputStream ->
                        var bytesRead: Int
                        while (readingArchive(unit) { zipStream.read(copyBuffer) }.also { bytesRead = it } != -1) {
                            out.write(copyBuffer, 0, bytesRead)
                        }
                    }
                    logger.d { "Extracted: ${shortEntryName(entryName)}" }
                }
                readingArchive(unit) { zipStream.closeEntry() }
                entry = readingArchive(unit) { zipStream.nextEntry }
            }
        }
        logger.i { "ZIP extraction complete" }
    }

    /** Fails with [ResourceIntegrityException] when [entryName] cannot be laid out next to what was already extracted; see [ZipEntryLayout]. */
    private fun requireLayout(unit: DownloadUnit, entryLayout: ZipEntryLayout, dir: File, segments: List<String>, isDirectory: Boolean) {
        if (segments.isEmpty()) return
        val reason: String? = entryLayout.conflict(segments, isDirectory) { path: String -> File(dir, path).exists() }
        if (reason != null) throw ResourceIntegrityException("Archive of $unit cannot be extracted: $reason")
    }

    /**
     * Opens the file of one entry. A name or path the file system cannot hold (too long, a parent that could not be
     * created) is the archive's fault, [ResourceIntegrityException]; a full disk or a denied write stays the plain exception.
     */
    private fun openOutput(unit: DownloadUnit, output: File, entryName: String): FileOutputStream = try {
        FileOutputStream(output)
    } catch (e: FileNotFoundException) {
        val reason: String = e.message.orEmpty().lowercase()
        if (UNLAYABLE_REASONS.any { it in reason }) {
            throw ResourceIntegrityException("Archive of $unit cannot be extracted: '${shortEntryName(entryName)}' cannot be created on this file system (${e.message})", e)
        }
        throw e
    }

    private fun requireInside(unit: DownloadUnit, output: File, dirPath: String, entryName: String) {
        // path-hygiene-ignore: PH1 a prefix check on canonical host paths built with the same separator; java.nio.file.Path is missing below API 26
        if (!output.path.startsWith(dirPath + File.separator)) {
            throw ResourceIntegrityException("Archive of $unit has an entry that attempts path traversal: ${shortEntryName(entryName)}")
        }
    }

    /** Runs a read of the archive, turning what a broken archive makes `java.util.zip` throw into [ResourceIntegrityException]. */
    private inline fun <T> readingArchive(unit: DownloadUnit, read: () -> T): T = try {
        read()
    } catch (e: ZipException) {
        throw ResourceIntegrityException("Archive of $unit cannot be extracted: ${e.message}", e)
    } catch (e: EOFException) {
        throw ResourceIntegrityException("Archive of $unit cannot be extracted: ${e.message ?: "unexpected end of archive"}", e)
    } catch (e: IllegalArgumentException) {
        // The JDK's ZipCoder: an entry name that is not valid UTF-8 (flag set or not; legacy code pages write such names).
        throw ResourceIntegrityException("Archive of $unit has an entry name that is not valid UTF-8", e)
    }

    /**
     * Fails with [ResourceIntegrityException] when [tempFile] has no self-consistent end of central directory record
     * ([hasZipEndRecord]): a file cut anywhere before it, its central directory included, is a half-written archive.
     * Decided from the file alone before the walk, because `ZipInputStream` reads ahead and cannot say what it left.
     */
    private fun requireEndRecord(unit: DownloadUnit, tempFile: File) {
        RandomAccessFile(tempFile, "r").use { file: RandomAccessFile ->
            val length: Long = file.length()
            val size: Int = minOf(length, ZIP_END_RECORD_WINDOW).toInt()
            val tail = ByteArray(size)
            file.seek(length - size)
            file.readFully(tail)
            if (!hasZipEndRecord(tail, length - size)) {
                throw ResourceIntegrityException("Archive of $unit has no end of central directory record")
            }
        }
    }

    private companion object {
        /** The longest path, in UTF-8 bytes, an entry may get: PATH_MAX (4096) less the terminating NUL. */
        const val MAX_ENTRY_PATH_BYTES: Int = 4095

        /** How often deleteResource retries moving a present tree aside before it deletes it in place. */
        const val MAX_DETACH_ATTEMPTS: Int = 3

        /** The sibling name the single root directory is moved to while its children go up; a free variant is chosen. */
        const val TEMPORARY_ROOT_NAME: String = ".kmptoolkit-up"

        /** What a [FileNotFoundException] says when the name or the path cannot exist, as opposed to a full or read-only disk. */
        val UNLAYABLE_REASONS: List<String> =
            listOf("file name too long", "no such file or directory", "not a directory", "is a directory")
    }
}
