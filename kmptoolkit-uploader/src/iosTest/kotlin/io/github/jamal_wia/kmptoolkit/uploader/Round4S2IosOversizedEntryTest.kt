package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset

/** An entry file larger than the bound is damaged on iOS too: deleted without being read, never an out-of-memory abort. */
@OptIn(ExperimentalForeignApi::class)
class Round4S2IosOversizedEntryTest {

    private val dir: String = NSTemporaryDirectory() + "/kmptoolkit-r4-big-" + NSUUID().UUIDString() + "/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }

    private fun createFileOfSize(itemId: String, size: Long) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$itemId"
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
        val handle: NSFileHandle = assertNotNull(NSFileHandle.fileHandleForWritingAtPath(path))
        handle.truncateFileAtOffset(size.toULong())
        handle.closeFile()
    }

    @Test
    fun `an entry file larger than the bound is discarded`() {
        createFileOfSize("item-1", MAX_ENCODED_ENTRY_BYTES + 1)

        val seen: JournalEntry = OutcomeJournal(IosOutcomeStorage(dir)).inspect("item-1")

        assertIs<JournalEntry.Discarded>(seen)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$dir/item-1"), "the poison file must be deleted")
    }

    @Test
    fun `the storage answers an oversized entry with an empty array instead of its bytes`() {
        createFileOfSize("item-2", MAX_ENCODED_ENTRY_BYTES + 1)

        assertEquals(0, assertNotNull(IosOutcomeStorage(dir).read("item-2")).size)
    }

    /** Guard, no red-before state: an entry at the bound is still read in full. */
    @Test
    fun `an entry exactly at the bound is still read`() {
        createFileOfSize("item-3", MAX_ENCODED_ENTRY_BYTES)

        assertEquals(MAX_ENCODED_ENTRY_BYTES, assertNotNull(IosOutcomeStorage(dir).read("item-3")).size.toLong())
    }
}
