package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.io.IOException
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
 * never disagree, and every write rewrites the whole file through a temporary sibling and an atomic
 * rename. That is the right trade for what this module is for — a handful of small values — and it
 * is what makes a write durable when the call returns.
 */
internal class JvmKeyValueStorage(private val file: File) : KeyValueStorage {

    /** Shared by every instance over the same canonical path, so their read-modify-write cycles never interleave. */
    private val lock: Any = LOCKS.computeIfAbsent(file.absoluteFile.normalize().path) { Any() }

    override fun get(key: String): StorageResult<String?> = synchronized(lock) {
        try {
            StorageResult.Success(load().getProperty(key))
        } catch (error: IOException) {
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error))
        }
    }

    override fun put(key: String, value: String): StorageResult<Unit> =
        update(StorageOperation.PUT, key) { it.setProperty(key, value) }

    override fun remove(key: String): StorageResult<Unit> =
        update(StorageOperation.REMOVE, key) { it.remove(key) }

    override fun clear(): StorageResult<Unit> = synchronized(lock) {
        try {
            Files.deleteIfExists(file.toPath())
            StorageResult.Success(Unit)
        } catch (error: IOException) {
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, cause = error))
        }
    }

    private inline fun update(
        operation: StorageOperation,
        key: String,
        change: (Properties) -> Unit,
    ): StorageResult<Unit> = synchronized(lock) {
        try {
            val properties: Properties = load()
            change(properties)
            write(properties)
            StorageResult.Success(Unit)
        } catch (error: IOException) {
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
        val temporary: File = File.createTempFile(file.name, ".tmp", directory)
        try {
            temporary.writer(StandardCharsets.UTF_8).use { writer ->
                properties.store(writer, null)
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
        val LOCKS: ConcurrentHashMap<String, Any> = ConcurrentHashMap()
    }
}
