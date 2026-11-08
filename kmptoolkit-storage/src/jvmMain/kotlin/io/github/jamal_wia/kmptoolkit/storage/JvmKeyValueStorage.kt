package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [KeyValueStorage] over a single properties file.
 *
 * Every operation reads the file afresh rather than caching it, so two instances over the same file
 * never disagree, and every write rewrites the whole file through a temporary sibling, flushed to the
 * device, and an atomic rename. That is the right trade for what this module is for — a handful of
 * small values — and it is what makes a write durable when the call returns.
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
) : KeyValueStorage {

    /**
     * Shared by every instance whose path is the same file on a volume that folds case or Unicode
     * normalization (macOS, Windows), so their read-modify-write cycles never interleave. On a
     * case-sensitive volume two such spellings are two files that share one lock.
     */
    private val lock: Mutex = LOCKS.computeIfAbsent(jvmStorageLockKey(file)) { Mutex() }

    override suspend fun get(key: String): StorageResult<String?> = guarded(StorageOperation.GET, key) {
        load().getProperty(key)
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> = guarded(StorageOperation.PUT, key) {
        val properties: Properties = load()
        properties.setProperty(key, value)
        write(properties)
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
            ?.listFiles { sibling -> isOwnTemporary(sibling.name) }
            ?.forEach { leftover -> Files.deleteIfExists(leftover.toPath()) }
    }

    private suspend inline fun <T> guarded(
        operation: StorageOperation,
        key: String?,
        crossinline block: () -> T,
    ): StorageResult<T> = withContext(dispatcher) {
        lock.withLock {
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

    /**
     * Exactly the shape [File.createTempFile] gives this store's writer: the file name, a run of digits,
     * the suffix. A looser prefix match would also take another store's temporary file whose own file
     * name merely starts with this one's.
     */
    private fun isOwnTemporary(name: String): Boolean {
        if (!name.startsWith(file.name) || !name.endsWith(TEMPORARY_SUFFIX)) return false
        val middle: String = name.substring(file.name.length, name.length - TEMPORARY_SUFFIX.length)
        return middle.isNotEmpty() && middle.all { it in '0'..'9' }
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
        val directory: File = file.absoluteFile.parentFile
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create directory $directory")
        }
        val temporary: File = File.createTempFile(file.name, TEMPORARY_SUFFIX, directory)
        try {
            FileOutputStream(temporary).use { output ->
                val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
                properties.store(writer, null)
                writer.flush()
                // Without this the rename can reach the disk before the data, and a power loss in between
                // leaves an empty file where the previous contents were.
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX: String = ".tmp"

        // ConcurrentHashMap, not a Mutex-guarded map: looked up from the non-suspending constructor; JVM-only, so no
        // Kotlin/Native constraint. The values are coroutine Mutexes.
        val LOCKS: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()
    }
}

/**
 * The key of the lock shared by every store over one file: the canonical path (or the absolute one),
 * Unicode-normalized (NFC) and lower-cased. Folded always, never probed: a case-sensitive volume
 * only loses some parallelism between `Settings` and `settings` (two files behind one lock; the
 * guarded block never suspends and never nests another lock, so nothing can deadlock), while a
 * volume that folds case would otherwise lose keys, because the canonical path of a file that does
 * not exist yet keeps the spelling it was given. Probing the volume would need file I/O in a
 * constructor.
 */
internal fun jvmStorageLockKey(file: File): String {
    val path: String = try {
        file.canonicalPath
    } catch (_: IOException) {
        file.absoluteFile.normalize().path
    } catch (_: SecurityException) {
        file.absoluteFile.normalize().path
    }
    return Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
