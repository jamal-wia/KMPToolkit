package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Docs breaker round 5 of group sto-docs (STO-12): inputs that hide a Kotlin fence from the fail-closed [DocFences] reader
 * (round 6) without any error, or make it throw something other than its own `file:line` error.
 *
 * Each document below renders, in CommonMark 0.31 (and so on GitHub), a fenced Kotlin block holding `val broken: Int = "text"`,
 * which does not compile. The check passes only if the reader throws its own `doc.md:<line>:` error or returns a Kotlin fence
 * whose body holds that line (an exact top-level fence is then compared with a sample, an odd one is reported).
 *
 * - Tab columns inside a fence body: the tab refusal runs only outside fence bodies, so a closing line whose tab CommonMark
 *   expands to fewer columns than the reader counts (after `>`, or after the spaces an item consumes) closes the fence in
 *   CommonMark but not in the reader, which then swallows the next (Kotlin) fence as body text.
 * - Unicode white space: Kotlin's `trim()` and `isBlank()` treat U+00A0, U+000C, U+2003 and the like as white space;
 *   CommonMark treats only spaces and tabs as indentation, as closing-fence padding and as a blank line. The reader then opens a
 *   fence CommonMark does not open (and swallows the next fence), closes a fence CommonMark keeps open, or ends an HTML block
 *   CommonMark keeps open.
 * - A line that CommonMark reads as paragraph continuation (an autolink or inline HTML at the start of a line, an ordered marker
 *   other than `1.` after paragraph text) is read by the reader as a block start: it ends a list item CommonMark keeps open, or
 *   opens a list item and a fence CommonMark never opens.
 * - A very long line makes the regular expressions recurse until StackOverflowError.
 */
class DocFencesBreakerR5Test {

    private val broken: String = """val broken: Int = "text""""

