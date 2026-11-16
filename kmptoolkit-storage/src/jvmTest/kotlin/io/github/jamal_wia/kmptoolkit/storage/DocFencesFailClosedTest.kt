package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The refusal rule of [DocFences] (docs round 7): the reader accepts exactly two line layouts, an opening line (ASCII spaces, three
 * backticks, ASCII letters or digits) and the closing line (the same spaces and three backticks), and refuses every other line that
 * holds three backticks or three tildes, wherever it is. One case per layout the reviewers named in earlier rounds (container,
 * lazy continuation, HTML block, tab, Unicode white space, ordered marker, long line), the layouts that need no refusal, and a
 * seeded fuzz whose oracle is the plain invariant: every line with a fence-like run is an exact opening or closing line, or the
 * scan throws.
 */
class DocFencesFailClosedTest {

    private val broken: String = """val broken: Int = "text""""

    private val listing: List<String> = listOf("<!-- listing -->", "```kotlin", "public interface Listed", "```")

    private fun refusal(lines: List<String>): String =
        assertFailsWith<IllegalStateException> { DocFences.scan("doc.md", lines) }.message.orEmpty()

    private fun assertRefusedAt(line: Int, name: String, lines: List<String>) {
        val message: String = refusal(lines)
        assertTrue(message.startsWith("doc.md:$line: "), "$name: expected a refusal at line $line, got: $message")
    }

    // Layouts that carry a fence-like run and are refused, whatever surrounds the run.

    @Test
    fun `every layout with a fence-like run that is not the exact opening or closing line is refused`() {
        val layouts: Map<String, Pair<Int, List<String>>> = mapOf(
            "block quote" to (5 to listOf("# Title", "", "> **Note.**", ">", "> ```kotlin", "> $broken", "> ```", "", "end")),
            "block quote closing line" to (3 to listOf("```bash", "x", "> ```")),
            "bullet item on the marker line" to (3 to listOf("# Title", "", "- ```kotlin", "  $broken", "  ```") + listing),
            "ordered item on the marker line" to (3 to listOf("# Title", "", "1. ```kotlin", "   $broken", "   ```") + listing),
            "ordered 2. after paragraph text" to (2 to listOf("Use the snippet below and", "2. ```text", "   ```kotlin", "   $broken", "   ```")),
            "indented code block that shows a fence, then a real one" to
                (8 to listOf("# Title", "", "A Markdown example:", "", "    ```text", "    shown", "", "```kotlin", broken, "```")),
            "closing line with other indentation" to (3 to listOf("  ```kotlin", broken, "```")),
            "tab indent" to (1 to listOf("\t```kotlin", broken, "\t```")),
            "tab after a quote marker" to (1 to listOf(">\t```kotlin", ">\t$broken", ">\t```")),
            "tab after a list marker" to (1 to listOf("-\t```kotlin", "\t$broken", "\t```")),
            "no-break space before the opening line" to (2 to listOf("Intro text.", " ```text", "```kotlin", broken, "```")),
            "form feed before the opening line" to (2 to listOf("Intro text.", "\u000C```text", "```kotlin", broken, "```")),
            "em space before the opening line" to (2 to listOf("Intro text.", " ```text", "```kotlin", broken, "```")),
            "ideographic space before the opening line" to (2 to listOf("Intro text.", "　```text", "```kotlin", broken, "```")),
            "byte order mark before the opening line" to (1 to listOf("﻿```kotlin", broken, "```")),
            "text before the run" to (1 to listOf("a ```kotlin", broken, "```")),
            "text after the closing run" to (3 to listOf("<!-- listing -->", "```kotlin", "```x")),
            "inline code" to (1 to listOf("Use ```kotlin``` here.")),
            "heading" to (1 to listOf("# Title ```kotlin")),
            "table row" to (1 to listOf("| a | ```kotlin |", "| - | - |")),
            "tildes in a sentence" to (1 to listOf("Write ~~~ to open a fence.")),
            "tilde fence" to (1 to listOf("~~~kotlin", broken, "~~~")),
            "four backticks" to (1 to listOf("````kotlin", broken, "````")),
            "info string with a backtick" to (1 to listOf("```kotlin`", broken, "```")),
            "info string with a space" to (1 to listOf("``` kotlin", broken, "```")),
            "info string with attributes" to (1 to listOf("```kotlin title=\"x\"", broken, "```")),
            "escaped fence" to (1 to listOf("\\```kotlin")),
            "html comment around a fence" to (6 to listOf("# Title", "", "<!--", "  ```text", "-->", "```kotlin", broken, "```")),
            "fence directly after an html tag line" to (4 to listOf("<details>", " ```kotlin", broken, "```", "</details>")),
        )
        for ((name: String, case: Pair<Int, List<String>>) in layouts) assertRefusedAt(case.first, name, case.second)
    }

