package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, phase A breaker round 4 (decisions.md D-23): the round-5 fast path of [Pbkdf2.normalized] copies a
 * passphrase whose characters are all below U+0300 without normalising it. Format v1 freezes "NFC, then UTF-8", so the
 * fast path is correct only if such text is NFC whatever its neighbours are: the KDoc says no character below U+0300 "can
 * combine with a previous one". The round-5 test checks single characters and the whole range as ONE text (each character
 * next to its own successor only); this one checks every ordered pair, which is every neighbourhood NFC can act on between
 * two starters, against the JDK's normalizer.
 */
class Pbkdf2BreakerR4Test {

    @Test
    fun `every ordered pair of characters below U+0300 is already NFC, so the copy without normalising keeps the frozen encoding`() {
        val pair = CharArray(2)
        var checked = 0
        val wrong: MutableList<String> = mutableListOf()
        for (first: Int in 0 until LIMIT) {
            for (second: Int in 0 until LIMIT) {
                pair[0] = first.toChar()
                pair[1] = second.toChar()
                val text = String(pair)
                if (!Normalizer.isNormalized(text, Normalizer.Form.NFC) && wrong.size < 10) {
                    wrong += "U+%04X U+%04X".format(first, second)
                }
                checked++
            }
        }
        assertTrue(wrong.isEmpty(), "pairs below U+0300 that NFC changes (the fast path would derive another key): $wrong")
        assertTrue(checked == LIMIT * LIMIT, "control: every pair was checked ($checked)")

        // Control that the comparison can fail: U+0300 itself combines with the letter before it.
        assertTrue(!Normalizer.isNormalized("è", Normalizer.Form.NFC), "control: e + U+0300 is not NFC")
        // And the fast path really returns the same characters as the normaliser for a mixed Latin text.
        val latin: CharArray = "Ærøskøbing-ÿ-ŉ-ǅ-ʬ-˿- -Ǆ".toCharArray()
        assertContentEquals(Normalizer.normalize(String(latin), Normalizer.Form.NFC).toCharArray(), Pbkdf2.normalized(latin))
    }

    private companion object {
        const val LIMIT: Int = 0x300
    }
}
