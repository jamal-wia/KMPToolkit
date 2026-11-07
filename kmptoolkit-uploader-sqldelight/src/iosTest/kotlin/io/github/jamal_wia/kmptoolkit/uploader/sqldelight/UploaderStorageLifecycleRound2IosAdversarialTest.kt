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
}
