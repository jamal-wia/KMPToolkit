package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Adversarial inputs for [DocFences], the fence reader behind [DocsSamplesDriftTest]: the spellings of a Kotlin fence. Each case is a
 * document whose fence holds a snippet that does not compile (`val broken: Int = "text"`): the drift check must be red on it,
 * because it cannot be green on a snippet that no build ever compiled. [DocFencesFailClosedTest] pins the refusal rule (every other
 * layout with a fence-like run) and the seeded invariant.
 */
class DocFencesTest {

    private val broken: String = """val broken: Int = "text""""

    private fun document(open: String, close: String = open.trim().takeWhile { it == '`' || it == '~' }): List<String> =
        listOf("# Title", "", open, broken, close, "", "end")

    /** What the drift check reports for [lines]: odd fences, plus fences it would read as samples (which then need a marker). */
    private fun problems(lines: List<String>): List<String> =
        DocFences.oddKotlinFences("doc.md", lines).map { it.location } +
            DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location }

    private fun refusal(lines: List<String>): String =
        assertFailsWith<IllegalStateException> { DocFences.scan("doc.md", lines) }.message.orEmpty()

    @Test
    fun `the layouts of the real docs are read field by field`() {
        val lines: List<String> = listOf(
            "# Title", "", "<!-- sample: a -->", "```kotlin", "val a: Int = 1", "", "val b: Int = 2", "```", "",
            "```bash", "./gradlew build", "```", "", "```", "diagram", "```", "end",
        )
        val fences: List<DocFence> = DocFences.scan("doc.md", lines)
        assertEquals(listOf(4, 10, 14), fences.map { it.line })
        assertEquals(listOf("```kotlin", "```bash", "```"), fences.map { it.opening })
        assertEquals(listOf("kotlin", "bash", ""), fences.map { it.info })
        assertEquals(listOf("<!-- sample: a -->", "", ""), fences.map { it.marker })
        assertEquals(listOf(listOf("val a: Int = 1", "", "val b: Int = 2"), listOf("./gradlew build"), listOf("diagram")), fences.map { it.body })
        assertEquals(listOf("doc.md:4"), DocFences.kotlinFences("doc.md", lines).map { it.location })
        assertEquals(emptyList(), DocFences.oddKotlinFences("doc.md", lines))
    }

    @Test
    fun `four backticks are a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("````kotlin")))
        assertTrue(refusal(document("````kotlin")).startsWith("doc.md:3: unrecognised fence-like line"))
    }

    @Test
    fun `a space before the info string is a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("``` kotlin")))
        assertTrue(refusal(document("``` kotlin")).startsWith("doc.md:3: unrecognised fence-like line"))
    }

    @Test
    fun `tildes are a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("~~~kotlin")))
        assertEquals(listOf("doc.md:3"), problems(document("~~~~ kotlin")))
        assertTrue(refusal(document("~~~kotlin")).startsWith("doc.md:3: unrecognised fence-like line"))
    }

    @Test
    fun `an indented fence is a kotlin fence the check reports`() {
        for (indent: String in listOf(" ", "   ", "    ")) {
            assertEquals(listOf("doc.md:3"), problems(document("${indent}```kotlin", "$indent```")), "indent '$indent'")
            assertEquals(emptyList(), DocFences.kotlinFences("doc.md", document("${indent}```kotlin", "$indent```")), "indent '$indent'")
        }
        // A tab is not an accepted indentation: the line is refused, and reported as a Kotlin-looking line.
        assertEquals(listOf("doc.md:3"), problems(document("\t```kotlin", "\t```")))
        assertTrue(refusal(document("\t```kotlin", "\t```")).startsWith("doc.md:3: unrecognised fence-like line"))
    }

    @Test
    fun `kt kts Kotlin and KOTLIN are read and reported as odd`() {
        for (spelling: String in listOf("```kt", "```kts", "```Kotlin", "```KOTLIN")) {
            assertEquals(listOf("doc.md:3"), problems(document(spelling)), "spelling $spelling")
            assertEquals(listOf("doc.md:3"), DocFences.scan("doc.md", document(spelling)).map { it.location }, "spelling $spelling is a fence, not a refusal")
        }
    }

    @Test
    fun `kotlin with more text and other info strings are refused and reported`() {
        for (spelling: String in listOf("```kotlin title=\"x\"", "```{.kotlin}", "```kotlin,ignore", "```kotlin-script", "```kotlin ")) {
            assertEquals(listOf("doc.md:3"), problems(document(spelling, "```")), "spelling $spelling")
            assertTrue(refusal(document(spelling, "```")).startsWith("doc.md:3: unrecognised fence-like line"), "spelling $spelling")
        }
    }

    @Test
    fun `the exact spelling without a marker is reported and with a marker is not`() {
        assertEquals(listOf("doc.md:3"), problems(document("```kotlin")))
        val marked: List<String> = listOf("<!-- listing -->", "```kotlin", broken, "```")
        assertEquals(emptyList(), problems(marked))
        assertEquals(1, DocFences.kotlinFences("doc.md", marked).size)
    }

    @Test
    fun `a closing line other than exactly three backticks is refused`() {
        for (closing: String in listOf("````", "``` ", "```kotlin", " ```", "~~~", "``` \t", "\t```")) {
            val lines: List<String> = listOf("<!-- listing -->", "```kotlin", broken, closing, "tail")
            assertTrue(refusal(lines).startsWith("doc.md:4: unrecognised fence-like line"), "closing '$closing': ${refusal(lines)}")
        }
    }

    @Test
    fun `a fence of another language is not kotlin and a word is not a fence`() {
        val lines: List<String> = listOf("```bash", "echo kotlin", "```", "Use the word kotlin in a sentence.", "``", "~~", "```text", "x", "```")
        assertEquals(emptyList(), DocFences.oddKotlinFences("doc.md", lines))
        assertEquals(emptyList(), DocFences.kotlinFences("doc.md", lines))
        assertEquals(2, DocFences.scan("doc.md", lines).size)
    }

    @Test
    fun `inline code that holds three backticks is refused instead of being skipped`() {
        val message: String = refusal(listOf("```bash", "echo", "```", "Use ```kotlin``` inline."))
        assertTrue(message.startsWith("doc.md:4: unrecognised fence-like line"), message)
        assertEquals(listOf("doc.md:4"), DocFences.oddKotlinFences("doc.md", listOf("```bash", "echo", "```", "Use ```kotlin``` inline.")).map { it.location })
    }

    @Test
    fun `an unclosed fence fails loudly instead of passing`() {
        val message: String = refusal(listOf("text", "```kotlin", broken))
        assertTrue(message.startsWith("doc.md:2: the fence opened by '```kotlin' is never closed"), message)
    }

    @Test
    fun `a mismatched closing character leaves the fence open and is refused`() {
        assertTrue(refusal(listOf("```kotlin", broken, "~~~")).startsWith("doc.md:3: unrecognised fence-like line"))
    }

    @Test
    fun `Windows line endings do not hide a fence and a lone carriage return is not a line`() {
        val lines: List<String> = "# Title\r\n\r\n```kotlin\r\n$broken\r\n```\r\n".split("\n")
        val fences: List<DocFence> = DocFences.scan("doc.md", lines)
        assertEquals(listOf("doc.md:3"), fences.map { it.location })
        assertEquals("```kotlin", fences.single().opening)
        assertEquals(listOf(broken), fences.single().body.map { it.removeSuffix("\r") })
    }
}
