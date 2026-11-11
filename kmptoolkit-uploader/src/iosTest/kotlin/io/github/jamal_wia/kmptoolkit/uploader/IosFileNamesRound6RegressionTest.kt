package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.concurrent.AtomicInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileModificationDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/** Round 6 breaker findings R6-S1 to R6-S5 on iOS (red before the round 6 fix, green after). */
@OptIn(ExperimentalForeignApi::class)
class IosFileNamesRound6RegressionTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6s-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    /** docs 05: only an empty id, `.`/`..`, `/`, `\`, space, a control character or more than 200 characters is not journaled. */
    @Test
    fun `an id of at most 200 characters that the journal accepts is journaled whatever its alphabet`() {
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val ids: List<String> = listOf("\u0416".repeat(50), "\u0416".repeat(100), "\u65E5".repeat(60), "ABCDEFGHIJ".repeat(13), "\u00E9".repeat(45))
        val refused: List<Int> = ids.filter { id ->
            isJournalableId(id) && !journal.record(id, UploadResponse(201, emptyMap(), byteArrayOf(1), false), attemptStamp = 0)
        }.map { it.length }
        assertTrue(refused.isEmpty(), "journalable ids (lengths $refused) were not journaled: the escaped file name is longer than the file system allows")
    }

    /** A hash of the UTF-8 bytes: two ids that differ only in a lone surrogate encode the same bytes. */
    @Test
    fun `ids that differ only by a lone surrogate do not share a temporary body file`() {
        assertNotEquals(temporaryBodyFileName("a\uD800"), temporaryBodyFileName("a\uD801"))
    }

    private fun writeRaw(path: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, attributes = null))
    }

    /** Migration: a 4.0.0 entry of `Photo-AbC` still on disk must not be read as the entry of `photo-abc`. */
    @Test
    fun `a leftover 4_0_0 entry of one id is not the entry of the id that differs by case`() {
        writeRaw("$dir/Photo-AbC", byteArrayOf(9, 9))
        val storage = IosOutcomeStorage(dir)
        if (storage.read("Photo-AbC") == null) return
        assertNull(storage.read("photo-abc"), "photo-abc reads the 4.0.0 entry of Photo-AbC")
        assertTrue(storage.write("photo-abc", byteArrayOf(1)))
        assertContentEquals(byteArrayOf(9, 9), storage.read("Photo-AbC"), "writing photo-abc replaced the entry of Photo-AbC")
    }
}

/** Phantom ids from Foundation's own atomic-write files, and a literal id that equals another id's escaped name. */
@OptIn(ExperimentalForeignApi::class)
class IosJournalListingRound6RegressionTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6l-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    /** Resource accounting: a listing taken while entries are rewritten shows only entries, never a file the write itself uses. */
    @Test
    fun `listing the journal during a write storm names no id that was never written`() = runBlocking {
        val storage = IosOutcomeStorage(dir)
        val written: Set<String> = setOf("a-0", "a-1", "a-2", "A-3")
        val phantoms: MutableSet<String> = mutableSetOf()
        withTimeout(60.seconds) {
            val writers = (0 until 3).map { w ->
                CoroutineScope(Dispatchers.Default).async {
                    repeat(1500) { i -> storage.write(written.elementAt((i + w) % written.size), ByteArray(2048) { w.toByte() }) }
                }
            }
            val reader = CoroutineScope(Dispatchers.Default).async {
                val seen: MutableSet<String> = mutableSetOf()
                while (writers.any { it.isActive }) seen += storage.itemIds()
                seen
            }
            writers.awaitAll()
            phantoms += reader.await() - written
        }
        assertEquals(emptySet(), phantoms, "a sweep during a write would replay, inspect or delete these names")
        assertEquals(written.sorted(), storage.itemIds().sorted())
    }


    /** An id that is literally the escaped file name of another id must not be read, replaced or deleted as a legacy entry. */
    @Test
    fun `an id equal to the escaped file name of another id does not touch the other entry`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("A", byteArrayOf(1, 1)))
        val escapedNameOfA: String = journalFileName("A")
        assertNull(storage.read(escapedNameOfA), "the id '$escapedNameOfA' reads the entry of 'A' as its own")
        storage.delete(escapedNameOfA)
        assertContentEquals(byteArrayOf(1, 1), storage.read("A"), "deleting the id '$escapedNameOfA' deleted the entry of 'A'")
        assertTrue(storage.write(escapedNameOfA, byteArrayOf(2, 2)))
        assertContentEquals(byteArrayOf(1, 1), storage.read("A"), "writing the id '$escapedNameOfA' replaced the entry of 'A'")
    }
}

