package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 4 (phase A breaker round 2, m3): an already-NFC passphrase is copied into a character array the
 * caller can wipe (no immutable String of it is made), and NFD text still normalises to the same characters.
 */
class Pbkdf2NormalizationTest {

    @Test
    fun `an NFC passphrase is copied into a fresh array and wiping the copy leaves the original`() {
        val original: CharArray = "correct horse café".toCharArray()
        val copy: CharArray = Pbkdf2.normalized(original)

        assertNotSame(original, copy)
        assertContentEquals(original, copy)
        copy.fill(' ')
        assertTrue(original.concatToString().endsWith("café"), "wiping the copy must not touch the caller's array")
    }

    @Test
    fun `an NFD passphrase normalises to the NFC characters`() {
        val nfd: CharArray = "café".toCharArray()

        assertContentEquals("café".toCharArray(), Pbkdf2.normalized(nfd))
        assertContentEquals("café".toCharArray(), "café".toCharArray().let(Pbkdf2::normalized))
    }

    // Group sto-desktop, round 5 (phase A breaker round 3, m2): the fast path no longer asks the JDK, which would copy the
    // passphrase into an immutable String even for NFC text. The rule it uses is pinned against the JDK here.

    @Test
    fun `every character below U+0300 is its own NFC form and the whole run is NFC`() {
        val all: String = (0 until FIRST_COMBINING).map { it.toChar() }.joinToString("")

        assertTrue(Normalizer.isNormalized(all, Normalizer.Form.NFC), "the fast path assumes this range is NFC as one text")
        for (code: Int in 0 until FIRST_COMBINING) {
            val one: String = code.toChar().toString()
            assertEquals(one, Normalizer.normalize(one, Normalizer.Form.NFC), "U+${code.toString(16)} changes under NFC")
        }
        assertContentEquals(all.toCharArray(), Pbkdf2.normalized(all.toCharArray()))
    }

    @Test
    fun `text from U+0300 up still goes through the normalizer`() {
        assertContentEquals("\u00e9".toCharArray(), Pbkdf2.normalized("e\u0301".toCharArray()), "e + acute")
        assertContentEquals("\u00e8".toCharArray(), Pbkdf2.normalized("e\u0300".toCharArray()), "e + grave: U+0300 itself is outside the fast path")
        assertContentEquals("\uAC00".toCharArray(), Pbkdf2.normalized("\u1100\u1161".toCharArray()), "Hangul jamo compose")
        assertContentEquals("a\u02ff".toCharArray(), Pbkdf2.normalized("a\u02ff".toCharArray()), "U+02FF is the last character of the fast path")
        assertContentEquals("\u043f\u0430\u0440\u043e\u043b\u044c".toCharArray(), Pbkdf2.normalized("\u043f\u0430\u0440\u043e\u043b\u044c".toCharArray()), "NFC Cyrillic is unchanged")
        assertContentEquals("\uD83D\uDE00".toCharArray(), Pbkdf2.normalized("\uD83D\uDE00".toCharArray()), "a surrogate pair is unchanged")
    }

    @Test
    fun `an unpaired surrogate is kept so the strict check can refuse it`() {
        val text: CharArray = "pass\uD800word".toCharArray()

        assertContentEquals(text, Pbkdf2.normalized(text))
    }

    @Test
    fun `an empty passphrase normalises to an empty array`() {
        assertEquals(0, Pbkdf2.normalized(CharArray(0)).size)
    }

    private companion object {
        const val FIRST_COMBINING: Int = 0x300
    }
}
