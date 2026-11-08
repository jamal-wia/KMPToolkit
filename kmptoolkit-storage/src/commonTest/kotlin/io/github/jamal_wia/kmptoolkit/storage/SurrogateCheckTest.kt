package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurrogateCheckTest {

    @Test
    fun `text with well-formed pairs has no unpaired surrogate`() {
        for (text: String in listOf("", "abc", "😀", "a😀b😀", "\uD83D\uDE00")) {
            assertFalse(text.hasUnpairedSurrogate(), "text of length ${text.length}")
        }
    }

    @Test
    fun `a lone high surrogate is unpaired at any position`() {
        for (text: String in listOf("\uD800", "\uD800a", "a\uD800b", "ab\uD800", "😀\uD800")) {
            assertTrue(text.hasUnpairedSurrogate(), "text of length ${text.length}")
        }
    }

    @Test
    fun `a lone low surrogate is unpaired at any position`() {
        for (text: String in listOf("\uDC00", "\uDC00a", "a\uDC00b", "ab\uDC00", "😀\uDC00")) {
            assertTrue(text.hasUnpairedSurrogate(), "text of length ${text.length}")
        }
    }

    @Test
    fun `a reversed pair is two unpaired surrogates`() {
        assertTrue("\uDE00\uD83D".hasUnpairedSurrogate())
        assertTrue("a\uDE00\uD83Db".hasUnpairedSurrogate())
    }

    @Test
    fun `two high surrogates in a row are unpaired`() {
        assertTrue("\uD83D\uD83D\uDE00".hasUnpairedSurrogate())
    }
}
