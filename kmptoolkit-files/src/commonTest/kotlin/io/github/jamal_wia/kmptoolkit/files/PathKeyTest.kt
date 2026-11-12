package io.github.jamal_wia.kmptoolkit.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** K-1..K-3: path keys fold the same spellings on every target. */
class PathKeyTest {
    private fun key(path: String): String = PathKey.of(path, CaseFolding.Always)

    private fun assertOneKey(vararg spellings: String) {
        val expected: String = key(spellings.first())
        for (spelling: String in spellings) assertEquals(expected, key(spelling), "'$spelling'")
    }

    @Test
    fun `sharp s capital sharp s and double s share a key`() {
        assertOneKey("/d/stra\u00dfe", "/d/stra\u1e9ee", "/d/strasse", "/d/STRASSE")
    }

    @Test
    fun `the three sigma forms share a key`() {
        assertOneKey("/d/\u03c3", "/d/\u03c2", "/d/\u03a3")
        assertOneKey("/d/\u03bf\u03b4\u03bf\u03c2", "/d/\u039f\u0394\u039f\u03a3", "/d/\u03bf\u03b4\u03bf\u03c3")
    }

    @Test
    fun `long s and s share a key and the Kelvin sign and k share a key`() {
        assertOneKey("/d/\u017f", "/d/s", "/d/S")
        assertOneKey("/d/\u212a", "/d/k", "/d/K")
    }

    @Test
    fun `NFC and NFD spellings share a key`() {
        assertOneKey("/d/caf\u00e9", "/d/cafe\u0301", "/d/CAFE\u0301")
    }

    @Test
    fun `the key is taken after lexical normalization`() {
        assertEquals(key("/a/c"), key("/a/./b/../c"))
        assertEquals(PathKey.of("/a/c", CaseFolding.Never), PathKey.of("/a/./b/../c", CaseFolding.Never))
    }

    @Test
    fun `Never keeps case and unicode spelling`() {
        assertNotEquals(PathKey.of("/d/A", CaseFolding.Never), PathKey.of("/d/a", CaseFolding.Never))
        assertNotEquals(PathKey.of("/d/caf\u00e9", CaseFolding.Never), PathKey.of("/d/cafe\u0301", CaseFolding.Never))
    }

    @Test
    fun `different files get different keys`() {
        assertNotEquals(key("/d/a"), key("/d/b"))
        assertNotEquals(key("/d/a/b"), key("/d/ab"))
        assertNotEquals(key("/d/stra\u00dfe"), key("/d/strase"))
    }
}
