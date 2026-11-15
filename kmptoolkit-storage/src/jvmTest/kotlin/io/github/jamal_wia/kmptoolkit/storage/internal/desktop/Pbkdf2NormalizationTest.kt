package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.test.Test
import kotlin.test.assertContentEquals
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
}
