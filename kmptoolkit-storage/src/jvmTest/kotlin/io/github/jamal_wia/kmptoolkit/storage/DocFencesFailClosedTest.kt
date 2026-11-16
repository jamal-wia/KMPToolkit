package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fail-closed rule of [DocFences] (docs round 6): a line the reader does not understand that holds three backticks or three
 * tildes is refused with `file:line: unrecognised fence-like line`, never skipped, so the drift check cannot pass because a layout
 * was misread. One case per layout the reviewers named (lazy continuation, HTML block, tab after a marker, thematic break,
 * setext underline, empty item), a case per kind of refused line, and a seeded fuzz over layout tokens.
 */
class DocFencesFailClosedTest {

    private val broken: String = """val broken: Int = "text""""

    /** The same notion of "reported" as DocFencesTest: an odd Kotlin fence, or an exact one with no marker. */
    private fun problems(lines: List<String>): List<String> =
        DocFences.oddKotlinFences("doc.md", lines).map { it.location } +
            DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location }

    private fun refusal(lines: List<String>): String =
        assertFailsWith<IllegalStateException> { DocFences.scan("doc.md", lines) }.message.orEmpty()

    // Layouts that are read (the lazy continuation fix).

    @Test
    fun `a fence under an item whose text wraps without indentation is read in the item`() {
        val bullet: List<String> = listOf("- Add the dependency, then", "sync the project:", "", "    ```kotlin", "    $broken", "    ```", "", "end")
        assertEquals(listOf("doc.md:4"), problems(bullet))
        assertEquals(true, DocFences.scan("doc.md", bullet).single().inContainer)
        val ordered: List<String> = listOf("1. Add the dependency and", "  sync the project:", "", "    ```kotlin", "    $broken", "    ```")
        assertEquals(listOf("doc.md:4"), problems(ordered))
    }

    @Test
    fun `a lazy line that opens a fence or a heading ends the item`() {
        val lines: List<String> = listOf("- item text", "```kotlin", broken, "```", "- other text", "# Heading", "```kotlin", broken, "```")
        val fences: List<DocFence> = DocFences.scan("doc.md", lines)
        assertEquals(listOf(2, 7), fences.map { it.line })
        assertEquals(listOf(false, false), fences.map { it.inContainer })
    }

    @Test
    fun `a line that lacks the indentation after a blank line ends the item`() {
        val lines: List<String> = listOf("- item text", "", "back at the margin", "", "    ```text", "    shown", "", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:8"), problems(lines))
    }

    // Layouts that are refused.

    @Test
    fun `a lazy continuation line that holds three backticks is refused`() {
        val message: String = refusal(listOf("- Add the dependency, then", "see ```kotlin``` inline", "", "end"))
        assertTrue(message.startsWith("doc.md:2: unrecognised fence-like line"), message)
    }

    @Test
    fun `an HTML comment that holds a fence is refused where it would swallow the next fence`() {
        // CommonMark ends the HTML block at the line with the comment end, so the fence after it is a Kotlin block; the reader would
        // read the line inside the comment as an opening and swallow it. It is refused instead.
        val message: String = refusal(listOf("# Title", "", "<!--", "```", "-->", "```kotlin", broken, "```"))
        assertTrue(message.startsWith("doc.md:4: unrecognised fence-like line"), message)
        assertTrue(message.contains("line 3"), message)
    }

    @Test
    fun `an HTML comment without fence characters is read through and the fence after it is read`() {
        val lines: List<String> = listOf("# Title", "", "<!--", "a note", "-->", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:6"), problems(lines))
    }

    @Test
    fun `a fence directly after an HTML tag line is refused and after a blank line is read`() {
        assertTrue(refusal(listOf("<details>", "```kotlin", broken, "```", "</details>")).startsWith("doc.md:2: unrecognised fence-like line"))
        assertEquals(listOf("doc.md:3"), problems(listOf("<details>", "", "```kotlin", broken, "```", "", "</details>")))
        assertTrue(refusal(listOf("<pre>", "", "```", "</pre>")).startsWith("doc.md:3: unrecognised fence-like line"))
        assertEquals(listOf("doc.md:5"), problems(listOf("<pre>", "", "x", "</pre>", "```kotlin", broken, "```")))
    }

    @Test
    fun `a one-line marker comment before a fence is not an open HTML block`() {
        val lines: List<String> = listOf("<!-- listing -->", "```kotlin", "public interface Listed", "```", "<!-- sample: x --> trailing", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:2", "doc.md:6"), DocFences.scan("doc.md", lines).map { it.location })
    }

    @Test
    fun `a tab after a list marker or a quote marker is refused`() {
        for (line: String in listOf("-\t```kotlin", ">\t```kotlin", "1.\t```kotlin", "\t```kotlin", "> -\t```kotlin")) {
            val message: String = refusal(listOf("# Title", "", line, broken, "```"))
            assertTrue(message.startsWith("doc.md:3: unrecognised fence-like line"), "$line: $message")
        }
    }

    @Test
    fun `a tab that decides an item's indentation is refused even on a line without fence characters`() {
        // CommonMark puts the content of "-<tab>text" at column 4, so the fence below is in the item; the reader cannot say that.
        val message: String = refusal(listOf("-\titem text", "", "    ```kotlin", "    $broken", "    ```"))
        assertTrue(message.startsWith("doc.md:1: unrecognised line"), message)
    }

    @Test
    fun `a tab inside a fence body or in the middle of a line is not a problem`() {
        val lines: List<String> = listOf("```kotlin", "\tval x: Int = 1", "```", "text\twith a tab", "- item\twith a tab")
        assertEquals(1, DocFences.scan("doc.md", lines).size)
    }

    @Test
    fun `fence characters that are not a fence opening are refused wherever they are`() {
        val layouts: Map<String, List<String>> = mapOf(
            "heading" to listOf("# Title ```kotlin"),
            "table row" to listOf("| a | ```kotlin |", "| - | - |"),
            "inline code" to listOf("Use ```kotlin``` here."),
            "tildes in a sentence" to listOf("Write ~~~ to open a fence."),
            "info string with a backtick" to listOf("```kotlin`", broken, "```"),
            "block quote sentence" to listOf("> Write ``` to open a fence."),
            "paragraph continuation indented by four" to listOf("Some text", "    ```text", "more"),
        )
        for ((name: String, lines: List<String>) in layouts) {
            val message: String = assertFailsWith<IllegalStateException>(name) { DocFences.scan("doc.md", lines) }.message.orEmpty()
            assertTrue(message.contains(Regex("^doc\\.md:\\d+: unrecognised fence-like line")), "$name: $message")
        }
    }

    // Layouts that must not crash or be refused.

    @Test
    fun `a thematic break a setext underline and an empty item are read without a refusal`() {
        val listing: List<String> = listOf("<!-- listing -->", "```kotlin", "public interface Listed", "```")
        val documents: Map<String, List<String>> = mapOf(
            "star break" to listOf("Text.", "", "* * *", "") + listing,
            "dash break" to listOf("Text.", "", "- - -", "") + listing,
            "underscore break" to listOf("Text.", "", "_ _ _", "") + listing,
            "setext dash" to listOf("Section", "-", "") + listing,
            "setext equals" to listOf("Section", "===", "") + listing,
            "empty item" to listOf("- first", "-", "- third", "") + listing,
            "empty ordered item" to listOf("1.", "2.", "") + listing,
            "bare marker last line" to listOf("text", "-"),
        )
        for ((name: String, lines: List<String>) in documents) {
            val fences: List<DocFence> = DocFences.scan("doc.md", lines)
            assertEquals(lines.indexOf("```kotlin").takeIf { it >= 0 }?.let { listOf(it + 1) } ?: emptyList(), fences.map { it.line }, name)
            assertEquals(emptyList(), problems(lines), name)
        }
    }

    @Test
    fun `a fence after a thematic break is at the top level`() {
        val lines: List<String> = listOf("* * *", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:2"), problems(lines))
        assertEquals(false, DocFences.scan("doc.md", lines).single().inContainer)
    }

    // Seeded fuzz: the reader neither crashes nor silently skips a fence-like line.

    private val prefixes: List<String> = listOf("", "", "", "> ", "- ", "* ", "1. ", "  ", "   ", "    ", "> > ", "-", ">", "      ", "> - ", "\t")
    private val contents: List<String> = listOf(
        "```kotlin", "```", "````", "~~~", "~~~kotlin", "text", "text", "", "", "---", "* * *", "# h", "<!--", "-->", "<div>", "</div>", "`code`",
        "```x```", "text ```", "=== ", "- ", "> ", "<!-- listing -->", "<pre>", "</pre>",
    )

    private fun randomDocument(random: Random): List<String> =
        List(random.nextInt(1, 11)) { prefixes[random.nextInt(prefixes.size)] + contents[random.nextInt(contents.size)] }

    @Test
    fun `no input crashes the reader with anything but its own error and no fence-like line is skipped silently`() {
        val seed = 20261116
        val random = Random(seed)
        var returned = 0
        var refused = 0
        repeat(6000) {
            val lines: List<String> = randomDocument(random)
            val described = "seed=$seed document=$lines"
            val fences: List<DocFence> = try {
                DocFences.scan("doc.md", lines).also { returned++ }
            } catch (error: IllegalStateException) {
                refused++
                assertTrue(error.message.orEmpty().matches(Regex("^doc\\.md:\\d+: .*", RegexOption.DOT_MATCHES_ALL)), "$described: ${error.message}")
                return@repeat
            } catch (error: Throwable) {
                throw AssertionError("$described: the reader threw $error", error)
            }
            // Every fence-like line is inside a fence (opening line, body, closing line) unless four columns of indentation come
            // before the fence characters (an indented code block).
            for ((index: Int, line: String) in lines.withIndex()) {
                val run: MatchResult = Regex("`{3,}|~{3,}").find(line) ?: continue
                if (line.substring(0, run.range.first).contains("    ")) continue
                val covered: Boolean = fences.any { index + 1 in it.line..(it.line + it.body.size + 1) }
                assertTrue(covered, "$described: line ${index + 1} '$line' holds a fence-like run, was read as nothing and not refused")
            }
        }
        assertTrue(returned > 500 && refused > 500, "the fuzz must exercise both outcomes: returned=$returned refused=$refused (seed $seed)")
    }
}
