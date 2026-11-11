package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Round 7 SUSPECT candidate for the cross-engine transfer lock on iOS: two storages whose base directory
 * names differ only in case are one directory on a volume that folds case (the iOS default), so the engines
 * over them must share one transfer lock scope, as units differing only in case already do.
 */
@OptIn(ExperimentalForeignApi::class)
class IosTransferLockScopeRound7Test {

    private class IdUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/files/$id"
        override val relativePath: String = "files/$id.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@IdUnit)
        }
    }

    @AfterTest
    fun cleanUp() {
        val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        NSFileManager.defaultManager.removeItemAtPath("$appSupport/Round7ScopeDir", error = null)
    }

    @Test
    fun `storages over one directory spelled in different cases share a transfer lock scope`() {
        val upper: DownloaderStorage = createDownloaderStorage(DownloaderStorageConfig("Round7ScopeDir"))
        val lower: DownloaderStorage = createDownloaderStorage(DownloaderStorageConfig("round7scopedir"))
        val unit = IdUnit("model")
        upper.beginTempFile(unit, null)
        val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val sameDirectory: Boolean = NSFileManager.defaultManager.fileExistsAtPath("$appSupport/round7scopedir")

        if (!sameDirectory) return // a case-sensitive volume: two directories, two scopes are right
        val a: TransferLockScope = upper as TransferLockScope
        val b: TransferLockScope = lower as TransferLockScope
        assertTrue(sameDirectory)
        assertEquals(a.transferLockScope.lowercase(), b.transferLockScope.lowercase())
        assertEquals(a.transferLockScope, b.transferLockScope, "one directory, two lock scopes: the cross-engine lock does not hold")
    }
}
