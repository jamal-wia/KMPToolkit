package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.test.BeforeTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.memcpy

/** Runs [TempLayoutRound7Contract] against the iOS storage. */
@OptIn(ExperimentalForeignApi::class)
class IosTempLayoutRound7Test : TempLayoutRound7Contract() {

    private val config = DownloaderStorageConfig(baseDirectoryName = "layout_round7")

    // A run that was killed leaves its files behind; none of them may leak into this one.
    @BeforeTest
    fun deleteDirectoryBeforeTest() {
        deleteTestDirectory()
    }

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(config)

    override fun writeFile(path: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file)
                }
            }
        } finally {
            fclose(file)
        }
    }

    override fun readFile(path: String): ByteArray? {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: return null
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        }
        return bytes
    }

    override fun deleteTestDirectory() {
        NSFileManager.defaultManager.removeItemAtPath(baseDirectory(), error = null)
    }

    private fun baseDirectory(): String {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).first() as String
        return "$appSupport/${config.baseDirectoryName}"
    }
}
