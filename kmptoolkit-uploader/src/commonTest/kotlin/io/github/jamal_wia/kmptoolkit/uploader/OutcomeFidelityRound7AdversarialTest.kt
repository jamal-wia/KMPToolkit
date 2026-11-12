package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 7 (persisted-data fidelity): everything an outcome carries (status, headers, body bytes, truncation flag, attempt
 * stamp, write time) is recorded through the real file storage of the platform (Android files in the host source set, the
 * iOS volume in the simulator one) and must come back exactly; a 4.0.0 entry (hand-encoded here from the documented
 * format, independent of the codec under test) is read as written; a cut or damaged entry is discarded, never thrown.
 */
class OutcomeFidelityRound7AdversarialTest {

    private val underTest: StorageUnderTest = newStorageUnderTest()
    private var now: Long = 1_000L
    private val journal = OutcomeJournal(underTest.storage, UploaderClock { now })

    @AfterTest
    fun cleanUp() = underTest.cleanUp()

    private fun valid(id: String): JournalEntry.Valid = assertIs<JournalEntry.Valid>(journal.inspect(id), "the entry of $id")

    private fun assertSame(expected: UploadResponse, actual: UploadResponse, label: String) {
        assertEquals(expected.statusCode, actual.statusCode, "$label: status")
        assertEquals(expected.isBodyTruncated, actual.isBodyTruncated, "$label: truncated")
        assertEquals(expected.headers.toList(), actual.headers.toList(), "$label: headers (order included)")
        assertContentEquals(expected.body(), actual.body(), "$label: body")
    }

    @Test
    fun `statuses stamps and write times at the edges of their types come back exactly`() {
        val statuses: List<Int> = listOf(Int.MIN_VALUE, -1, 0, 99, 100, 200, 204, 599, 600, Int.MAX_VALUE)
        val stamps: List<Int?> = listOf(null, 0, 1, Int.MAX_VALUE)
        val times: List<Long> = listOf(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 1_700_000_000_000L)
        var n = 0
        for (status in statuses) for (stamp in stamps) for (time in times) {
            val id = "e${n++}"
            now = time
            val response = UploadResponse(status, mapOf("x-a" to "1"), byteArrayOf(1, 2, 3), isBodyTruncated = n % 2 == 0)
            assertTrue(journal.record(id, response, attemptStamp = stamp), "record $id")
            val entry: JournalEntry.Valid = valid(id)
            assertSame(response, entry.response, "status=$status stamp=$stamp time=$time")
            assertEquals(stamp, entry.attemptStamp, "stamp")
            assertEquals(time, entry.writtenAtMillis, "write time")
        }
    }

    @Test
    fun `every byte value and every size around the chunk edges survive in a body`() {
        val sizes: List<Int> = listOf(0, 1, 255, 256, 257, 4095, 4096, 4097, 8191, 8192, 8193, 65_535, 65_536, 65_537, 1_048_575, 1_048_576)
        val random = Random(7_001)
        for (size in sizes) {
            val body = ByteArray(size) { (if (it < 256) it else random.nextInt(256)).toByte() }
            val response = UploadResponse(200, emptyMap(), body, isBodyTruncated = false)
            assertTrue(journal.record("b$size", response), "record $size")
            assertSame(response, valid("b$size").response, "body of $size bytes")
        }
    }

    @Test
    fun `hostile header names and values come back equal`() {
        val texts: List<String> = listOf(
            "", "a", "é", "é", "مرحبا", "‮abc", "😀", "﻿bom", "￾reversed", "\u0000nul", "tab\tin",
            "line\r\nbreak", "  ", "​‍", "x".repeat(30_000), "日本語".repeat(3_000), "%41", "\\", "\"", "İ", "ẞ",
        )
        for ((index, text) in texts.withIndex()) {
            val response = UploadResponse(200, mapOf("n-${index}x" to text, text.lowercase() to "v", "k$index" to ""), ByteArray(0), false)
            assertTrue(journal.record("h$index", response), "record $index")
            assertSame(response, valid("h$index").response, "header text #$index")
        }
    }

    @Test
    fun `many headers keep their count order and values`() {
        val headers: Map<String, String> = (0 until 2_000).associate { "h-${it.toString().padStart(5, '0')}" to "value $it" }
        val response = UploadResponse(200, headers, ByteArray(10) { it.toByte() }, false)
        assertTrue(journal.record("many", response))
        assertSame(response, valid("many").response, "2000 headers")
    }

