package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Adversarial inputs for [DocFences], the fence reader behind [DocsSamplesDriftTest]. Each case is a document whose fence holds a
 * snippet that does not compile (`val broken: Int = "text"`): the drift check must be red on it, because it cannot be green on a
 * snippet that no build ever compiled.
 */
class DocFencesTest {

    private val broken: String = """val broken: Int = "text""""

    private fun document(open: String, close: String = open.trim().takeWhile { it == '`' || it == '~' }): List<String> =
        listOf("# Title", "", open, broken, close, "", "end")

    /** What the drift check reports for [lines]: odd fences, plus fences it would read as samples (which then need a marker). */
    private fun problems(lines: List<String>): List<String> =
        DocFences.oddKotlinFences("doc.md", lines).map { it.location } +
            DocFences.kotlinFences("doc.md", lines).filter { !it.marker.startsWith("<!-- ") }.map { it.location }

    @Test
    fun `four backticks are a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("````kotlin")))
    }

    @Test
    fun `a space before the info string is a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("``` kotlin")))
    }

    @Test
    fun `tildes are a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("~~~kotlin")))
        assertEquals(listOf("doc.md:3"), problems(document("~~~~ kotlin")))
    }

    @Test
    fun `an indented fence in a list item is a kotlin fence the check reports`() {
        assertEquals(listOf("doc.md:3"), problems(document("   ````kotlin", "   ````")))
    }

    @Test
    fun `kt kts Kotlin and kotlin with more text are reported`() {
        for (spelling: String in listOf("```kt", "```kts", "```Kotlin", "```KOTLIN", "```kotlin title=\"x\"", "```{.kotlin}", "```kotlin,ignore")) {
            assertEquals(listOf("doc.md:3"), problems(document(spelling)), "spelling $spelling")
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
    fun `a longer closing fence closes a shorter one and a shorter one does not close a longer one`() {
        // A four-tick closing line closes a three-tick fence (CommonMark); a three-tick line inside a four-tick fence is body text.
        val longClose: List<String> = listOf("<!-- listing -->", "```kotlin", broken, "````", "tail")
        assertEquals(1, DocFences.kotlinFences("doc.md", longClose).size)
        val shortClose: List<String> = listOf("````markdown", "```kotlin", broken, "```", "````", "tail")
        assertEquals(emptyList(), DocFences.scan("doc.md", shortClose).filter { it.isKotlinLike })
        assertEquals(1, DocFences.scan("doc.md", shortClose).size)
    }

    @Test
    fun `a fence of another language and inline code are not kotlin`() {
        val lines: List<String> = listOf("```bash", "echo kotlin", "```", "Use ```kotlin``` inline.", "~~~text", "x", "~~~")
        assertEquals(emptyList(), DocFences.oddKotlinFences("doc.md", lines))
        assertEquals(emptyList(), DocFences.kotlinFences("doc.md", lines))
        assertEquals(2, DocFences.scan("doc.md", lines).size)
    }

    @Test
    fun `an unclosed fence fails loudly instead of passing`() {
        val error: IllegalStateException = assertFailsWith<IllegalStateException> { DocFences.scan("doc.md", listOf("text", "```kotlin", broken)) }
        assertTrue(error.message.orEmpty().contains("doc.md:2"), error.message)
    }

    @Test
    fun `a mismatched closing character leaves the fence open`() {
        assertFailsWith<IllegalStateException> { DocFences.scan("doc.md", listOf("```kotlin", broken, "~~~")) }
    }
}
