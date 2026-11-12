package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * Round 10, R10-S4: documented behavior (docs 05, "Ids"): a file named like Foundation's temporary file of an atomic write
 * (`<name>.sb-<8 hex>-<6 letters or digits>`) is a leftover, whatever it holds: not an entry for the sweep, not read by
 * `inspect`, and never deleted while it is younger than five minutes (it may be a write that is still running).
 * That is what a 4.0.0 entry of an id that ends this way becomes: it cannot be told from a leftover.
 */
@OptIn(ExperimentalForeignApi::class)
class Journal400TempShapedIdIosRound10Test {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r10t-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val now = 1_700_000_000_000L

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    @Test
    fun `a file named like a Foundation temporary file is no entry and survives while it is young`() = runBlocking {
        val id = "upload.sb-1234abcd-ABC123"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val bytes: ByteArray = OutcomeCodec.encode(UploadResponse(200, emptyMap(), ByteArray(0), false), now, null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath("$dir/$id", data, attributes = null))
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock { now + 1_000L })
        assertEquals(JournalEntry.Absent, journal.inspect(id))
        val handed: MutableList<String> = mutableListOf()
        journal.sweep { itemId, _, _, _ ->
            handed += itemId
            JournalSettleOutcome.LANDED
        }
        assertEquals(emptyList(), handed)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$dir/$id"), "a young temporary file is not deleted")
    }
}
