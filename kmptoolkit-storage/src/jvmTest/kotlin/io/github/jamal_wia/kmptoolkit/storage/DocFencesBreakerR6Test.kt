package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Docs breaker round 6 of group sto-docs (STO-12): attacks on the by-construction [DocFences] reader of round 7 (decision D-38).
 *
 * The reader's stated invariant: every line that holds a run of three or more backticks or tildes is an accepted opening line
 * (ASCII spaces, three backticks, ASCII letters or digits) outside a fence, the exact closing line (the same spaces and three
 * backticks) inside one, or a refusal; a fence left open at the end is a refusal. The seeded differential test below checks the
 * reader against an independent model of exactly that invariant (regular expressions over short lines, written apart from the
 * reader's loops): same outcome (throw or not) and, when it returns, the same fences line for line. A targeted test checks the one
 * place where the check can still be blind: an accepted info string that a renderer draws as Kotlin but [DocFence.isKotlinLike]
 * does not recognise.
 */
class DocFencesBreakerR6Test {

    private val run: Regex = Regex("`{3,}|~{3,}")
    private val openingLine: Regex = Regex("^( *)```([A-Za-z0-9]*)$")

    private class Expected(val line: Int, val opening: String, val body: List<String>)

    /** The independent model: null when the invariant says the document must be refused, else the fences it must read. */
    private fun model(lines: List<String>): List<Expected>? {
        val fences: MutableList<Expected> = mutableListOf()
        var openLine = 0
        var opening = ""
        var closing: String? = null
        var body: MutableList<String> = mutableListOf()
        for ((index: Int, raw: String) in lines.withIndex()) {
            val line: String = raw.removeSuffix("\r")
            val held: Boolean = run.containsMatchIn(line)
            if (closing != null) {
                if (line == closing) {
                    fences.add(Expected(openLine, opening, body))
                    closing = null
                } else if (held) {
                    return null
                } else {
                    body.add(line)
                }
            } else if (held) {
                val match: MatchResult = openingLine.matchEntire(line) ?: return null
                openLine = index + 1
                opening = line
                closing = match.groupValues[1] + "```"
                body = mutableListOf()
            }
        }
        return if (closing == null) fences else null
    }

    private val prefixes: List<String> = listOf(
        "", "", "", " ", "  ", "   ", "    ", "\t", "> ", "- ", "1. ", "\u00A0", "\u000C", "\uFEFF", "\u0000", "\r", "x",
    )
    private val contents: List<String> = listOf(
        "```kotlin", "```kotlin", "```", "```", "```", "```bash", "```Kotlin", "```kt", "```kotlin\r", "```\r", "```\r\r",
        "````", "~~~", "~~~kotlin", "``", "`x`", "``` ", "```kotlin ", "```kotlin\u0000", "```k\u00F6tlin", "```kotlin{.x}",
        "val broken: Int = \"text\"", "text", "", "", "\r", "a ``` b", "``\u200B`", "\u000B```",
    )

    private fun describe(lines: List<String>): String =
        lines.joinToString(" | ") { line: String -> line.map { if (it.code < 0x20 || it.code > 0x7E) "<U+%04X>".format(it.code) else it.toString() }.joinToString("") }

    @Test
    fun `seeded differential - the reader matches an independent model of its own invariant`() {
        val seed = 20261151
        val random = Random(seed)
        var refused = 0
        var returned = 0
        repeat(20000) {
            val lines: List<String> = List(random.nextInt(0, 12)) { prefixes[random.nextInt(prefixes.size)] + contents[random.nextInt(contents.size)] }
            val described = "seed=$seed document=[${describe(lines)}]"
            val expected: List<Expected>? = model(lines)
            val actual: List<DocFence>? = try {
                DocFences.scan("doc.md", lines)
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().startsWith("doc.md:"), "$described: ${error.message}")
                null
            } catch (error: Throwable) {
                throw AssertionError("$described: the reader threw $error", error)
            }
            // The two reports must never throw, whatever scan does.
            val odd: List<DocFence> = try {
                DocFences.oddKotlinFences("doc.md", lines)
            } catch (error: Throwable) {
                throw AssertionError("$described: oddKotlinFences threw $error", error)
            }
            val exact: List<DocFence> = try {
                DocFences.kotlinFences("doc.md", lines)
            } catch (error: Throwable) {
                throw AssertionError("$described: kotlinFences threw $error", error)
            }
            if (expected == null) {
                assertEquals(null, actual?.map { it.location }, "$described: the invariant refuses this document, scan returned fences")
                refused++
            } else {
                if (actual == null) fail("$described: the invariant accepts this document, scan threw")
                assertEquals(
                    expected.map { Triple(it.line, it.opening, it.body) },
                    actual.map { Triple(it.line, it.opening, it.body) },
                    "$described: fences read",
                )
                assertEquals(actual.filter { it.isExactKotlin }.map { it.line }, exact.map { it.line }, "$described: kotlinFences against scan")
                assertEquals(
                    actual.filter { it.isKotlinLike && !it.isExactKotlin }.map { it.line },
                    odd.map { it.line },
                    "$described: oddKotlinFences against scan",
                )
                returned++
            }
        }
        assertTrue(refused > 1000 && returned > 1000, "seed=$seed both outcomes must occur: refused=$refused returned=$returned")
    }

    @Test
    fun `line endings - lone CR, CRLF, a missing final newline and a BOM neither crash nor hide a fence`() {
        val texts: List<String> = listOf(
            "<!-- s -->\r```kotlin\rval broken: Int = \"text\"\r```",
            "<!-- s -->\r\n```kotlin\r\nval broken: Int = \"text\"\r\n```\r\n",
            "<!-- s -->\n```kotlin\nval broken: Int = \"text\"\n```",
            "\uFEFF```kotlin\nval broken: Int = \"text\"\n```\n",
            "<!-- s -->\n```kotlin\nval broken: Int = \"text\"\n",
        )
        for ((number: Int, text: String) in texts.withIndex()) {
            // String.lines() splits on CRLF, LF and lone CR, as File.readLines does in DocsSamplesDriftTest.
            val lines: List<String> = text.lines()
            val fences: List<DocFence> = try {
                DocFences.scan("doc.md", lines)
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().startsWith("doc.md:"), "text #$number: ${error.message}")
                continue
            }
            assertTrue(
                fences.any { it.isExactKotlin && it.body.contains("val broken: Int = \"text\"") },
                "text #$number [${describe(lines)}]: read ${fences.map { "${it.location} ${it.body}" }} and raised nothing",
            )
        }
    }

    @Test
    fun `an accepted info string that GitHub draws as Kotlin is reported as an odd Kotlin fence`() {
        // GitHub resolves a fence's info string through Linguist by name, alias or extension; Kotlin has no alias and the
        // extensions .kt, .ktm and .kts (linguist languages.yml), so `kt`, `kts` and `ktm` all render as Kotlin. Each is an
        // accepted opening line (letters only), so it is never refused: if isKotlinLike misses it, the sample check skips it
        // and DocsSamplesDriftTest stays green on a snippet no build compiled.
        val missed: List<String> = listOf("kt", "kts", "ktm", "KTM", "Kt").filter { info: String ->
            val lines: List<String> = listOf("Some text.", "", "```$info", "val broken: Int = \"text\"", "```")
            DocFences.scan("doc.md", lines)
            DocFences.oddKotlinFences("doc.md", lines).isEmpty() && DocFences.kotlinFences("doc.md", lines).isEmpty()
        }
        assertEquals(emptyList(), missed, "info strings drawn as Kotlin that neither the sample check nor the odd-fence report sees")
    }
}
