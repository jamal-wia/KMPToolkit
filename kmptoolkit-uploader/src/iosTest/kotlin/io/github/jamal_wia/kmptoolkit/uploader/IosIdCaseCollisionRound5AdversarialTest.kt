package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create

/**
 * Two item ids that differ only by letter case name the same file on a case-insensitive volume (the default for an
 * iOS app's data container and for macOS). The id is the consumer's (`idGenerator`) and the library puts no alphabet
 * restriction on it (the temporary body name keeps a plain id as it is, the journal names its entry file after the
 * id), so two such items must not share a temporary body file nor a journal entry. The migration tests pin that an
 * entry an earlier version wrote under an id's own spelling stays readable.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosIdCaseCollisionRound5AdversarialTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-case-" + NSUUID().UUIDString()

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, error = null)
    }

    private fun writeRaw(path: String, bytes: ByteArray) {
        NSFileManager.defaultManager.createDirectoryAtPath(root + "/outcomes", withIntermediateDirectories = true, attributes = null, error = null)
        val data: NSData = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.convert()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, data, attributes = null), "could not create $path")
    }

    @Test
    fun `ids that differ only by case do not share a temporary body file name`() {
        val upper: String = temporaryBodyFileName("Photo-AbC")
        val lower: String = temporaryBodyFileName("photo-abc")
        assertNotEquals(
            upper.lowercase(),
            lower.lowercase(),
            "'Photo-AbC' and 'photo-abc' write their multipart bodies to $upper and $lower: one file on a case-insensitive volume, " +
                "so concurrent uploads of the two items would send each other's bytes",
        )
    }

    @Test
    fun `ids that differ only by case do not share a journal entry on a case-insensitive volume`() {
        val storage = IosOutcomeStorage(root + "/outcomes")
        assertTrue(storage.write("Photo-AbC", byteArrayOf(1, 1, 1)))
        val foreign: ByteArray? = storage.read("photo-abc")
        if (foreign == null) return // a case-sensitive volume: nothing to collide
        storage.write("photo-abc", byteArrayOf(2, 2, 2))
        assertContentEquals(byteArrayOf(1, 1, 1), storage.read("Photo-AbC"), "the entry of 'Photo-AbC' was replaced by the one of 'photo-abc'")
    }

    /** Not vacuous on any volume: each id reads back its own entry whatever the volume does with letter case. */
    @Test
    fun `entries of ids that differ only by case stay apart and are listed under their own ids`() {
        val storage = IosOutcomeStorage(root + "/outcomes")
        assertTrue(storage.write("Photo-AbC", byteArrayOf(1, 1, 1)))
        assertTrue(storage.write("photo-abc", byteArrayOf(2, 2, 2)))
        assertTrue(storage.write("PHOTO-ABC", byteArrayOf(3, 3, 3)))

        assertContentEquals(byteArrayOf(1, 1, 1), storage.read("Photo-AbC"))
        assertContentEquals(byteArrayOf(2, 2, 2), storage.read("photo-abc"))
        assertContentEquals(byteArrayOf(3, 3, 3), storage.read("PHOTO-ABC"))
        assertEquals(listOf("PHOTO-ABC", "Photo-AbC", "photo-abc"), storage.itemIds())

        storage.delete("Photo-AbC")
        assertNull(storage.read("Photo-AbC"))
        assertContentEquals(byteArrayOf(2, 2, 2), storage.read("photo-abc"))
        assertContentEquals(byteArrayOf(3, 3, 3), storage.read("PHOTO-ABC"))
    }

    @Test
    fun `an id maps to a file name and back for every kind of id and names stay apart case-insensitively`() {
        val ids: List<String> = listOf(
            "item-1", "a", "Photo-AbC", "photo-abc", "PHOTO-ABC", "a~b", "a~~0041", "~", "x~y~z", "café", "CAFÉ",
            "tenant-7:photo|42", "UPPER", "MiXeD.cAsE_id-1", "~a", "~~0041", "A".repeat(100),
        )
        val names: List<String> = ids.map(::journalFileName)
        ids.zip(names).forEach { (id, name) ->
            assertEquals(id, itemIdOfJournalFileName(name), "'$id' was written as '$name' and read back as another id")
            assertTrue('/' !in name && name != "." && name != "..", "'$name' is not a usable file name")
        }
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size, "two ids share a name once letter case is folded: $names")
    }

    @Test
    fun `a default id and a plain lower-case id keep the file names they always had`() {
        val uuid: String = NSUUID().UUIDString()
        assertEquals(uuid, journalFileName(uuid))
        assertEquals("kmptoolkit_upload_$uuid.tmp", temporaryBodyFileName(uuid))
        assertEquals("item-1", journalFileName("item-1"))
        assertEquals("kmptoolkit_upload_item-1.tmp", temporaryBodyFileName("item-1"))
    }

    @Test
    fun `the same uuid in both letter cases is two ids with two names`() {
        val upper: String = NSUUID().UUIDString()
        val lower: String = upper.lowercase()
        assertNotEquals(journalFileName(upper).lowercase(), journalFileName(lower).lowercase())
        assertNotEquals(temporaryBodyFileName(upper).lowercase(), temporaryBodyFileName(lower).lowercase())
    }

    /** Migration: an entry an earlier version wrote under the id's own spelling is read, listed, replaced and deleted. */
    @Test
    fun `an entry written under the id's own spelling by an earlier version stays readable`() {
        val dir: String = root + "/outcomes"
        writeRaw("$dir/Photo-AbC", byteArrayOf(9, 9))
        val storage = IosOutcomeStorage(dir)

        assertContentEquals(byteArrayOf(9, 9), storage.read("Photo-AbC"))
        assertEquals(listOf("Photo-AbC"), storage.itemIds())

        assertTrue(storage.write("Photo-AbC", byteArrayOf(7)))
        assertContentEquals(byteArrayOf(7), storage.read("Photo-AbC"))
        assertEquals(listOf("Photo-AbC"), storage.itemIds(), "the superseded entry must not be listed a second time")

        writeRaw("$dir/Other-Id", byteArrayOf(5))
        storage.delete("Other-Id")
        assertNull(storage.read("Other-Id"))
        assertEquals(listOf("Photo-AbC"), storage.itemIds())
    }

    /** The journal over the storage: an entry of a mixed-case id is journaled, inspected and swept under its own id. */
    @Test
    fun `the journal records and sweeps entries of ids that differ only by case under their own ids`() {
        val journal = OutcomeJournal(IosOutcomeStorage(root + "/outcomes"), UploaderClock.System)
        assertTrue(journal.record("Photo-AbC", UploadResponse(201, emptyMap(), byteArrayOf(1), false), attemptStamp = 0))
        assertTrue(journal.record("photo-abc", UploadResponse(500, emptyMap(), byteArrayOf(2), false), attemptStamp = 3))

        val first: JournalEntry = journal.inspect("Photo-AbC")
        val second: JournalEntry = journal.inspect("photo-abc")
        assertTrue(first is JournalEntry.Valid && first.response.statusCode == 201 && first.attemptStamp == 0, "got $first")
        assertTrue(second is JournalEntry.Valid && second.response.statusCode == 500 && second.attemptStamp == 3, "got $second")
    }
}
