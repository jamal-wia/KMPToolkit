package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The journal's string form (round 10, R10-S5): plain UTF-8 for every text UTF-8 can hold, the code unit's own bytes for a lone surrogate. */
class JournalTextRound10Test {

    private fun units(text: String): List<Int> = text.map { it.code }

    @Test
    fun `a text without a lone surrogate has exactly the bytes of plain UTF-8`() {
        for (text in listOf("", "abc", "café", "€", "😀", "x😀y", "�", "￿")) {
            assertContentEquals(text.encodeToByteArray(), encodeJournalText(text), "bytes of ${units(text)}")
            assertEquals(units(text), units(decodeJournalText(encodeJournalText(text))), "round trip of ${units(text)}")
        }
    }

    @Test
    fun `every placement of a lone surrogate round trips unchanged`() {
        val texts: List<String> = listOf(
            "\uD800", "\uDFFF", "a\uD800b", "\uDC00\uD800", "\uD800\uD800", "\uDC00\uDC00", "😀\uDE00", "\uD83D😀",
            "\uD800x😀\uDC00", "é\uDBFF€",
        )
        for (text in texts) {
            assertEquals(units(text), units(decodeJournalText(encodeJournalText(text))), "round trip of ${units(text)}")
        }
    }

    @Test
    fun `bytes an earlier version wrote for a lone surrogate read as they are`() {
        assertEquals(listOf(97, 63, 98), units(decodeJournalText(byteArrayOf(97, 63, 98))))
        assertEquals(listOf(97, 0xFFFD, 98), units(decodeJournalText("a�b".encodeToByteArray())))
    }

    @Test
    fun `a damaged surrogate form decodes to replacement characters on every platform`() {
        val truncated: ByteArray = byteArrayOf(97, 0xED.toByte(), 0xA0.toByte())
        assertEquals(listOf(97, 0xFFFD, 0xFFFD), units(decodeJournalText(truncated)))
        val bad: ByteArray = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x41, 98)
        assertEquals(listOf(0xFFFD, 0xFFFD, 65, 98), units(decodeJournalText(bad)))
    }

    @Test
    fun `an entry with a lone surrogate in a header name and a value decodes to the same response`() {
        val response = UploadResponse(200, mapOf("x-\uD800" to "v\uDC00", "x-b" to "😀"), byteArrayOf(1, 2), true)
        val ok = OutcomeCodec.decode(OutcomeCodec.encode(response, 9L, 4)) as DecodedOutcome.Ok
        assertEquals(response, ok.response)
        assertEquals(4, ok.attemptStamp)
    }
}