/** The entries of ids whose escaped name is too long for the file system (R6-S1 fix): the id lives in the entry. */
@OptIn(ExperimentalForeignApi::class)
class IosLongIdJournalRound6FixTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6h-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private val longIds: List<String> = listOf(
        "Ж".repeat(50), "Ж".repeat(50) + "x", "日".repeat(60), "ABCDEFGHIJ".repeat(13), "é".repeat(45), "É".repeat(45),
        "Ж".repeat(200),
    )

    @Test
    fun `a long id is read back and listed by its own id and deleted without touching another long id`() {
        val storage = IosOutcomeStorage(dir)
        longIds.forEachIndexed { i, id -> assertTrue(storage.write(id, byteArrayOf(i.toByte(), 7)), "write of id $i") }
        longIds.forEachIndexed { i, id -> assertContentEquals(byteArrayOf(i.toByte(), 7), storage.read(id), "read of id $i") }
        assertEquals(longIds.sorted(), storage.itemIds())
        storage.delete(longIds[0])
        assertNull(storage.read(longIds[0]))
        assertContentEquals(byteArrayOf(1, 7), storage.read(longIds[1]))
        assertEquals(longIds.drop(1).sorted(), storage.itemIds())
        longIds.forEach { id ->
            val name: String = journalFileName(id)
            assertTrue(name.length <= 255 - 20 && name.all { it.code in 0x21..0x7e }, "name of a long id: $name")
        }
    }

    @Test
    fun `an empty payload and a rewrite of a long id keep one entry`() {
        val storage = IosOutcomeStorage(dir)
        val id: String = longIds[0]
        assertTrue(storage.write(id, ByteArray(0)))
        assertContentEquals(ByteArray(0), storage.read(id))
        assertTrue(storage.write(id, byteArrayOf(5)))
        assertContentEquals(byteArrayOf(5), storage.read(id))
        assertEquals(listOf(id), storage.itemIds())
    }

    @Test
    fun `a journal records and inspects a long id and the sweep hands it back`() {
        val journal = OutcomeJournal(IosOutcomeStorage(dir), UploaderClock.System)
        val id: String = longIds[4]
        assertTrue(journal.record(id, UploadResponse(201, emptyMap(), byteArrayOf(1, 2), false), attemptStamp = 0))
        val entry: JournalEntry = journal.inspect(id)
        assertTrue(entry is JournalEntry.Valid, "entry was $entry")
        assertContentEquals(byteArrayOf(1, 2), entry.response.body())
    }
}

/** R6-S3 (a 4.0.0 entry that differs only by case is moved, not replaced) and R6-S5 (Foundation's temporary files). */
@OptIn(ExperimentalForeignApi::class)
class IosJournalMigrationRound6FixTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-r6m-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private fun writeRaw(name: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath("$dir/$name", data, attributes = null))
    }

    @Test
    fun `a 4_0_0 entry of the id that differs by case is moved to its own name before the kept id writes and deletes only its own file`() {
        writeRaw("Photo-AbC", byteArrayOf(9, 9))
        val storage = IosOutcomeStorage(dir)
        if (storage.read("Photo-AbC") == null) return
        storage.delete("photo-abc")
        assertContentEquals(byteArrayOf(9, 9), storage.read("Photo-AbC"), "deleting photo-abc deleted the entry of Photo-AbC")
        assertTrue(storage.write("photo-abc", byteArrayOf(1)))
        assertContentEquals(byteArrayOf(9, 9), storage.read("Photo-AbC"))
        assertContentEquals(byteArrayOf(1), storage.read("photo-abc"))
        assertEquals(listOf("Photo-AbC", "photo-abc"), storage.itemIds())
    }

    @Test
    fun `a name that ends like a Foundation temporary file is never an id and an old leftover is purged`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("a-1", byteArrayOf(1)))
        writeRaw("a-1.sb-0d3a26b6-Bkp1Q5", byteArrayOf(2))
        writeRaw("a-2.sb-0d3a26b6-Zk3x9q", byteArrayOf(3))
        assertEquals(listOf("a-1"), storage.itemIds())
        NSFileManager.defaultManager.setAttributes(
            mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceNow(-3600.0)), ofItemAtPath = "$dir/a-2.sb-0d3a26b6-Zk3x9q", error = null,
        )
        storage.purgeLeftovers()
        val left: List<Any?> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null).orEmpty()
        assertTrue("a-2.sb-0d3a26b6-Zk3x9q" !in left, "an old leftover stayed: $left")
        assertTrue("a-1.sb-0d3a26b6-Bkp1Q5" in left, "a fresh temporary file may be a write in flight: $left")
    }

    @Test
    fun `ids that end like a temporary file name keep their own entries`() {
        val storage = IosOutcomeStorage(dir)
        val ids: List<String> = listOf("x.sb-0d3a26b6-bkp1q5", "X.sb-0d3a26b6-Bkp1Q5")
        ids.forEachIndexed { i, id -> assertTrue(storage.write(id, byteArrayOf(i.toByte()))) }
        ids.forEachIndexed { i, id -> assertContentEquals(byteArrayOf(i.toByte()), storage.read(id)) }
        assertEquals(ids.sorted(), storage.itemIds())
    }
}