    @Test
    fun `a fence-like run inside a fence body is refused whatever it looks like`() {
        val bodies: Map<String, String> = mapOf(
            "an opening line" to "```kotlin",
            "a longer closing line" to "````",
            "a closing line with a trailing space" to "``` ",
            "a closing line with a trailing no-break space" to "``` ",
            "an indented closing line" to "  ```",
            "a tab-indented closing line" to "\t```",
            "a closing line behind a quote marker" to ">\t```",
            "a tilde line" to "~~~",
        )
        for ((name: String, body: String) in bodies) {
            // The fence is followed by a real Kotlin fence that the body line must not be able to swallow silently.
            val lines: List<String> = listOf("```text", "foo", body, "```", "```kotlin", broken, "```")
            assertRefusedAt(3, name, lines)
        }
    }

    @Test
    fun `an indented fence is read, never a sample, and reported`() {
        for (indent: String in listOf(" ", "  ", "   ", "    ", "      ")) {
            val lines: List<String> = listOf("- item", "", "${indent}```kotlin", "$indent$broken", "$indent```", "", "end")
            val fences: List<DocFence> = DocFences.scan("doc.md", lines)
            assertEquals(listOf("doc.md:3"), fences.map { it.location }, "indent '$indent'")
            assertEquals(emptyList(), DocFences.kotlinFences("doc.md", lines), "indent '$indent'")
            assertEquals(listOf("doc.md:3"), DocFences.oddKotlinFences("doc.md", lines).map { it.location }, "indent '$indent'")
        }
    }

    @Test
    fun `the layouts that hid a fence in earlier rounds are read as indented fences and reported or refused`() {
        val layouts: Map<String, List<String>> = mapOf(
            "lazy continuation then an indented fence" to
                listOf("- Add the dependency, then", "sync the project:", "", "    ```kotlin", "    $broken", "    ```"),
            "ordered lazy continuation then an indented fence" to
                listOf("1. Add the dependency and", "  sync the project:", "", "    ```kotlin", "    $broken", "    ```"),
            "wrapped item and an autolink line" to
                listOf("- Read the notes at", "<https://example.com/notes> before you", "  upgrade.", "", "    ```kotlin", "    $broken", "    ```"),
            "item fence indented under its text" to listOf("- item", "", "  ```kotlin", "  $broken", "  ```"),
        )
        for ((name: String, lines: List<String>) in layouts) {
            val fence: DocFence = DocFences.scan("doc.md", lines).single()
            assertTrue(fence.isKotlinLike && !fence.isExactKotlin, name)
            assertTrue(fence.body.any { it.contains(broken) }, name)
            assertEquals(listOf(fence.location), DocFences.oddKotlinFences("doc.md", lines).map { it.location }, name)
        }
    }

