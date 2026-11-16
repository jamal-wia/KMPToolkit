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

    // Containers (docs breaker round 3). DocFencesBreakerR3Test holds the reviewer's cases; these pin the rest of the reader.

    @Test
    fun `a kotlin fence in a block quote is reported even when it is spelled exactly and marked`() {
        assertEquals(listOf("doc.md:3"), problems(listOf("# Title", "", "> ```kotlin", "> $broken", "> ```", "", "end")))
        val marked: List<String> = listOf("<!-- listing -->", "> ```kotlin", "> $broken", "> ```")
        assertEquals(listOf("doc.md:2"), problems(marked))
        assertEquals(emptyList(), DocFences.kotlinFences("doc.md", marked))
    }

    @Test
    fun `tildes and a nested quote and list are read`() {
        assertEquals(listOf("doc.md:1"), problems(listOf("> ~~~~ kotlin", "> $broken", "> ~~~~")))
        assertEquals(listOf("doc.md:1"), problems(listOf("> - ```kotlin", ">   $broken", ">   ```")))
        assertEquals(listOf("doc.md:1"), problems(listOf("1. > ```kotlin", "   > $broken", "   > ```")))
    }

    @Test
    fun `an indented fence under a list item is in the item and is reported even when it is spelled exactly`() {
        val lines: List<String> = listOf("- item", "", "  ```kotlin", "  $broken", "  ```", "", "end")
        assertEquals(listOf("doc.md:3"), problems(lines))
        assertEquals(true, DocFences.scan("doc.md", lines).single().inContainer)
    }

    @Test
    fun `a fence of another language in a list item is closed in the item and does not swallow the next fence`() {
        val lines: List<String> = listOf("- ```bash", "  echo", "  ```", "", "<!-- listing -->", "```kotlin", "public interface Listed", "```")
        assertEquals(2, DocFences.scan("doc.md", lines).size)
        assertEquals(listOf("doc.md:6"), DocFences.kotlinFences("doc.md", lines).map { it.location })
        assertEquals(emptyList(), problems(lines))
    }

    @Test
    fun `a blank line inside a fence of a list item keeps the fence open`() {
        val lines: List<String> = listOf("- ```kotlin", "  first", "", "  second", "  ```")
        val fence: DocFence = DocFences.scan("doc.md", lines).single()
        assertEquals(listOf("first", "", "second"), fence.body)
    }

    @Test
    fun `a fence that is open when its block quote or list item ends fails loudly`() {
        val quote: IllegalStateException = assertFailsWith<IllegalStateException> {
            DocFences.scan("doc.md", listOf("# Title", "> ```kotlin", "> $broken", "", "end"))
        }
        assertTrue(quote.message.orEmpty().contains("doc.md:2"), quote.message)
        val item: IllegalStateException = assertFailsWith<IllegalStateException> {
            DocFences.scan("doc.md", listOf("- ```kotlin", "  $broken", "back at the margin", "  ```"))
        }
        assertTrue(item.message.orEmpty().contains("doc.md:1"), item.message)
    }

    @Test
    fun `a top-level fence after a list is not in the list`() {
        val lines: List<String> = listOf("- item", "", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:3"), problems(lines))
        assertEquals(false, DocFences.scan("doc.md", lines).single().inContainer)
    }

    @Test
    fun `emphasis and a thematic break do not open a container`() {
        val lines: List<String> = listOf("**bold** text", "---", "***", "```kotlin", broken, "```")
        assertEquals(listOf("doc.md:4"), problems(lines))
        assertEquals(false, DocFences.scan("doc.md", lines).single().inContainer)
    }

    @Test
    fun `fence characters in an indented code block are neither a fence nor a closing line`() {
        // Four columns of indentation, at the top level and relative to a list item: an indented code block.
        assertEquals(emptyList(), DocFences.scan("doc.md", listOf("text", "", "    ~~~kotlin", "    $broken", "    ~~~", "", "end")))
        assertEquals(emptyList(), DocFences.scan("doc.md", listOf("- item", "", "      ~~~kotlin", "      $broken", "      ~~~", "", "end")))
        // An over-indented line inside a fence is body text and does not close it.
        val fence: DocFence = DocFences.scan("doc.md", listOf("```bash", "    ```", "```")).single()
        assertEquals(listOf("    ```"), fence.body)
    }
}
