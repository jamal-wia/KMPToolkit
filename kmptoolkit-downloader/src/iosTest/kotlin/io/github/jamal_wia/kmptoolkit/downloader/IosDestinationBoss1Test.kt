package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.test.BeforeTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.chmod
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

/** Runs [DestinationBoss1Contract] against the iOS storage. */
@OptIn(ExperimentalForeignApi::class)
class IosDestinationBoss1Test : DestinationBoss1Contract() {

    private val config = DownloaderStorageConfig(baseDirectoryName = "destination_boss1")

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

    override fun deletePath(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    override fun deleteTestDirectory() {
        NSFileManager.defaultManager.removeItemAtPath(baseDirectory(), error = null)
    }

    override fun makeReadOnly(path: String) {
        check(chmod(path, 0x16Du.convert()) == 0) { "could not take the write permission from $path" }
    }

    override fun restoreWritable() {
        restoreUnder(baseDirectory())
    }

    private fun restoreUnder(directory: String) {
        chmod(directory, 0x1EDu.convert())
        val names: List<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, error = null)
            ?.filterIsInstance<String>() ?: return
        for (name: String in names) restoreUnder("$directory/$name")
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