    private fun hiddenBy(name: String, lines: List<String>, hidden: String = broken) {
        val fences: List<DocFence> = try {
            DocFences.scan("doc.md", lines)
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().startsWith("doc.md:"), "$name: ${error.message}")
            return
        } catch (error: Throwable) {
            throw AssertionError("$name: the reader threw $error", error)
        }
        val checked: Boolean = fences.any { fence: DocFence -> fence.isKotlinLike && fence.body.any { it.contains(hidden) } }
        assertTrue(
            checked,
            "$name: CommonMark renders a Kotlin fence holding '$hidden', the reader read ${fences.map { "${it.location} '${it.opening}' ${it.body}" }} and raised nothing",
        )
    }

    @Test
    fun `a closing fence after a quote marker and a tab ends the fence as in CommonMark`() {
        // CommonMark 2.2: the tab after `>` expands to column 4; one column is the marker's optional space, two remain, so
        // `>\t```` is a closing fence (indent 2). The reader keeps "\t```" (four columns) and does not close.
        hiddenBy("quote + tab", listOf("> ```text", "> foo", ">\t```", "> ```kotlin", "> $broken", "> ```"))
    }

    @Test
    fun `a closing fence whose tab follows the spaces an item consumes ends the fence as in CommonMark`() {
        // Item content column 2; "  \t```": the tab at column 2 is two columns wide, so the closing line has indent 2. The reader
        // drops the two spaces, counts the tab as four columns and does not close.
        hiddenBy("item + spaces + tab", listOf("- ```text", "  foo", "  \t```", "  ```kotlin", "  $broken", "  ```"))
    }

    @Test
    fun `a closing-shaped body line whose tab the item only partly consumes does not end the fence`() {
        // Item content column 2; "\t  ```": the item takes two of the tab's four columns, so the line keeps four columns of
        // indentation and is body text in CommonMark. The reader drops the whole tab, sees two columns and closes there; it then
        // opens a fence at the real closing line and swallows the Kotlin fence that follows.
        hiddenBy("item + tab overshoot", listOf("- ```text", "\t  ```", "  ```", "  ```kotlin", "  $broken", "  ```"))
    }

    @Test
    fun `a line that starts with a no-break space or a form feed is not a fence opening`() {
        // CommonMark: only spaces count as indentation, so " ```text" is paragraph text and the next line opens a Kotlin
        // fence. The reader trims the no-break space, opens a text fence and swallows the Kotlin fence.
        for ((name: String, space: String) in listOf("U+00A0" to " ", "U+000C" to "\u000C", "U+2003" to " ", "U+3000" to "　")) {
            hiddenBy("opening after $name", listOf("Intro text.", "$space```text", "```kotlin", broken, "```"))
        }
    }

    @Test
    fun `a closing line padded with a no-break space does not end the fence`() {
        // CommonMark 4.5: a closing fence may be followed only by spaces or tabs, so "``` " is body text and the Kotlin
        // fence runs on to line 6. The reader closes at line 4 and compares only "val ok" with the sample; the uncompiled
        // line 5 is rendered inside the Kotlin block (line 7 then opens an empty fence that runs to the end of the file).
        hiddenBy(
            "closing + U+00A0",
            listOf("<!-- sample: s -->", "```kotlin", "val ok: Int = 1", "``` ", broken, "```", "```"),
        )
    }

    @Test
    fun `a line holding only a no-break space does not end an HTML block`() {
        // CommonMark: a blank line holds only spaces or tabs, so the type 6 block `<div>` continues over " " and over
        // "```text" and ends at the empty line; the Kotlin fence after it is a fence. The reader ends the block at " ",
        // opens a text fence at "```text" and swallows the Kotlin fence.
        hiddenBy("HTML + U+00A0 line", listOf("<div>", " ", "```text", "", "```kotlin", broken, "```"))
    }

    @Test
    fun `an item whose text wraps onto a line starting with an autolink stays open`() {
        // CommonMark: "<https://...> before you" is not an HTML block start (no valid tag) and a type 7 block cannot interrupt a
        // paragraph anyway, so it is a lazy continuation line, the item stays open, and the fence indented by four columns after
        // the blank line is a Kotlin fence in the item. The reader starts an HTML block there, ends the item, and skips the fence as
        // an indented code block.
        for (wrapped: String in listOf("<https://example.com/notes> before you", "<kbd>Ctrl</kbd> and the project syncs.")) {
            hiddenBy(
                "lazy '$wrapped'",
                listOf("- Read the platform notes at", wrapped, "  upgrade.", "", "    ```kotlin", "    $broken", "    ```"),
            )
        }
    }

    @Test
    fun `an ordered marker other than 1 after paragraph text opens no item and no fence`() {
        // CommonMark 5.2: an ordered list that interrupts a paragraph must start with 1, so "2. ```text" continues the paragraph,
        // and the next line, indented by three, opens a top-level Kotlin fence. The reader opens an item (content column 3) and a
        // text fence in it, and swallows the Kotlin fence.
        hiddenBy("ordered 2.", listOf("Use the snippet below and", "2. ```text", "   ```kotlin", "   $broken", "   ```"))
    }

    @Test
    fun `a very long line makes the reader throw only its own error or nothing`() {
        val layouts: Map<String, String> = mapOf(
            "spaces" to " ".repeat(200_000) + "x",
            "quote markers" to "> ".repeat(100_000) + "x",
            "dash break" to "- ".repeat(100_000),
            "digits" to "1".repeat(200_000) + ". x",
        )
        val crashes: List<String> = layouts.mapNotNull { (name: String, line: String) ->
            try {
                DocFences.scan("doc.md", listOf(line, "", "```kotlin", broken, "```"))
                null
            } catch (error: IllegalStateException) {
                if (error.message.orEmpty().startsWith("doc.md:")) null else "$name: ${error.message}"
            } catch (error: Throwable) {
                "$name: ${error.javaClass.name}"
            }
        }
        assertTrue(crashes.isEmpty(), "long lines made the reader throw something other than its own error: $crashes")
    }

    // Seeded fuzz over layouts the round-6 fuzz does not draw (tabs inside containers, Unicode spaces, inline HTML, ordered
    // markers other than 1): no exception but the reader's own, and no fence-like line read as nothing and not refused.

    private val prefixes: List<String> = listOf(
        "", "", "> ", "- ", "2. ", "1. ", "  ", "   ", "    ", ">\t", "  \t", " ", "\u000C", "> - ", "-", "\t",
    )
    private val contents: List<String> = listOf(
        "```kotlin", "```text", "```", "``` ", "~~~", "text", "text", "", "", " ", "<div>", "<https://x.y> z", "<kbd>a</kbd>",
        "---", "* * *", "-", "<!--", "-->", "2. ```text", "`x`",
    )

    @Test
    fun `seeded fuzz over tabs unicode spaces and inline html neither crashes nor skips a fence-like line silently`() {
        val seed = 20261150
        val random = Random(seed)
        repeat(8000) {
            val lines: List<String> = List(random.nextInt(1, 9)) { prefixes[random.nextInt(prefixes.size)] + contents[random.nextInt(contents.size)] }
            val described = "seed=$seed document=${lines.map { it.replace("\t", "<TAB>").replace(" ", "<NBSP>").replace("\u000C", "<FF>") }}"
            val fences: List<DocFence> = try {
                DocFences.scan("doc.md", lines)
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().matches(Regex("^doc\\.md:\\d+: .*", RegexOption.DOT_MATCHES_ALL)), "$described: ${error.message}")
                return@repeat
            } catch (error: Throwable) {
                throw AssertionError("$described: the reader threw $error", error)
            }
            for ((index: Int, line: String) in lines.withIndex()) {
                val run: MatchResult = Regex("`{3,}|~{3,}").find(line) ?: continue
                if (line.substring(0, run.range.first).contains("    ")) continue
                val covered: Boolean = fences.any { index + 1 in it.line..(it.line + it.body.size + 1) }
                assertTrue(covered, "$described: line ${index + 1} holds a fence-like run, was read as nothing and not refused")
            }
        }
    }
}
