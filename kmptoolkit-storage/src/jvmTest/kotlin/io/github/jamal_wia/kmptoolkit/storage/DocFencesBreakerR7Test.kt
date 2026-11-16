package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Docs breaker round 7 of group sto-docs (STO-12): the last re-check of the [DocFences] reader (decision D-38) after the round-8
 * fixes (the `ktm` spelling, the escaped test sources).
 *
 * The seeded differential test runs fresh seeds against an independent model of the reader's invariant (regular expressions,
 * written apart from the reader's loops) over an alphabet that adds every Kotlin spelling and near-spelling (`kts`, `ktm`, `KTM`,
 * `kotlinc`, `kotlin-script`, `kotlin+x`, `ktx`) and indented closing lines. On top of the model it checks the property the drift
 * test exists for: when [DocFences.scan] returns, every fence whose info string a renderer draws as Kotlin (an independent oracle:
 * the name `kotlin` in it, or exactly one of the Linguist extensions) is either a sample ([DocFence.isExactKotlin]) or reported
 * by [DocFences.oddKotlinFences]. The targeted test pins that an info string with characters other than letters and digits
 * after a Kotlin name (`kotlin-script`, `kotlin+x`, `kt.x`) is refused, never read and skipped.
 */
class DocFencesBreakerR7Test {

    private val run: Regex = Regex("`{3,}|~{3,}")
    private val openingLine: Regex = Regex("^( *)```([A-Za-z0-9]*)$")

    private class Expected(val line: Int, val opening: String, val info: String, val body: List<String>)

    /** The independent model: null when the invariant says the document must be refused, else the fences it must read. */
    private fun model(lines: List<String>): List<Expected>? {
        val fences: MutableList<Expected> = mutableListOf()
        var open: Expected? = null
        var closing: String? = null
        for ((index: Int, raw: String) in lines.withIndex()) {
            val line: String = raw.removeSuffix("\r")
            val held: Boolean = run.containsMatchIn(line)
            val current: Expected? = open
            if (current != null) {
                when {
                    line == closing -> {
                        fences.add(current)
                        open = null
                        closing = null
                    }
                    held -> return null
                    else -> (current.body as MutableList<String>).add(line)
                }
            } else if (held) {
                val match: MatchResult = openingLine.matchEntire(line) ?: return null
                open = Expected(index + 1, line, match.groupValues[2], mutableListOf())
                closing = match.groupValues[1] + "```"
            }
        }
        return if (open == null) fences else null
    }

    /** The renderer-side oracle, written apart from [DocFence.isKotlinLike]: drawn as Kotlin by name or by a Linguist extension. */
    private fun drawnAsKotlin(info: String): Boolean {
        val lower: String = info.lowercase()
        return "kotlin" in lower || lower == "kt" || lower == "kts" || lower == "ktm"
    }

    private val prefixes: List<String> = listOf("", "", "", "", " ", "  ", "   ", "\t", "> ", "- ", "\u00A0", "\r", "x")
    private val contents: List<String> = listOf(
        "```kotlin", "```kotlin", "```", "```", "```", "```", "```kts", "```ktm", "```KTM", "```Kt", "```kotlinc", "```KOTLIN",
        "```kotlin-script", "```kotlin+x", "```kt.x", "```ktx", "```ktm1", "```text", "```kotlin\r", "``` kotlin", "````kotlin",
        "~~~ktm", "``\u200B`ktm", "val broken: Int = \"text\"", "text", "", "", "`kt`", "a ```ktm b",
    )

    private val openers: List<String> = listOf("```kotlin", "```kts", "```ktm", "```KTM", "```Kt", "```kotlinc", "```KOTLIN", "```text", "```ktx", "```")
    private val accepted: List<String> = listOf("val broken: Int = \"text\"", "text", "", "`kt`", "  nested", "\tval x: Int = 1", "\r")

    private fun describe(lines: List<String>): String =
        lines.joinToString(" | ") { line: String -> line.map { if (it.code < 0x20 || it.code > 0x7E) "<U+%04X>".format(it.code) else it.toString() }.joinToString("") }

    @Test
    fun `fresh seeds - the reader matches the model and never lets a fence drawn as Kotlin pass unreported`() {
        for (seed: Int in listOf(20261152, 52, 7_000_007)) {
            val random = Random(seed)
            var refused = 0
            var returned = 0
            var kotlinRead = 0
            repeat(20000) {
                // Units: three in four are a well-formed fence (an accepted opening, body lines, its closing line), so that enough
                // documents are read and not refused; the fourth is one noisy line from the full alphabet.
                val lines: MutableList<String> = mutableListOf()
                repeat(random.nextInt(0, 6)) {
                    if (random.nextInt(4) < 3) {
                        val indent: String = if (random.nextInt(6) == 0) "  " else ""
                        lines.add(indent + openers[random.nextInt(openers.size)])
                        repeat(random.nextInt(0, 3)) { lines.add(accepted[random.nextInt(accepted.size)]) }
                        lines.add(indent + "```")
                    } else {
                        lines.add(prefixes[random.nextInt(prefixes.size)] + contents[random.nextInt(contents.size)])
                    }
                }
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
                val odd: List<DocFence> = try {
                    DocFences.oddKotlinFences("doc.md", lines)
                } catch (error: Throwable) {
                    throw AssertionError("$described: oddKotlinFences threw $error", error)
                }
                if (expected == null) {
                    assertEquals(null, actual?.map { it.location }, "$described: the invariant refuses this document, scan returned fences")
                    refused++
                } else {
                    if (actual == null) fail("$described: the invariant accepts this document, scan threw")
                    assertEquals(
                        expected.map { listOf(it.line, it.opening, it.info, it.body) },
                        actual.map { listOf(it.line, it.opening, it.info, it.body) },
                        "$described: fences read",
                    )
                    val oddLines: Set<Int> = odd.map { it.line }.toSet()
                    for (fence: Expected in expected.filter { drawnAsKotlin(it.info) }) {
                        kotlinRead++
                        val sample: Boolean = fence.opening == "```kotlin"
                        assertTrue(sample || fence.line in oddLines, "$described: the fence at line ${fence.line} is drawn as Kotlin and hides")
                    }
                    returned++
                }
            }
            assertTrue(refused > 1000 && returned > 1000 && kotlinRead > 1000, "seed=$seed refused=$refused returned=$returned kotlinRead=$kotlinRead")
        }
    }

    @Test
    fun `a Kotlin name followed by a character other than a letter or digit is refused, never read and skipped`() {
        for (info: String in listOf("kotlin-script", "kotlin+x", "kt.x", "ktm-1", "Kotlin_x", "kts:", "ktm\u00A0")) {
            val lines: List<String> = listOf("Some text.", "", "```$info", "val broken: Int = \"text\"", "```")
            val message: String? = try {
                DocFences.scan("doc.md", lines)
                null
            } catch (error: IllegalStateException) {
                error.message
            }
            assertTrue(message.orEmpty().startsWith("doc.md:3: unrecognised fence-like line"), "info '$info': scan answered $message")
        }
    }
}
