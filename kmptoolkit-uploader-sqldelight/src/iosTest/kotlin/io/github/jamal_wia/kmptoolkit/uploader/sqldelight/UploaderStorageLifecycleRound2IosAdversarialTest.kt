package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import co.touchlab.sqliter.DatabaseFileContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithBytes

/**
 * A crash image on iOS: the process died with committed rows still in the write-ahead log and a damaged main
 * file. `recreateOnCorruption` must give an empty queue that stays working, not one that mixes the dead log
 * into a fresh file.
 */
@OptIn(ExperimentalForeignApi::class)
class UploaderStorageLifecycleRound2IosAdversarialTest {

    private fun read(path: String): ByteArray {
        val data: NSData = NSFileManager.defaultManager.contentsAtPath(path) ?: return ByteArray(0)
        val out = ByteArray(data.length.toInt())
        if (out.isNotEmpty()) out.usePinned { platform.posix.memcpy(it.addressOf(0), data.bytes, data.length) }
        return out
    }

    private fun write(path: String, bytes: ByteArray) {
        val data: NSData = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, null), "could not write $path")
    }

    private fun removeAll(path: String) {
        listOf(path, "$path-wal", "$path-shm", "$path-journal").forEach {
            NSFileManager.defaultManager.removeItemAtPath(it, null)
        }
    }

    private suspend fun seed(config: UploaderDatabaseConfig, count: Int) {
        val storage: UploaderStorage = createUploaderStorage(config)
        try {
            storage.transactionRunner.inTransaction {
                repeat(count) { storage.store.insertKeep(anItem("seed-$it")) }
            }
        } finally {
            storage.close()
        }
    }

    private fun damageBelowTheFirstPage(path: String) {
        val bytes: ByteArray = read(path)
        assertTrue(bytes.size >= 4 * 4096, "setup: the seeded file is too small (${bytes.size}) for damage to reach a data page")
        assertTrue(read("$path-wal").isEmpty(), "setup: a write-ahead log still holds pages, so damage to the main file would be masked")
        for (index in 4096 until bytes.size) bytes[index] = ((index * 131) xor 0x5A).toByte()
        write(path, bytes)
    }

    @Test
    fun `recreateOnCorruption keeps a working consistent queue when a dead write-ahead log sits next to it`() = runTest {
        val config = UploaderDatabaseConfig(
            "com.example.r2.wal-${uniqueDatabaseName("ios")}",
            recreateOnCorruption = true,
        )
        val path: String = DatabaseFileContext.databasePath(uploaderDatabaseFileName(config.name!!), null)
        removeAll(path)
        try {
            val live: UploaderStorage = createUploaderStorage(config)
            val walSnapshot: ByteArray
            try {
                live.store.insertKeep(anItem("old-1"))
                live.store.insertKeep(anItem("old-2"))
                walSnapshot = read("$path-wal")
            } finally {
                live.close()
            }
            assertTrue(walSnapshot.isNotEmpty(), "setup: the rows are not in a write-ahead log, so this scenario cannot arise")

            // The crash image: the log the dead process left, next to a main file that is no database.
            removeAll(path)
            write(path, ByteArray(4_096) { (it * 17).toByte() })
            write("$path-wal", walSnapshot)

            val storage: UploaderStorage = createUploaderStorage(config)
            try {
                storage.store.insertKeep(anItem("fresh"))
            } finally {
                storage.close()
            }
            val reopened: UploaderStorage = createUploaderStorage(config)
            try {
                assertTrue("fresh" in reopened.store.getAllActive().map { it.id }, "the recreated file lost an acknowledged insert")
            } finally {
                reopened.close()
            }
        } finally {
            removeAll(path)
        }
    }

    @Test
    fun `default config over a file damaged below a valid header fails typed on use and leaves the file as found`() = runTest {
        val config = UploaderDatabaseConfig("com.example.r2.interior-${uniqueDatabaseName("ios")}")
        val path: String = DatabaseFileContext.databasePath(uploaderDatabaseFileName(config.name!!), null)
        removeAll(path)
        try {
            seed(config, 600)
            damageBelowTheFirstPage(path)
            val damaged: ByteArray = read(path)

            val outcome: Result<UploaderStorage> = runCatching { createUploaderStorage(config) }
            outcome.getOrNull()?.let { storage ->
                try {
                    val failure: Throwable? = runCatching { storage.store.getAllActive() }.exceptionOrNull()
                    assertTrue(failure is UploaderStorageException, "reading damaged pages must fail typed, got $failure")
                } finally {
                    storage.close()
                }
            }
            outcome.exceptionOrNull()?.let { assertTrue(it is UploaderStorageException, "got $it") }

            assertTrue(damaged.contentEquals(read(path)), "the damaged file was modified or deleted without the app's say")
        } finally {
            removeAll(path)
        }
    }

    @Test
    fun `recreateOnCorruption does not let a dead write-ahead log leak the old queue into the new file`() = runTest {
        val config = UploaderDatabaseConfig(
            "com.example.r2.wal-${uniqueDatabaseName("ios")}",
            recreateOnCorruption = true,
        )
        val path: String = DatabaseFileContext.databasePath(uploaderDatabaseFileName(config.name!!), null)
        removeAll(path)
        try {
            val live: UploaderStorage = createUploaderStorage(config)
            val walSnapshot: ByteArray
            try {
                live.store.insertKeep(anItem("old-1"))
                live.store.insertKeep(anItem("old-2"))
                walSnapshot = read("$path-wal")
            } finally {
                live.close()
            }
            assertTrue(walSnapshot.isNotEmpty(), "setup: the rows are not in a write-ahead log, so this scenario cannot arise")

            // The crash image: the log the dead process left, next to a main file that is no database.
            removeAll(path)
            write(path, ByteArray(4_096) { (it * 17).toByte() })
            write("$path-wal", walSnapshot)

            val storage: UploaderStorage = createUploaderStorage(config)
            try {
                assertEquals(emptyList(), storage.store.getAllActive().map { it.id }, "the recreated queue must be empty")
                storage.store.insertKeep(anItem("fresh"))
            } finally {
                storage.close()
            }
            val reopened: UploaderStorage = createUploaderStorage(config)
            try {
                assertEquals(listOf("fresh"), reopened.store.getAllActive().map { it.id }, "the recreated file did not stay consistent")
            } finally {
                reopened.close()
            }
        } finally {
            removeAll(path)
        }
    }

    @Test
    fun `recreateOnCorruption heals a file damaged below a valid header by the next launch at the latest`() = runTest {
        val config = UploaderDatabaseConfig(
            "com.example.r2.interior.optin-${uniqueDatabaseName("ios")}",
            recreateOnCorruption = true,
        )
        val path: String = DatabaseFileContext.databasePath(uploaderDatabaseFileName(config.name!!), null)
        removeAll(path)
        try {
            seed(config, 600)
            damageBelowTheFirstPage(path)

            // The first launch meets the damage on its first read (the open itself only reads the header).
            runCatching { createUploaderStorage(config) }.getOrNull()?.let { first ->
                try {
                    runCatching { first.store.getAllActive() }
                } finally {
                    first.close()
                }
            }

            // The next launch: the documented outcome of the option is a working, empty queue.
            val second: UploaderStorage = createUploaderStorage(config)
            try {
                val rows: List<String> = second.store.getAllActive().map { it.id }
                assertEquals(emptyList(), rows, "the damaged queue was neither recreated nor readable")
                second.store.insertKeep(anItem("after-restart"))
                assertEquals(listOf("after-restart"), second.store.getAllActive().map { it.id })
            } finally {
                second.close()
            }
        } finally {
            removeAll(path)
        }
    }
}
