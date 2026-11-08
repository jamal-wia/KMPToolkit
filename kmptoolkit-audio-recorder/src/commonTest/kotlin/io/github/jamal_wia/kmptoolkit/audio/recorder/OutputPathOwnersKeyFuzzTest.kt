package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Properties of the registry key over random paths: it is a fixed point of itself, it never keeps a
 * dot segment or a repeated separator, and decorating a path with the spellings the documentation
 * names (repeated separators, `.` segments, a detour through `..`, another letter case, a trailing
 * separator) never changes it. The seed is in every failure message.
 */
class OutputPathOwnersKeyFuzzTest {

    private val names: List<String> = listOf("a", "B", "rec", "Rec", "x.m4a", "...", "..a", "a..", " ", "İ", "kK", "café")

    private fun randomPath(random: Random): String {
        val parts: MutableList<String> = mutableListOf()
        repeat(random.nextInt(0, 6)) {
            parts += when (random.nextInt(6)) {
                0 -> "."
                1 -> ".."
                2 -> ""
                else -> names[random.nextInt(names.size)]
            }
        }
        return "/" + parts.joinToString("/")
    }

    @Test
    fun `the key is a fixed point and has no dot segments and no repeated separators`() {
        val seed = 20_260_508
        val random = Random(seed)
        repeat(3_000) { round: Int ->
            val path: String = randomPath(random)
            val key: String = OutputPathOwners.ownershipKey(path)
            assertEquals(key, OutputPathOwners.ownershipKey(key), "seed $seed round $round path '$path'")
            assertTrue(key.startsWith("/"), "seed $seed round $round key '$key'")
            assertFalse(key.contains("//"), "seed $seed round $round key '$key'")
            val segments: List<String> = key.split('/').filter { it.isNotEmpty() }
            assertFalse(segments.any { it == "." || it == ".." }, "seed $seed round $round key '$key'")
            assertTrue(key == "/" || !key.endsWith("/"), "seed $seed round $round key '$key'")
        }
    }

    @Test
    fun `decorating a path with the documented spellings never changes its key`() {
        val seed = 7_771
        val random = Random(seed)
        repeat(3_000) { round: Int ->
            val path: String = randomPath(random)
            val key: String = OutputPathOwners.ownershipKey(path)
            val decorated: List<String> = listOf(
                "//" + path.removePrefix("/"),
                "/./" + path.removePrefix("/"),
                "/zz/../" + path.removePrefix("/"),
                path + "/",
                path + "/.",
                path + "/zz/..",
                path.replace("/", "//"),
                path.uppercase(),
            )
            for (spelling in decorated) {
                assertEquals(key, OutputPathOwners.ownershipKey(spelling), "seed $seed round $round '$path' vs '$spelling'")
            }
        }
    }
}
