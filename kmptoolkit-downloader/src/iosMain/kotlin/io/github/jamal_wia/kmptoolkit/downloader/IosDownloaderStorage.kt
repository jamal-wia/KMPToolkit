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
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.posix.FILE
import platform.posix.SEEK_CUR
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.fwrite
import platform.posix.remove
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
 * needs nothing further from here — an ordinary `NSFileHandle` (or POSIX `fopen`/`fwrite`) opened
 * at that path, appending from [getTempFileSize] to resume, is all a `BackgroundResourceDownloader`
 * needs. This class only finalizes what already arrived.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosDownloaderStorage(
    private val config: DownloaderStorageConfig,
    private val logger: Logger,
) : DownloaderStorage {

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

    override fun isResourceAvailable(unit: DownloadUnit): Boolean {
        return if (unit.isDirectoryResource) {
            val pagesDir: String = getResourcePath(unit)
            fileExists("$pagesDir/${unit.archiveMarker()}")
        } else {
            fileExists(getResourcePath(unit))
        }
    }

    override fun getResourcePath(unit: DownloadUnit): String {
        return "$baseDir/${unit.relativePath}"
    }

    override fun getTempFilePath(unit: DownloadUnit): String {
        return "$baseDir/tmp/${unit.id}.${unit.tempExtension}"
    }

    override fun isTempFileAvailable(unit: DownloadUnit): Boolean {
        return fileExists(getTempFilePath(unit))
    }

    override fun getTempFileSize(unit: DownloadUnit): Long {
        val path: String = getTempFilePath(unit)
        val attributes: Map<Any?, *>? = NSFileManager.defaultManager
            .attributesOfItemAtPath(path, error = null)

        val size: Long? = attributes?.get(NSFileSize) as? Long
        return size ?: 0L
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        remove(getTempFilePath(unit))
    }

    override fun getResourceSize(unit: DownloadUnit): Long {
        val path: String = getResourcePath(unit)
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
        val tempFilePath: String = getTempFilePath(unit)
        if (unit.isDirectoryResource) {
            val targetDir: String = getResourcePath(unit)
            // Per-unit, not a single shared name — two archive units extracting at the same time
            // must not collide in one staging directory.
            val stagingDir = "$baseDir/tmp/staging-${unit.id}"
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
                val moved: Boolean = NSFileManager.defaultManager.moveItemAtPath(
                    srcPath = stagingDir,
                    toPath = targetDir,
                    error = null,
                )
                if (!moved) {
                    // Fallback: copy + delete
                    NSFileManager.defaultManager.copyItemAtPath(stagingDir, targetDir, null)
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
    private fun verifySqlite(tempFilePath: String, format: ResourceFormat.SqliteDatabase) {
        val counts: Pair<Int, Int?>? = try {
            val connection: SQLiteConnection = BundledSQLiteDriver().open(tempFilePath)
            try {
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
            throw IllegalStateException(
                "Downloaded resource failed integrity check: not a valid database (${e.message})",
                e,
            )
        }
        val (actualCount: Int, declaredCount: Int?) = counts ?: return
        if (declaredCount == null || actualCount != declaredCount) {
            NSFileManager.defaultManager.removeItemAtPath(tempFilePath, error = null)
            error("Downloaded resource failed integrity check: $actualCount rows, meta declares $declaredCount")
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
        // Remove destination if exists (moveItemAtPath fails otherwise)
        NSFileManager.defaultManager.removeItemAtPath(destinationPath, error = null)
        val moved: Boolean = NSFileManager.defaultManager.moveItemAtPath(
            srcPath = tempFilePath,
            toPath = destinationPath,
            error = null,
        )
        if (!moved) {
            // Fallback: copy + delete
            NSFileManager.defaultManager.copyItemAtPath(tempFilePath, destinationPath, null)
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
