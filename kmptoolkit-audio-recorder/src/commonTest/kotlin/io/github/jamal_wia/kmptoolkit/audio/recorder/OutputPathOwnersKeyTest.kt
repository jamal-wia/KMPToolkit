package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The registry key leans towards matching: every spelling of one path maps to one key. */
class OutputPathOwnersKeyTest {

    @Test
    fun `spellings of one path share a key`() {
        val spellings: List<String> = listOf("/a/b", "/a//b", "/a/./b", "/a/x/../b", "/A/B", "/a/b/", "//a/b")
        for (spelling in spellings) {
            assertEquals("/a/b", OutputPathOwners.ownershipKey(spelling), "key of $spelling")
        }
    }

    @Test
    fun `parent segments above the root are dropped`() {
        assertEquals("/a", OutputPathOwners.ownershipKey("/../a"))
    }

    @Test
    fun `different paths keep different keys`() {
        assertNotEquals(OutputPathOwners.ownershipKey("/a/b"), OutputPathOwners.ownershipKey("/a/c"))
        assertNotEquals(OutputPathOwners.ownershipKey("/a/b"), OutputPathOwners.ownershipKey("/a/b/c"))
    }
}
