package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Docs breaker round 3 of group sto-docs (STO-12): fence spellings that CommonMark (and so GitHub) renders as a Kotlin code block
 * but that [DocFences] does not see, so that [DocsSamplesDriftTest] stays green on a snippet no build ever compiled. 06-testing.md
 * says the fences "are read the way CommonMark reads them" and that a Kotlin fence spelled any other way than exactly ```kotlin
 * fails the test. Each document below holds `val broken: Int = "text"`, which does not compile; the check must report it.
 */
class DocFencesBreakerR3Test {

    private val broken: String = """val broken: Int = "text""""

    private val markedListing: List<String> = listOf("<!-- listing -->", "```kotlin", "public interface Listed", "```", "", "end")

    /** The same notion of "reported" as DocFencesTest: an odd Kotlin fence, or an exact one with no marker. */
    private fun problems(lines: List<String>): List<String> =
        DocFences.oddKotlinFences("doc.md", lines).map { it.location } +
            DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location }

    @Test
    fun `a kotlin fence inside a block quote is reported`() {
        // CommonMark: a fenced code block is a leaf block that may sit in a block quote; GitHub renders it as Kotlin.
        val lines: List<String> = listOf("# Title", "", "> **Note.** Do this:", ">", "> ```kotlin", "> $broken", "> ```", "", "end")
        assertTrue(problems(lines).isNotEmpty(), "a Kotlin fence in a block quote is skipped by the drift check: ${problems(lines)}")
    }

    @Test
    fun `a kotlin fence that opens a bullet list item on the same line is reported`() {
        // CommonMark: a list item may begin with a fenced code block ("- ```kotlin"); its content lines are indented by the marker width.
        // A marked listing follows, as in 04-api-reference.md: the item's closing line is then read as an opening and swallows the
        // listing, so nothing throws and both fences vanish from the check (proven on the real docs, log r48-3).
        val lines: List<String> = listOf("# Title", "", "- ```kotlin", "  $broken", "  ```", "") + markedListing
        assertTrue(problems(lines).isNotEmpty(), "a Kotlin fence opening a bullet item is skipped by the drift check: ${problems(lines)}")
    }

    @Test
    fun `a kotlin fence that opens an ordered list item on the same line is reported`() {
        val lines: List<String> = listOf("# Title", "", "1. ```kotlin", "   $broken", "   ```", "") + markedListing
        assertTrue(problems(lines).isNotEmpty(), "a Kotlin fence opening an ordered item is skipped by the drift check: ${problems(lines)}")
    }

    @Test
    fun `an indented code block that shows fence characters does not swallow the real kotlin fence after it`() {
        // CommonMark: outside a list, a line indented by four spaces after a blank line is an indented code block, not a fence opening.
        // The real ```kotlin fence that follows is a Kotlin block with no marker and must be reported.
        val lines: List<String> = listOf("# Title", "", "A Markdown example:", "", "    ```text", "    shown", "", "```kotlin", broken, "```", "", "end")
        assertEquals(listOf("doc.md:8"), problems(lines), "the indented code block was read as a fence and hid the Kotlin fence")
    }

    @Test
    fun `control - Windows line endings do not hide a fence`() {
        // A document split on \n only keeps a trailing \r on every line; the opening must still be read (green today).
        val lines: List<String> = "# Title\r\n\r\n```kotlin\r\n$broken\r\n```\r\n".split("\n")
        assertEquals(listOf("doc.md:3"), problems(lines))
    }
}
