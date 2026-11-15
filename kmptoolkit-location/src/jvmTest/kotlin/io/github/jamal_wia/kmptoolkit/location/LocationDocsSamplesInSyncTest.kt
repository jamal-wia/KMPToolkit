package io.github.jamal_wia.kmptoolkit.location

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the Kotlin samples of the Markdown pages in `docs/kmptoolkit-location` and the tests that run them identical.
 *
 * A sample is written twice on purpose: once in a test source set, where it is compiled and run, and once in a
 * docs page, where a reader finds it. Nothing but this test ties the two copies together, so an edit of either
 * one that is not made in the other fails here.
 *
 * The contract, for every file of `docs/kmptoolkit-location`:
 * - a docs block is an HTML comment line `<!-- docs-sample: NAME -->` followed by a fenced `kotlin` block;
 * - its twin is the region of a test source file of `kmptoolkit-location` or `kmptoolkit-location-testing` that
 *   starts at a line holding only the two-slash comment `docs-sample: NAME` and ends at the next line holding only
 *   the two-slash comment `end docs-sample`, dedented;
 * - the text of the two is equal, line for line;
 * - a region named by no docs block fails, a docs block without a region fails, a name defined twice fails.
 *
 * It reads source files as text, so it also covers the regions of the Android host and iOS source sets that this
 * JVM run does not compile (their own tests do). The root is found from the working directory upwards, as the other
 * modules' docs tests do; every path is built with `File(parent, child)`.
 */
class LocationDocsSamplesInSyncTest {

    private class Sample(val name: String, val text: String, val origin: String)

    private val project: File = File(System.getProperty("user.dir")).absoluteFile

    private val root: File =
        generateSequence(project) { dir: File -> dir.parentFile }
            .first { dir: File -> File(File(dir, "docs"), "kmptoolkit-location").isDirectory }

    private val docsDir: File = File(File(root, "docs"), "kmptoolkit-location")

    private fun dedent(lines: List<String>): List<String> {
        val indent: Int = lines.filter { it.isNotBlank() }.minOfOrNull { line: String -> line.length - line.trimStart().length } ?: 0
        return lines.map { line: String -> if (line.isBlank()) "" else line.substring(indent) }
    }

    /** Every Kotlin file of the test source sets (names ending in Test) of the two modules. */
    private fun testSources(): List<File> =
        listOf("kmptoolkit-location", "kmptoolkit-location-testing").flatMap { module: String ->
            val src: File = File(File(root, module), "src")
            val sets: List<File> = src.listFiles { f: File -> f.isDirectory && f.name.endsWith("Test") }.orEmpty().sortedBy { it.name }
            sets.flatMap { set: File ->
                File(set, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
            }
        }

    private fun regions(): Map<String, Sample> {
        val open = Regex("""^\s*// docs-sample: (\S+)\s*$""")
        val close = Regex("""^\s*// end docs-sample\s*$""")
        val found: MutableMap<String, Sample> = linkedMapOf()
        for (file: File in testSources()) {
            val lines: List<String> = file.readText().replace("\r\n", "\n").split("\n")
            var i: Int = 0
            while (i < lines.size) {
                val name: String? = open.matchEntire(lines[i])?.groupValues?.get(1)
                if (name == null) {
                    i++
                    continue
                }
                val end: Int = (i + 1 until lines.size).firstOrNull { close.matches(lines[it]) }
                    ?: error("${file.name}:${i + 1}: the region '$name' is never closed")
                val text: String = dedent(lines.subList(i + 1, end)).joinToString("\n").trim('\n')
                assertTrue(name !in found, "the sample '$name' is defined twice: ${found[name]?.origin} and ${file.name}:${i + 1}")
                found[name] = Sample(name, text, "${file.name}:${i + 1}")
                i = end + 1
            }
        }
        return found
    }

    private fun docsBlocks(): List<Sample> {
        val marker = Regex("""^<!-- docs-sample: (\S+) -->\s*$""")
        val blocks: MutableList<Sample> = mutableListOf()
        for (page: File in docsDir.listFiles { f: File -> f.isFile && f.extension == "md" }.orEmpty().sortedBy { it.name }) {
            val lines: List<String> = page.readText().replace("\r\n", "\n").split("\n")
            for ((index: Int, line: String) in lines.withIndex()) {
                val name: String = marker.matchEntire(line)?.groupValues?.get(1) ?: continue
                val origin = "${page.name}:${index + 1}"
                assertTrue(
                    index + 1 < lines.size && lines[index + 1] == "```kotlin",
                    "$origin: the marker of '$name' must be followed by a ```kotlin fence on the next line",
                )
                val end: Int = (index + 2 until lines.size).firstOrNull { lines[it] == "```" }
                    ?: error("$origin: the block of '$name' is never closed")
                blocks += Sample(name, lines.subList(index + 2, end).joinToString("\n"), origin)
            }
        }
        return blocks
    }

    @Test
    fun `every docs sample block equals its test region`() {
        val regions: Map<String, Sample> = regions()
        val blocks: List<Sample> = docsBlocks()
        assertTrue(regions.isNotEmpty(), "no docs-sample region found under ${root.name}: the scan is broken")
        assertTrue(blocks.isNotEmpty(), "no docs-sample block found in ${docsDir.path}: the scan is broken")

        for (block: Sample in blocks) {
            val region: Sample = regions[block.name]
                ?: error("${block.origin}: the docs block '${block.name}' has no region in a test source file")
            assertEquals(
                region.text,
                block.text,
                "${block.origin}: the docs block '${block.name}' differs from its test region at ${region.origin}",
            )
        }
    }

    @Test
    fun `every test region is printed in the docs and every block name is used once`() {
        val regions: Map<String, Sample> = regions()
        val blocks: List<Sample> = docsBlocks()

        val printed: Set<String> = blocks.map { it.name }.toSet()
        assertEquals(emptySet(), regions.keys - printed, "regions that no docs page prints")
        val twice: List<String> = blocks.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
        assertEquals(emptyList(), twice, "docs sample names printed more than once")
    }
}
