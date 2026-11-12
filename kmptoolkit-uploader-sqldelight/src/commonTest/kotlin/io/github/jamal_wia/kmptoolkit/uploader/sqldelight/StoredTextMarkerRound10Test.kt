package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.test.assertEquals

/** Round 10, R10-S2 (ruling D-28): a stored text is decoded only when it is exactly the form the writer produces for what it decodes to. */
class StoredTextMarkerRound10Test {

    private val marker2 = "\uFDD0kmp2\uFDD1"
    private val marker1 = "\uFDD0kmp1\uFDD1"

    private fun units(text: String): List<String> = text.map { it.code.toString(16) }

    @Test
    fun `a text an earlier version stored that only starts with a marker is read as it is`() {
        listOf(
            "${marker2}hello", marker2, "${marker2}hello\u0081", marker2 + "\u0081z", marker2 + "\u0080\u0081", "${marker1}\uFDD10041 tail", marker1, "${marker1}x",
            "${marker1}\uFDD1004",
        ).forEach { raw -> assertEquals(units(raw), units(fromStoredText(raw)), "changed on read: ${units(raw)}") }
    }

    @Test
    fun `a text that is exactly an escaped form reads as the text it encodes`() {
        assertEquals(units("\uFEFF\u0000\u0080"), units(fromStoredText(marker2 + "\u0081m\u0080\u00810")))
        assertEquals(units("a\u0000"), units(fromStoredText(marker1 + "a\uFDD10000")))
    }

    @Test
    fun `every written form reads back to its text for both marker forms`() {
        val corpus: List<String> = listOf(
            "\uFEFF", "a\u0000b", "\uD800", "x\uDC00y", marker2, marker1, marker2 + "\u0000", "\uFFFE", "\u0081", "\u0080\u0000\u0081",
            "${marker2}hello", "${marker1}\uFDD1", "\uFEFF\u0000\uD800\uFDD1",
        )
        for (text in corpus) assertEquals(units(text), units(fromStoredText(toStoredText(text))), "round trip of ${units(text)}")
    }

    @Test
    fun `a snapshot row in the first escaped form is still read`() {
        assertEquals(units("\u0000"), units(fromStoredText(marker1 + "\uFDD10000")))
        assertEquals(units("\uFEFFab"), units(fromStoredText(marker1 + "\uFDD1feffab")))
        assertEquals(units("a\uD800b"), units(fromStoredText(marker1 + "a\uFDD1d800b")))
    }
}
