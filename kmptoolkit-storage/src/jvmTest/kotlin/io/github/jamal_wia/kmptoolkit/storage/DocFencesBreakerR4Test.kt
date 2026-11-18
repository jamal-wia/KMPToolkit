package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Docs breaker round 4 of group sto-docs (STO-12): layouts that the container-aware [DocFences] reader (round 5) gets wrong.
 *
 * - A list item whose text is wrapped onto a line without the item's indentation (a lazy continuation line, CommonMark 0.31
 *   section 5.2) stays open in CommonMark, so a fence indented under the item after a blank line is a Kotlin block in that item.
 *   The reader ends the item at the wrapped line and then reads the fence's four columns of indentation as an indented code block:
 *   the Kotlin block is neither read nor reported, even when it is spelled exactly ```kotlin.
 * - A bare list marker (an empty item `-`, the last `*` of the thematic break `* * *`, the setext underline `-`) makes the reader
 *   throw IllegalArgumentException from `" ".repeat(-1)`, so a legitimate document fails the drift check with a message that names
 *   no line.
 *
 * Each Kotlin document below holds `val broken: Int = "text"`, which does not compile; the check must report it.
 */
class DocFencesBreakerR4Test {

    private val broken: String = """val broken: Int = "text""""

    /** The same notion of "reported" as DocFencesTest: an odd Kotlin fence, or an exact one with no marker. */
    private fun problems(lines: List<String>): List<String> =
        DocFences.oddKotlinFences("doc.md", lines).map { it.location } +
            DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location }

    @Test
    fun `a kotlin fence under a bullet item whose text wraps without indentation is reported`() {
        // CommonMark: "sync the project:" is a lazy continuation of the item's paragraph, the item stays open, and the fence
        // indented by four columns (two past the item's content column) is a fenced Kotlin block inside the item.
        val lines: List<String> = listOf(
            "# Title", "", "- Add the dependency, then", "sync the project:", "", "    ```kotlin", "    $broken", "    ```", "", "end",
        )
        assertTrue(problems(lines).isNotEmpty(), "a Kotlin fence under a lazily continued bullet item is skipped: ${DocFences.scan("doc.md", lines).size} fences read")
    }

    @Test
    fun `a kotlin fence under an ordered item whose second line is indented by two columns is reported`() {
        // CommonMark: the content column of "1. " is 3, so the two-column continuation line is lazy; the item stays open and the
        // fence indented by four columns is a fenced Kotlin block inside the item (indented by one column relative to it).
        val lines: List<String> = listOf(
            "# Title", "", "1. Add the dependency and", "  sync the project:", "", "    ```kotlin", "    $broken", "    ```", "", "end",
        )
        assertTrue(problems(lines).isNotEmpty(), "a Kotlin fence under a lazily continued ordered item is skipped: ${DocFences.scan("doc.md", lines).size} fences read")
    }

    @Test
    fun `a bare list marker a spaced thematic break and a setext underline do not crash the reader`() {
        val listing: List<String> = listOf("<!-- listing -->", "```kotlin", "public interface Listed", "```")
        val documents: Map<String, List<String>> = mapOf(
            "thematic break * * *" to listOf("# Title", "", "Text.", "", "* * *", "") + listing,
            "thematic break - - -" to listOf("# Title", "", "Text.", "", "- - -", "") + listing,
            "setext underline -" to listOf("Section", "-", "") + listing,
            "empty list item" to listOf("- first", "-", "- third", "") + listing,
        )
        for ((name: String, lines: List<String>) in documents) {
            val read: Result<List<String>> = runCatching { DocFences.kotlinFences("doc.md", lines).map { it.location } }
            assertEquals(listOf("doc.md:${lines.size - 2}"), read.getOrElse { throw AssertionError("$name: the reader threw $it", it) }, name)
            assertEquals(emptyList(), problems(lines), name)
        }
    }
}
