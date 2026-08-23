package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage.Companion.WRITE_BUFFER_SIZE
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.i
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * [DownloaderStorage] over plain files under `filesDir/<config.baseDirectoryName>/`.
 *
 * A downloader implementation writing into the temp file this class names via [getTempFilePath]
 * needs nothing further from here — ordinary `FileOutputStream` (append mode, resuming from
 * [getTempFileSize]) is all a `BackgroundResourceDownloader` needs to stream bytes onto disk. This
 * class only finalizes what already arrived.
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

    override fun isTempFileAvailable(unit: DownloadUnit): Boolean {
        val tempFile = File(getTempFilePath(unit))
        return tempFile.exists() && tempFile.length() > 0
    }

    override fun getTempFileSize(unit: DownloadUnit): Long {
        val tempFile = File(getTempFilePath(unit))
        return if (tempFile.exists()) tempFile.length() else 0L
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        File(getTempFilePath(unit)).delete()
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
        val tempFile = File(getTempFilePath(unit))
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
     * Verifies a committed-to-be [ResourceFormat.SqliteDatabase] before it is moved into place.
     *
     * Opening it is the baseline check — bytes that are not a database fail here. When the format
     * also names a table and a `meta` key, the real row count must match the count the file itself
     * declares, which catches a truncated download that still happens to parse. Which table and
     * which key those are is the host's own domain knowledge, which is why they are values on the
     * unit's [ResourceFormat.SqliteDatabase] rather than anything this library assumes.
     *
     * Deletes the temp file and throws on any failure: nothing invalid may reach the final path,
     * not even momentarily.
     */
    private fun verifySqlite(tempFile: File, format: ResourceFormat.SqliteDatabase) {
        val counts: Pair<Int, Int?>? = try {
            SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { db: SQLiteDatabase ->
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
            throw IllegalStateException(
                "Downloaded resource failed integrity check: not a valid database (${e.message})",
                e,
            )
        }
        val (actualCount: Int, declaredCount: Int?) = counts ?: return
        if (declaredCount == null || actualCount != declaredCount) {
            tempFile.delete()
            error("Downloaded resource failed integrity check: $actualCount rows, meta declares $declaredCount")
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
