package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.KeyedMutex
import io.github.jamal_wia.kmptoolkit.files.PathKey
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [KeyValueStorage] over a single properties file.
 *
 * Every operation reads the file afresh rather than caching it, so two instances over the same file
 * never disagree, and every write rewrites the whole file through a temporary sibling, flushed to the
 * device, and an atomic rename. That is the right trade for what this module is for — a handful of
 * small values — and it is what makes a write durable when the call returns. The write is
 * `kmptoolkit-files`' `writeAtomically`; when the file system refuses the atomic rename the write
 * fails instead of replacing the file non-atomically.
 *
 * Nothing throws: an unreadable or corrupted file, a path the file system rejects, and a denied
 * security check all arrive as [StorageError.OperationFailed]. A file that cannot be parsed,
 * including one that is not valid UTF-8, fails every operation on it, writes included, and is never
 * replaced: its other values may still be recoverable by hand. [KeyValueStorage.clear] is the way out.
 *
 * The file I/O runs on [dispatcher], never on the caller's thread.
 */
internal class JvmKeyValueStorage(
    private val file: File,
    private val dispatcher: CoroutineDispatcher,
    private val files: FileAccess = createFileAccess(),
) : KeyValueStorage {

    /**
     * Shared by every instance whose path is the same file on a volume that folds case or Unicode
     * normalization (macOS, Windows), so their read-modify-write cycles never interleave. On a
     * case-sensitive volume two such spellings are two files that share one lock.
     */
    private val lockKey: String = jvmStorageLockKey(file)

    override suspend fun get(key: String): StorageResult<String?> = guarded(StorageOperation.GET, key) {
        load().getProperty(key)
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> {
        unpairedSurrogateFailure(key, value)?.let { return it }
        return guarded(StorageOperation.PUT, key) {
            val properties: Properties = load()
            properties.setProperty(key, value)
            write(properties)
        }
    }

    override suspend fun remove(key: String): StorageResult<Unit> = guarded(StorageOperation.REMOVE, key) {
        val properties: Properties = load()
        properties.remove(key)
        write(properties)
    }

    override suspend fun clear(): StorageResult<Unit> = guarded(StorageOperation.CLEAR, key = null) {
        Files.deleteIfExists(file.toPath())
        // A process killed between creating a temporary file and renaming it leaves that file behind.
        file.absoluteFile.parentFile
            ?.listFiles { sibling -> FileNames.isTempName(sibling.name, file.name) }
            ?.forEach { leftover -> Files.deleteIfExists(leftover.toPath()) }
    }

    private suspend inline fun <T> guarded(
        operation: StorageOperation,
        key: String?,
        crossinline block: () -> T,
    ): StorageResult<T> = withContext(dispatcher) {
        LOCKS.withLock(lockKey) {
            try {
                StorageResult.Success(block())
            } catch (error: IOException) {
                StorageResult.Failure(StorageError.OperationFailed(operation, key = key, cause = error))
            } catch (error: IllegalArgumentException) {
                // A malformed \uXXXX escape in a hand-edited file, or a name the file system rejects.
                StorageResult.Failure(StorageError.OperationFailed(operation, key = key, cause = error))
            } catch (error: SecurityException) {
                StorageResult.Failure(StorageError.OperationFailed(operation, key = key, cause = error))
            }
        }
    }

    private fun load(): Properties {
        val properties = Properties()
        if (file.isFile) {
            // Strict: a byte that is not UTF-8 is an error (a MalformedInputException, an IOException),
            // never a replacement character that the next write would store for good.
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(FileInputStream(file), decoder).use(properties::load)
        }
        return properties
    }

    private fun write(properties: Properties) {
        // The temporary sibling's prefix is the file name, which kmptoolkit-files caps so that every temporary name
        // fits 255 bytes. A longer store file name could not have a temporary sibling in 4.x either.
        if (file.name.toByteArray(StandardCharsets.UTF_8).size > MAX_TEMPORARY_PREFIX_BYTES) {
            throw IOException("store file name too long for a temporary sibling")
        }
        val buffer = ByteArrayOutputStream()
        val writer = OutputStreamWriter(buffer, StandardCharsets.UTF_8)
        properties.store(writer, null)
        writer.flush()
        // The temporary sibling is named `<file name><digits>.tmp`, the shape clear() recognizes as this store's own.
        val result: FileResult<Unit> = files.writeAtomically(
            file.absolutePath,
            buffer.toByteArray(),
            WriteOptions(Durability.Synced, createParentDirectories = true, tempPrefix = file.name),
        )
        if (result is FileResult.Failure) {
            val error: FileError = result.error
            throw IOException(error.toString(), (error as? FileError.Io)?.cause)
        }
    }

    private companion object {
        // 255-byte file names minus up to 19 digits and ".tmp": the longest prefix kmptoolkit-files accepts.
        const val MAX_TEMPORARY_PREFIX_BYTES: Int = FileNames.MAX_NAME_BYTES - 19 - 4

        // Process-wide, one entry per file with a user: entries vanish when no cycle holds or awaits the key.
        val LOCKS: KeyedMutex<String> = KeyedMutex()
    }
}

/**
 * The key of the lock shared by every store over one file: the canonical path (or the absolute one),
 * Unicode-normalized (NFC) and folded through the full case mapping, by `PathKey.of(path, CaseFolding.Always)` of
 * kmptoolkit-files (lowercase, uppercase, lowercase, then
 * NFC again, because case mapping can leave a decomposed sequence): `ß`, the capital sharp s `ẞ` and `ss`,
 * the Greek sigma forms, the long s `ſ` and `s`, the Kelvin sign and `k` each get one key. Two paths whose
 * uppercase forms after lowercasing are equal always get the same key. The formula was checked against the
 * full case-folding table of the Unicode version of the JDK it was written on: no group of code points that
 * fold to one string is split by it (over-folding, joining names a volume keeps apart, only costs parallelism). Folded always, never probed: a case-sensitive volume
 * only loses some parallelism between `Settings` and `settings` (two files behind one lock; the
 * guarded block never suspends and never nests another lock, so nothing can deadlock), while a
 * volume that folds case would otherwise lose keys, because the canonical path of a file that does
 * not exist yet keeps the spelling it was given. Probing the volume would need file I/O in a
 * constructor. A volume with a folding table of its own could still treat two names whose capitals
 * differ as one file; no such case is known for APFS or NTFS.
 */
internal fun jvmStorageLockKey(file: File): String {
    val path: String = try {
        file.canonicalPath
    } catch (_: IOException) {
        file.absoluteFile.normalize().path
    } catch (_: SecurityException) {
        file.absoluteFile.normalize().path
    }
    return PathKey.of(path, CaseFolding.Always)
}
