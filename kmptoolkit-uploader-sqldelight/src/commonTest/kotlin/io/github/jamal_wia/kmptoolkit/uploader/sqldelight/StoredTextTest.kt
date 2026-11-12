package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StoredTextTest {

    private val odd: List<String> = listOf(
        "﻿", "﻿ab", "￾ab", "a\u0000b", "\u0000", "a\uD800b", "\uDC00", "ab\uD800", "\uD800\uD800", "\uDC00\uD800", "😀\uDE00",
        "﷐kmp1﷑", "﷐kmp1﷑fdd1", "﻿﻿", "﻿\u0000\uD800﷑",
    )

    private val untouched: List<String> = listOf(
        "", "a", "  ", "\u0080a\u0081", "﷑", "a﷑b", "a﻿b", "a￾b", "a￿b", "😀", "x😀y", "﷐kmp1", "﷐kmp1x", "\r\n", "�", "é",
    )

    @Test
    fun `a text the drivers keep is stored as it is`() {
        untouched.forEach { assertEquals(it, toStoredText(it), "changed: ${it.map { c -> c.code.toString(16) }}") }
    }

    @Test
    fun `a text the drivers change is stored escaped and comes back as it was`() {
        odd.forEach { text ->
            val stored: String = toStoredText(text)
            assertNotEquals(text, stored)
            assertTrue(stored.none { it == '\u0000' || it.isSurrogate() && !stored.hasPairAround(it) }, "stored form still holds a risky unit")
            assertTrue(!stored.startsWith("﻿") && !stored.startsWith("￾"))
            assertEquals(text, fromStoredText(stored))
        }
    }

    @Test
    fun `a stored text that is not well formed is returned as it is`() {
        val marker = "\uFDD0kmp2\uFDD1"
        listOf("\u0081", "\u0081z", "\u008180", "\u0081x00", "\u0081ABC", "\u0081d8", "\u00812").forEach {
            assertEquals(marker + it, fromStoredText(marker + it))
        }
        val legacy = "\uFDD0kmp1\uFDD1"
        listOf("\uFDD1", "\uFDD1zz", "\uFDD100").forEach { assertEquals(legacy + it, fromStoredText(legacy + it)) }
    }

    @Test
    fun `a text stored by the first 4_1_0 snapshots is still read as it was`() {
        val legacy = "\uFDD0kmp1\uFDD1"
        assertEquals("\uD800", fromStoredText(legacy + "\uFDD1d800"))
        assertEquals("a\u0000b", fromStoredText(legacy + "a\uFDD10000b"))
        assertEquals("\uFEFFab", fromStoredText(legacy + "\uFDD1feffab"))
        assertEquals("\uFDD1", fromStoredText(legacy + "\uFDD1fdd1"))
    }

    @Test
    fun `a stored text is never more than twice the size of the text plus its marker`() {
        val units: List<String> = listOf("\u0000", "\uD800", "\uDFFF", "\u0080", "\u0081", "a", "\u00e9", "\u20ac", "\uD83D\uDE00")
        units.forEach { unit ->
            val text: String = unit.repeat(1000)
            assertTrue(
                utf8Length(toStoredText(text)) <= 2 * utf8Length(text) + 10,
                "'${unit.map { c -> c.code.toString(16) }}' grows more than twice",
            )
        }
        assertTrue(utf8Length(toStoredText("\uFEFF")) <= 2 * utf8Length("\uFEFF") + 10)
    }

    @Test
    fun `random texts round trip and two different texts never share a stored form`() {
        val random = Random(6)
        val alphabet: List<Char> = listOf('a', 'b', '\u0000', '﻿', '￾', '￿', '\uD800', '\uDC00', '\uD83D', '\uDE00', '﷐', '﷑', 'k', '1', 'f', '\u0080', '\u0081', 'm', 'n', '0', '2')
        val seen: MutableMap<String, String> = mutableMapOf()
        repeat(20_000) {
            val text: String = buildString { repeat(random.nextInt(0, 9)) { append(alphabet.random(random)) } }
            val stored: String = toStoredText(text)
            assertEquals(text, fromStoredText(stored))
            val other: String? = seen.put(stored, text)
            assertTrue(other == null || other == text, "'$text' and '$other' share one stored form")
        }
    }

    private fun String.hasPairAround(c: Char): Boolean = indices.filter { this[it] == c }.all { i ->
        (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) || (c.isLowSurrogate() && i > 0 && this[i - 1].isHighSurrogate())
    }
}
