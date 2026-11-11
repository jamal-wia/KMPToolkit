package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 6 (docs versus code): docs 05 "Ids" says an id the journal cannot use as a file name (empty, `.`, `..`, containing
 * `/`, `\`, a space or a control character, or longer than 200 characters) is uploaded but not journaled, which promises
 * that every other id is. Pinned on the Android storage with ids of every alphabet, and the resources a failed write
 * must not leave behind.
 */
class JournalIdsRound6ContractTest {

    private val root: File = Files.createTempDirectory("r6-ids").toFile()
    private val dir: File = File(root, "outcomes")
    private val partial: File = File(root, "partial")
    private val journal = OutcomeJournal(AndroidOutcomeStorage(dir, partial), UploaderClock.System)
    private val response = UploadResponse(201, mapOf("a" to "b"), byteArrayOf(1, 2, 3), false)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun roundTrips(id: String): Boolean {
        if (!journal.record(id, response, attemptStamp = 4)) return false
        val entry: JournalEntry = journal.inspect(id)
        return entry is JournalEntry.Valid && entry.response.statusCode == 201 && entry.attemptStamp == 4
    }

    @Test
    fun `ids of every alphabet within the documented limits are journaled and read back`() {
        val ids: List<String> = listOf(
            "a", "A", "Photo-AbC", "item.1", ".hidden", "-lead", "~tilde", "x".repeat(200), "X".repeat(200), "%41", "a:b", "a*b", "a?b", "a|b",
            "a<b>", "a\"b", "CON", "NUL", "trailing.", "\u00E9", "e\u0301", "\u65E5\u672C", "\u0424\u043E\u0442\u043E", "\uD83D\uDE00", "a\u00A0b", "a\u2028b", "a\u200Bb",
            "\u0416".repeat(40), "\u00E9".repeat(60), "tenant-7:photo|42",
        )
        val failed: List<String> = ids.filterNot { id -> isJournalableId(id) && roundTrips(id) || !isJournalableId(id) }
        assertEquals(emptyList(), failed, "journalable ids that did not round trip")
        ids.filter(::isJournalableId).forEach { id -> assertIs<JournalEntry.Valid>(journal.inspect(id), "'$id' lost its entry") }
        assertTrue(ids.count(::isJournalableId) >= 28, "the journalability filter now refuses more ids than docs 05 lists")
    }

    @Test
    fun `an id the documentation excludes is refused without touching the disk`() {
        listOf("", ".", "..", "a/b", "a\\b", "a b", "a\nb", "a\u007fb", "x".repeat(201)).forEach { id ->
            assertFalse(journal.record(id, response), "'${id.replace("\n", "\\n")}' was journaled")
        }
        assertEquals(emptyList(), dir.list()?.toList().orEmpty())
        assertEquals(emptyList(), partial.list()?.toList().orEmpty())
    }

    @Test
    fun `a write that fails for a long name leaves no partial file and no entry`() {
        val id: String = "\u0436".repeat(180)
        journal.record(id, response)
        assertEquals(emptyList(), partial.list()?.toList().orEmpty(), "a failed write left a partial file")
        assertTrue(dir.list()?.toList().orEmpty().all { it == id }, "a failed write left a foreign file in the journal")
    }

    /** The bytes of one entry are exactly what the codec wrote: a sibling id's entry is never read for another. */
    @Test
    fun `entries of ids that share a prefix never read each other`() {
        val ids: List<String> = listOf("a", "a.", "a.b", "a-", "a2", "a.b.c")
        ids.forEachIndexed { i, id -> assertTrue(journal.record(id, UploadResponse(200 + i, emptyMap(), byteArrayOf(i.toByte()), false))) }
        ids.forEachIndexed { i, id ->
            val entry = journal.inspect(id)
            assertIs<JournalEntry.Valid>(entry)
            assertEquals(200 + i, entry.response.statusCode)
            assertContentEquals(byteArrayOf(i.toByte()), entry.response.body())
        }
        assertEquals(ids.sorted(), AndroidOutcomeStorage(dir, partial).itemIds())
    }
}
