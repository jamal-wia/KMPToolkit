package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.AfterTest
import kotlin.test.Test
import co.touchlab.sqliter.DatabaseFileContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest

/**
 * The iOS factory itself — the entry point every consumer actually calls, and the one the shared
 * checks cannot reach because they build a driver directly.
 *
 * A test binary has no `CFBundleIdentifier` of its own, so the default config exercises the
 * documented fallback rather than a real bundle id. That is worth covering precisely because it is
 * the path a test run takes: if it threw, every consumer's first test against a real queue would
 * fail for a reason that has nothing to do with their code.
 */
class UploaderStorageFactoryIosTest {

    private val named = UploaderDatabaseConfig("com.example.factory-${uniqueDatabaseName("ios")}")

    @Test
    fun `the default config opens a usable queue even with no bundle identifier`() = runTest {
        val storage: UploaderStorage = createUploaderStorage()
        try {
            storage.store.clearAll()
            storage.store.insertKeep(anItem("a"))
            assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            storage.store.clearAll()
        } finally {
            storage.close()
        }
    }

    @Test
    fun `a named queue survives reopening through the factory`() = runTest {
        createUploaderStorage(named).let { storage ->
            storage.store.insertKeep(anItem("a"))
            storage.close()
        }
        createUploaderStorage(named).let { storage ->
            try {
                assertEquals(listOf("a"), storage.store.getAllActive().map { it.id })
            } finally {
                storage.close()
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun writeCorruptFile(config: UploaderDatabaseConfig, bytes: ByteArray): String {
        val path: String = DatabaseFileContext.databasePath(uploaderDatabaseFileName(config.name!!), null)
        val directory: String = path.substringBeforeLast('/')
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        val data: NSData = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, null), "could not write $path")
        return path
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFile(path: String): ByteArray {
        val data: NSData = NSFileManager.defaultManager.contentsAtPath(path) ?: return ByteArray(0)
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    @Test
    fun `a corrupt file fails the open and is left byte for byte by default`() = runTest {
        val config = UploaderDatabaseConfig("com.example.keepcorrupt-${uniqueDatabaseName("ios")}")
        val garbage = ByteArray(4_096) { (it * 17).toByte() }
        val path: String = writeCorruptFile(config, garbage)
        try {
            val failure: Throwable? = runCatching { createUploaderStorage(config) }.exceptionOrNull()
            assertTrue(failure is UploaderStorageException, "got $failure")
            assertEquals(UploaderStorageOperation.OPEN, failure.operation)
            assertTrue(garbage.contentEquals(readFile(path)), "the corrupt file was touched")
        } finally {
            deleteDatabaseFile(uploaderDatabaseFileName(config.name!!))
        }
    }

    @Test
    fun `recreateOnCorruption replaces a corrupt file with an empty working queue`() = runTest {
        val config = UploaderDatabaseConfig(
            "com.example.recreate-${uniqueDatabaseName("ios")}",
            recreateOnCorruption = true,
        )
        val garbage = ByteArray(4_096) { (it * 17).toByte() }
        val path: String = writeCorruptFile(config, garbage)
        try {
            val storage: UploaderStorage = createUploaderStorage(config)
            try {
                assertEquals(emptyList(), storage.store.getAllActive().map { it.id })
                storage.store.insertKeep(anItem("fresh"))
                assertEquals(listOf("fresh"), storage.store.getAllActive().map { it.id })
            } finally {
                storage.close()
            }
            assertTrue(!garbage.contentEquals(readFile(path)), "the corrupt bytes are still there")
        } finally {
            deleteDatabaseFile(uploaderDatabaseFileName(config.name!!))
        }
    }

    @AfterTest
    fun removeTheNamedQueue() {
        deleteDatabaseFile(uploaderDatabaseFileName(named.name!!))
    }
}
