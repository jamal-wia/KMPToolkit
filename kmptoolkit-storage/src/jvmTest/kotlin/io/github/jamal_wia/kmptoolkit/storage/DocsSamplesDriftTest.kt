package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documentation and its samples cannot drift apart (plan 6.7). Every `kotlin` fence of `docs/kmptoolkit-storage/01..07` is one of:
 *
 * - `<!-- sample: id -->`: the fence must equal, line for line after the common indentation is removed, the code between the
 *   `// sample:begin id` and `// sample:end id` lines of exactly one source file (a file of `docs-samples`, the Android host test
 *   `AndroidDocsSamplesRound11Test`, the iOS test `IosDocsSamplesCompileTest` or the fixture test `DocsSamplesTestingTest`), and
 *   every such block must belong to exactly one fence: a sample nobody documents is dead code;
 * - `<!-- listing -->`: an API signature listing (written from the published API; nothing compares it with the ABI dump) or an excerpt of the library's own code, not a consumer sample;
 * - `<!-- build-script -->`: a Gradle snippet.
 *
 * A fence with no marker, or a marker that names nothing, fails. The compile and run checks of the samples themselves are
 * `:docs-samples:compileKotlinJvm` (as a consumer), `DocsSamplesDesktopRunTest`, `AndroidDocsSamplesRound11Test`,
 * `IosDocsSamplesCompileTest` and `DocsSamplesTestingTest`.
 */
class DocsSamplesDriftTest {

    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(File(File(it, "docs"), "kmptoolkit-storage"), "01-overview.md").isFile }

    private val docs: File = File(File(root, "docs"), "kmptoolkit-storage")

    private val sourceRoots: List<File> = listOf(
        File(File(File(File(root, "docs-samples"), "src"), "jvmMain"), "kotlin"),
        File(File(File(root, "kmptoolkit-storage"), "src"), "androidHostTest"),
        File(File(File(root, "kmptoolkit-storage"), "src"), "iosTest"),
        File(File(File(root, "kmptoolkit-storage-testing"), "src"), "jvmTest"),
    )

    private class Fence(val file: String, val line: Int, val marker: String, val text: String)

    private fun dedent(lines: List<String>): String {
        val indent: Int = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        return lines.map { if (it.isBlank()) "" else it.drop(indent).trimEnd() }.joinToString("\n").trim('\n')
    }

    private fun fences(): List<Fence> {
        val result: MutableList<Fence> = mutableListOf()
        val files: List<File> = docs.listFiles { f: File -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }
        for (file: File in files) {
            val lines: List<String> = file.readLines()
            var index = 0
            while (index < lines.size) {
                if (lines[index].trim() == "```kotlin") {
                    val marker: String = if (index > 0) lines[index - 1].trim() else ""
                    val body: MutableList<String> = mutableListOf()
                    var end: Int = index + 1
                    while (lines[end].trim() != "```") {
                        body.add(lines[end])
                        end++
                    }
                    result.add(Fence(file.name, index + 1, marker, dedent(body)))
                    index = end
                }
                index++
            }
        }
        return result
    }

    private fun sourceBlocks(): Map<String, MutableList<Pair<String, String>>> {
        val blocks: MutableMap<String, MutableList<Pair<String, String>>> = mutableMapOf()
        for (sourceRoot: File in sourceRoots) {
            assertTrue(sourceRoot.isDirectory, "missing sample source root $sourceRoot")
            for (file: File in sourceRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }) {
                val lines: List<String> = file.readLines()
                var index = 0
                while (index < lines.size) {
                    val begin: String? = Regex("""^\s*// sample:begin (\S+)\s*$""").matchEntire(lines[index])?.groupValues?.get(1)
                    if (begin != null) {
                        val body: MutableList<String> = mutableListOf()
                        var end: Int = index + 1
                        while (!Regex("""^\s*// sample:end ${Regex.escape(begin)}\s*$""").matches(lines[end])) {
                            body.add(lines[end])
                            end++
                        }
                        blocks.getOrPut(begin) { mutableListOf() }.add(file.name to dedent(body))
                        index = end
                    }
                    index++
                }
            }
        }
        return blocks
    }

    @Test
    fun `every kotlin fence of the storage docs is a registered sample, a listing or a build script`() {
        val bad: List<String> = fences().filter { fence: Fence ->
            !(fence.marker.startsWith("<!-- sample: ") && fence.marker.endsWith(" -->")) &&
                fence.marker != "<!-- listing -->" && fence.marker != "<!-- build-script -->"
        }.map { "${it.file}:${it.line}" }
        assertEquals(emptyList(), bad, "kotlin fences without a <!-- sample: id -->, <!-- listing --> or <!-- build-script --> marker")
    }

    @Test
    fun `no fence opens with a kotlin-like info string other than exactly kotlin`() {
        val odd: MutableList<String> = mutableListOf()
        for (file: File in docs.listFiles { f: File -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }) {
            file.readLines().forEachIndexed { index: Int, line: String ->
                val info: String = line.trim().removePrefix("```")
                val isFence: Boolean = line.trim().startsWith("```") && info.isNotEmpty()
                val kotlinLike: Boolean = info.lowercase().let { it.startsWith("kotlin") || it == "kt" || it.startsWith("kt ") || it.startsWith("kts") }
                if (isFence && kotlinLike && info != "kotlin") odd.add("${file.name}:${index + 1}")
            }
        }
        assertEquals(emptyList(), odd, "a fence spelled kt, kts, Kotlin or kotlin with extra text is skipped by the sample check: spell it exactly ```kotlin")
    }

    @Test
    fun `every documented sample equals its source block and every source block is documented`() {
        val documented: Map<String, List<Fence>> = fences()
            .filter { it.marker.startsWith("<!-- sample: ") }
            .groupBy { it.marker.removePrefix("<!-- sample: ").removeSuffix(" -->").trim() }
        val sources: Map<String, MutableList<Pair<String, String>>> = sourceBlocks()

        assertEquals(documented.keys.sorted(), sources.keys.sorted(), "documented sample ids against sample:begin ids of the sources")
        for ((id: String, fenceList: List<Fence>) in documented) {
            assertEquals(1, fenceList.size, "sample $id is documented ${fenceList.size} times")
            val blocks: List<Pair<String, String>> = sources.getValue(id)
            assertEquals(1, blocks.size, "sample $id has ${blocks.size} source blocks: ${blocks.map { it.first }}")
            assertEquals(blocks.single().second, fenceList.single().text, "sample $id: ${fenceList.single().file} differs from ${blocks.single().first}")
        }
    }
}
