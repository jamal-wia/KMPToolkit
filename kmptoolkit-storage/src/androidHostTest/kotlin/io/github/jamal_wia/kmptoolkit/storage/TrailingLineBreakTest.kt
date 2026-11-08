package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class TrailingLineBreakTest {

    private val values: List<String> = listOf(
        "", "x", "abc\n", "abc\n\n", "\n", "\r\n", "abc\n", "\n", "", "\n",
        "abc\n    ", "a\n", "a\nb", "", "\n\n", "😀\n",
    )

    @Test
    fun `decode inverts encode on every value`() {
        for (value: String in values) {
            assertEquals(value, decodeFromPreferences(encodeForPreferences(value)), "value ${value.map { it.code }}")
        }
    }

    @Test
    fun `an encoded value never ends in a line break`() {
        for (value: String in values) {
            assertFalse(encodeForPreferences(value).endsWith("\n"), "value ${value.map { it.code }}")
        }
    }

    @Test
    fun `encode is injective across the values`() {
        assertEquals(values.size, values.map { encodeForPreferences(it) }.toSet().size)
    }

    @Test
    fun `a value that does not end in a line break and markers is stored and read byte for byte`() {
        for (value: String in listOf("", "x", "abc\n    ", "", "a\n b", "a\nb")) {
            assertSame(value, encodeForPreferences(value))
            assertSame(value, decodeFromPreferences(value))
        }
    }

    @Test
    fun `a trailing line break gains exactly one marker`() {
        assertEquals("abc\n", encodeForPreferences("abc\n"))
        assertEquals("abc\n", encodeForPreferences("abc\n"))
        assertEquals("abc\n", decodeFromPreferences("abc\n"))
        assertEquals("abc\n", decodeFromPreferences("abc\n"))
    }
}
