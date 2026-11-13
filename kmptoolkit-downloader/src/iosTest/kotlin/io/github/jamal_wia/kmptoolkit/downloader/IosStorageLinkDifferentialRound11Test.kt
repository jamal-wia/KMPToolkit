package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.BeforeTest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.symlink

/** Runs [StorageLinkDifferentialRound11Contract] against the iOS storage. */
@OptIn(ExperimentalForeignApi::class)
class IosStorageLinkDifferentialRound11Test : StorageLinkDifferentialRound11Contract() {

    private val config = DownloaderStorageConfig(baseDirectoryName = "link_diff_round11")
    private val manager: NSFileManager = NSFileManager.defaultManager

    @BeforeTest
    fun deleteDirectoryBeforeTest() {
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    override fun newStorage(): DownloaderStorage = createDownloaderStorage(config)

    override fun writeFile(path: String, bytes: ByteArray) {
        manager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
            }
        } finally {
            fclose(file)
        }
    }

    override fun pathExists(path: String): Boolean = manager.fileExistsAtPath(path)

    override fun makeLink(link: String, target: String) {
        manager.createDirectoryAtPath(
            path = link.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        check(symlink(target, link) == 0) { "could not create the symbolic link $link" }
    }

    override fun isLink(path: String): Boolean =
        (manager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeSymbolicLink

    override fun deleteTestDirectory() {
        manager.removeItemAtPath(baseDirectory(), error = null)
    }

    private fun baseDirectory(): String {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        return "$appSupport/${config.baseDirectoryName}"
    }
}
