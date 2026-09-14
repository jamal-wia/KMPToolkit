package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * [KeyValueStorage] over a single properties file.
 *
 * Every operation reads the file afresh rather than caching it, so two instances over the same file
 * never disagree, and every write rewrites the whole file through a temporary sibling, flushed to the
 * device, and an atomic rename. That is the right trade for what this module is for — a handful of
 * small values — and it is what makes a write durable when the call returns.
 *
 * Nothing throws: an unreadable or corrupted file, a path the file system rejects, and a denied
 * security check all arrive as [StorageError.OperationFailed].
 */
internal class JvmKeyValueStorage(private val file: File) : KeyValueStorage {

    /** Shared by every instance over the same canonical path, so their read-modify-write cycles never interleave. */
    private val lock: Any = LOCKS.computeIfAbsent(lockKey(file)) { Any() }

    override fun get(key: String): StorageResult<String?> = guarded(StorageOperation.GET, key) {
        load().getProperty(key)
    }

    override fun put(key: String, value: String): StorageResult<Unit> = guarded(StorageOperation.PUT, key) {
        val properties: Properties = load()
        properties.setProperty(key, value)
        write(properties)
    }

    override fun remove(key: String): StorageResult<Unit> = guarded(StorageOperation.REMOVE, key) {
        val properties: Properties = load()
        properties.remove(key)
        write(properties)
    }

    override fun clear(): StorageResult<Unit> = guarded(StorageOperation.CLEAR, key = null) {
        Files.deleteIfExists(file.toPath())
        // A process killed between creating a temporary file and renaming it leaves that file behind.
        file.absoluteFile.parentFile
            ?.listFiles { sibling -> sibling.name.startsWith(file.name) && sibling.name.endsWith(TEMPORARY_SUFFIX) }
            ?.forEach { leftover -> Files.deleteIfExists(leftover.toPath()) }
    }

    private inline fun <T> guarded(operation: StorageOperation, key: String?, block: () -> T): StorageResult<T> =
        synchronized(lock) {
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

    private fun load(): Properties {
        val properties = Properties()
        if (file.isFile) {
            file.reader(StandardCharsets.UTF_8).use(properties::load)
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

        val LOCKS: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

        /** The canonical path — symlinks and case-insensitive spellings resolved — or the absolute one. */
        fun lockKey(file: File): String = try {
            file.canonicalPath
        } catch (_: IOException) {
            file.absoluteFile.normalize().path
        } catch (_: SecurityException) {
            file.absoluteFile.normalize().path
        }
    }
}
