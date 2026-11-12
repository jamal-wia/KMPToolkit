package io.github.jamal_wia.kmptoolkit.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Round 1 of unit G, G4: the folding rule decomposes first (NFD, lowercase, uppercase, lowercase, NFC), so the Greek
 * letters with an iota subscript share a key with their decomposed spelling. Common test: runs on every target, so the
 * Kotlin/Native case mapping of iOS is covered too.
 */
class PathKeyNfdFoldingR1FixTest {

    private fun key(name: String): String = PathKey.of("/d/$name", CaseFolding.Always)

    private fun assertOneKey(vararg names: String) {
        val expected: String = key(names.first())
        for (name: String in names) assertEquals(expected, key(name), "'${name.map { it.code.toString(16) }}'")
    }

    @Test
    fun `the iota subscript letters share a key with their decomposed spelling`() {
        assertOneKey("ᾷ", "ᾼ͂")
        assertOneKey("ῇ", "ῌ͂")
        assertOneKey("ῷ", "ῼ͂")
    }

    @Test
    fun `the documented groups still share a key`() {
        assertOneKey("straße", "straẞe", "strasse", "STRASSE")
        assertOneKey("σ", "ς", "Σ")
        assertOneKey("ſ", "s", "S")
        assertOneKey("K", "k", "K")
        assertOneKey("café", "café", "CAFÉ")
        assertOneKey("Å", "Å", "Å", "å")
        assertOneKey("Ǆ", "ǅ", "ǆ")
    }

    @Test
    fun `different names still have different keys`() {
        assertNotEquals(key("a"), key("b"))
        assertNotEquals(key("ᾷ"), key("Α"))
    }

    @Test
    fun `the key is stable when folded again`() {
        for (name: String in listOf("ᾷ", "straße", "ς", "café")) {
            val once: String = key(name)
            assertEquals(once, PathKey.of(once, CaseFolding.Always), "'$name'")
        }
    }
}