    @Test
    fun `a body limit cuts the body to its prefix and says so`() {
        val body = ByteArray(5_000) { (it % 251).toByte() }
        for (limit in listOf(0, 1, 4_999, 5_000, 5_001, -3)) {
            val id = "lim${limit + 3}"
            assertTrue(journal.record(id, UploadResponse(201, emptyMap(), body, false), bodyLimit = limit), "record")
            val got: UploadResponse = valid(id).response
            val kept: Int = limit.coerceIn(0, body.size)
            assertContentEquals(body.copyOf(kept), got.body(), "limit $limit")
            assertEquals(limit < body.size, got.isBodyTruncated, "truncated flag at limit $limit")
        }
    }

    /** The version 1 form (4.0.0 wrote it), encoded by hand from the documented layout, not by the codec. */
    private fun fourZeroEntry(status: Int, writtenAt: Long, truncated: Boolean, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        fun int(v: Int) { for (s in 24 downTo 0 step 8) out += (v ushr s).toByte() }
        fun long(v: Long) { for (s in 56 downTo 0 step 8) out += (v ushr s).toByte() }
        fun bytes(b: ByteArray) { int(b.size); out += b.toList() }
        int(1); int(status); long(writtenAt); out += (if (truncated) 1 else 0).toByte(); int(headers.size)
        headers.forEach { (n, v) -> bytes(n.encodeToByteArray()); bytes(v.encodeToByteArray()) }
        bytes(body)
        return out.toByteArray()
    }

    @Test
    fun `an entry written by 4_0_0 is read as written and has no attempt stamp`() {
        now = 5_000L
        val body = byteArrayOf(0, 1, 2, -1, -128, 127)
        val raw: ByteArray = fourZeroEntry(201, 4_000L, true, listOf("etag" to "\"é\"", "x-y" to ""), body)
        assertTrue(underTest.storage.write("legacy-1", raw))
        val entry: JournalEntry.Valid = valid("legacy-1")
        assertEquals(201, entry.response.statusCode)
        assertEquals(true, entry.response.isBodyTruncated)
        assertEquals(mapOf("etag" to "\"é\"", "x-y" to ""), entry.response.headers)
        assertContentEquals(body, entry.response.body())
        assertEquals(4_000L, entry.writtenAtMillis)
        assertEquals(null, entry.attemptStamp)
    }

    @Test
    fun `every cut of an entry and every flipped byte is discarded or read and never throws`() {
        val raw: ByteArray = fourZeroEntry(200, 900L, false, listOf("a" to "b", "cc" to "dd"), ByteArray(40) { it.toByte() })
        for (cut in 0 until raw.size) {
            assertTrue(underTest.storage.write("cut", raw.copyOf(cut)))
            val entry: JournalEntry = journal.inspect("cut")
            assertTrue(entry is JournalEntry.Discarded || entry is JournalEntry.Absent, "a $cut byte prefix was taken for $entry")
            assertEquals(null, underTest.storage.read("cut"), "the cut entry of $cut bytes was not removed")
        }
        for (position in raw.indices) {
            val damaged: ByteArray = raw.copyOf().also { it[position] = (it[position].toInt() xor 0x5A).toByte() }
            assertTrue(underTest.storage.write("flip", damaged))
            val entry: JournalEntry = journal.inspect("flip")
            assertTrue(entry is JournalEntry.Valid || entry is JournalEntry.Discarded, "byte $position flipped gave $entry")
            journal.delete("flip")
        }
    }

    @Test
    fun `the data of an entry is the same under every kind of file name the platform gives its id`() {
        val ids: List<String> = listOf("a", "A".repeat(200), "\u00E9".repeat(60), "x".repeat(200), "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", "~_~a", "A", "e\u0301".repeat(40), "\u65E5".repeat(60), "Photo.Name-1")
        val random = Random(7_002)
        for ((index, id) in ids.withIndex()) {
            if (!isJournalableId(id)) continue
            val response = UploadResponse(200 + index, mapOf("x-id" to id.length.toString()), ByteArray(random.nextInt(0, 700)) { random.nextInt().toByte() }, index % 2 == 0)
            assertTrue(journal.record(id, response, attemptStamp = index), "record $index")
            val entry: JournalEntry.Valid = valid(id)
            assertSame(response, entry.response, "id #$index")
            assertEquals(index, entry.attemptStamp)
        }
        // Each entry is its own: deleting one leaves the others.
        journal.delete("a")
        assertEquals(null, underTest.storage.read("a"))
        if (underTest.keepsCaseApart) assertTrue(journal.inspect("A") is JournalEntry.Valid)
        val listed: List<String> = underTest.storage.itemIds()
        assertTrue("x".repeat(200) in listed && "a" !in listed, "listing $listed")
    }
}