    @Test
    fun `a fence inside an html block that CommonMark does not draw is read and can only fail the sample check`() {
        // An exact line inside a comment is read as a fence (a renderer does not draw it). That is loud, never a skipped snippet:
        // it has no marker, so the drift test fails on it. A stray opening inside the comment would swallow the next fence, so the run
        // that is not a closing line is refused.
        val lines: List<String> = listOf("<!--", "```kotlin", broken, "```", "-->")
        assertEquals(listOf("doc.md:2"), DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location })
        assertRefusedAt(5, "swallow", listOf("<div>", "```text", "x", "-->", "```kotlin", broken, "```"))
    }

    // Layouts without a fence-like run are not looked at, so they can neither crash the reader nor be refused.

    @Test
    fun `a thematic break a setext underline an empty item and a bare marker are plain lines`() {
        val documents: Map<String, List<String>> = mapOf(
            "star break" to listOf("Text.", "", "* * *", "") + listing,
            "dash break" to listOf("Text.", "", "- - -", "") + listing,
            "underscore break" to listOf("Text.", "", "_ _ _", "") + listing,
            "setext dash" to listOf("Section", "-", "") + listing,
            "setext equals" to listOf("Section", "===", "") + listing,
            "empty item" to listOf("- first", "-", "- third", "") + listing,
            "empty ordered item" to listOf("1.", "2.", "") + listing,
            "html comment without fence characters" to listOf("<!--", "a note", "-->") + listing,
            "tab in plain text" to listOf("text\twith a tab", "-\titem text", "\tindented text", "> -\tx") + listing,
            "no-break space in plain text" to listOf(" ", " text") + listing,
            "block quote without fence characters" to listOf("> quote", ">", "> more") + listing,
        )
        for ((name: String, lines: List<String>) in documents) {
            val fences: List<DocFence> = DocFences.scan("doc.md", lines)
            assertEquals(listOf("doc.md:${lines.size - 2}"), fences.map { it.location }, name)
            assertEquals(listOf("<!-- listing -->"), fences.map { it.marker }, name)
        }
    }

    @Test
    fun `an empty file and a file without fences give no fence`() {
        assertEquals(emptyList(), DocFences.scan("doc.md", emptyList()))
        assertEquals(emptyList(), DocFences.scan("doc.md", listOf("", "text", "")))
    }

    @Test
    fun `a tab or an odd character inside a fence body is body text`() {
        val lines: List<String> = listOf("```kotlin", "\tval x: Int = 1", " ", "    nested", "```")
        assertEquals(listOf("\tval x: Int = 1", " ", "    nested"), DocFences.scan("doc.md", lines).single().body)
    }

    // Very long lines: linear scans only.

    @Test
    fun `a very long line is read in linear time and a long refused line gives a short message`() {
        val plain: List<String> = listOf(
            " ".repeat(200_000) + "x", "> ".repeat(100_000) + "x", "- ".repeat(100_000), "1".repeat(200_000) + ". x", "`".repeat(2) + "x".repeat(300_000),
        )
        for (line: String in plain) assertEquals(1, DocFences.scan("doc.md", listOf(line, "") + listing).size)
        for (line: String in listOf("\t" + " ".repeat(200_000) + "```kotlin", "`".repeat(1_000_000), "> ".repeat(100_000) + "```")) {
            val message: String = refusal(listOf(line, "", "```kotlin", broken, "```"))
            assertTrue(message.startsWith("doc.md:1: unrecognised fence-like line"), message.take(120))
            assertTrue(message.length < 400, "the message holds the whole line: ${message.length} characters")
        }
    }

    // Seeded fuzz. The oracle is independent of the reader's loops: it uses substring tests, not the reader's run scan.

    private val prefixes: List<String> = listOf(
        "", "", "", "> ", "- ", "* ", "1. ", "2. ", "  ", "   ", "    ", "> > ", "-", ">", "      ", "> - ", "\t", ">\t", "  \t", " ", "\u000C", " ",
    )
    private val contents: List<String> = listOf(
        "```kotlin", "```", "````", "~~~", "~~~kotlin", "```text", "```Kotlin", "``` ", "```x```", "text", "text", "", "", "---", "* * *", "# h",
        "<!--", "-->", "<div>", "</div>", "`code`", "text ```", "=== ", "- ", "> ", "<!-- listing -->", "<pre>", "<https://x.y> z", "<kbd>a</kbd>", "``",
    )

    private fun randomDocument(random: Random): List<String> =
        List(random.nextInt(1, 11)) { prefixes[random.nextInt(prefixes.size)] + contents[random.nextInt(contents.size)] }

    private fun carriesRun(line: String): Boolean = line.contains("```") || line.contains("~~~")

    private fun isOpeningLine(line: String): Boolean {
        val rest: String = line.trimStart(' ')
        return rest.startsWith("```") && rest.drop(3).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }

    /** True when the plain invariant says the document must be refused: a run that is neither an opening nor a closing line, or an open fence at the end. */
    private fun mustBeRefused(lines: List<String>): Boolean {
        var closing: String? = null
        for (raw: String in lines) {
            val line: String = raw.removeSuffix("\r")
            if (closing != null) {
                if (line == closing) closing = null else if (carriesRun(line)) return true
            } else if (carriesRun(line)) {
                if (!isOpeningLine(line)) return true
                closing = line.takeWhile { it == ' ' } + "```"
            }
        }
        return closing != null
    }

    @Test
    fun `the scan throws exactly when a fence-like line is not an exact opening or closing line`() {
        val seed = 20261117
        val random = Random(seed)
        var returned = 0
        var refused = 0
        repeat(8000) {
            val lines: List<String> = randomDocument(random)
            val described = "seed=$seed document=${lines.map { it.replace("\t", "<TAB>").replace(" ", "<NBSP>").replace("\u000C", "<FF>").replace(" ", "<EM>") }}"
            val expectRefusal: Boolean = mustBeRefused(lines)
            val fences: List<DocFence>? = try {
                DocFences.scan("doc.md", lines).also { returned++ }
            } catch (error: IllegalStateException) {
                refused++
                assertTrue(error.message.orEmpty().matches(Regex("^doc\\.md:\\d+: .{0,500}", RegexOption.DOT_MATCHES_ALL)), "$described: ${error.message}")
                null
            } catch (error: Throwable) {
                throw AssertionError("$described: the reader threw $error", error)
            }
            assertEquals(expectRefusal, fences == null, "$described: refusal expected=$expectRefusal")
            // The reports never throw and agree with the strict reader about every fence it read.
            val odd: List<DocFence> = DocFences.oddKotlinFences("doc.md", lines)
            val exact: List<DocFence> = DocFences.kotlinFences("doc.md", lines)
            if (fences == null) return@repeat
            assertEquals(fences.filter { it.isExactKotlin }.map { it.line }, exact.map { it.line }, described)
            assertEquals(fences.filter { it.isKotlinLike && !it.isExactKotlin }.map { it.line }, odd.map { it.line }, described)
            // Every line with a run is the opening or the closing line of a read fence, and nothing else is.
            val runLines: Set<Int> = lines.withIndex().filter { carriesRun(it.value) }.map { it.index + 1 }.toSet()
            val fenceLines: Set<Int> = fences.flatMap { listOf(it.line, it.line + it.body.size + 1) }.toSet()
            assertEquals(runLines, fenceLines, described)
        }
        assertTrue(returned > 500 && refused > 500, "the fuzz must exercise both outcomes: returned=$returned refused=$refused (seed $seed)")
    }
}
