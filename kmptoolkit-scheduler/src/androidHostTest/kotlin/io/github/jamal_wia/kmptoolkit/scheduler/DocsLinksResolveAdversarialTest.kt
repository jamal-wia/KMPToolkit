package io.github.jamal_wia.kmptoolkit.scheduler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 20 (docs as a contract): every relative link in the notification and scheduler docs (and the
 * root README, which links to them) points at a file that exists, and when it carries a `#fragment`,
 * at a heading that exists under GitHub's slug rules: lower case, punctuation other than `-` and `_`
 * removed, each space a hyphen (so a heading with a code span such as ``separate `-testing` artifacts``
 * gives a double hyphen), a repeated heading getting `-1`, `-2`. A link that does not resolve sends a
 * reader of the published docs to the top of a page or to nothing.
 */
class DocsLinksResolveAdversarialTest {

    private val root: File = generateSequence(File("").absoluteFile) { dir: File -> dir.parentFile }
        .first { dir: File -> File(dir, "settings.gradle.kts").isFile }

    private fun docsOf(module: String): List<File> =
        File(File(root, "docs"), module).listFiles { file: File -> file.extension == "md" }.orEmpty().sortedBy { it.name }

    private fun slugs(file: File): Set<String> {
        val seen: MutableMap<String, Int> = mutableMapOf()
        val out: MutableSet<String> = mutableSetOf()
        var inFence = false
        for (line: String in file.readLines()) {
            if (line.startsWith("```")) inFence = !inFence
            if (inFence) continue
            val heading: String = HEADING.matchEntire(line)?.groupValues?.get(1) ?: continue
            val base: String = heading.trim().lowercase()
                .replace("`", "")
                .replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "")
                .replace(' ', '-')
            val count: Int = seen.getOrDefault(base, 0)
            seen[base] = count + 1
            out += if (count == 0) base else "$base-$count"
        }
        return out
    }

    private fun brokenLinks(file: File): List<String> {
        val broken: MutableList<String> = mutableListOf()
        var inFence = false
        file.readLines().forEachIndexed { index: Int, line: String ->
            if (line.startsWith("```")) inFence = !inFence
            if (inFence) return@forEachIndexed
            for (match: MatchResult in LINK.findAll(line)) {
                val target: String = match.groupValues[1]
                if (target.startsWith("http") || target.startsWith("mailto:")) continue
                val path: String = target.substringBefore('#')
                val fragment: String? = target.substringAfter('#', "").ifEmpty { null }
                val resolved: File = if (path.isEmpty()) file else File(file.parentFile, path).normalize()
                val where = "${file.name}:${index + 1} -> $target"
                if (!resolved.exists()) {
                    broken += "$where (no such file)"
                } else if (fragment != null && resolved.extension == "md" && fragment !in slugs(resolved)) {
                    broken += "$where (no heading ${resolved.name}#$fragment)"
                }
            }
        }
        return broken
    }

    @Test
    fun `every relative link of the notification and scheduler docs resolves to a file and a heading`() {
        val files: List<File> = docsOf("kmptoolkit-notification") + docsOf("kmptoolkit-scheduler")
        assertTrue(files.size >= 12, "the harness found the docs: ${files.size}")
        val broken: List<String> = files.flatMap { brokenLinks(it) }
        assertTrue(broken.isEmpty(), "links that do not resolve:\n" + broken.joinToString("\n"))
    }

    private companion object {
        val HEADING: Regex = Regex("^#{1,6}\\s+(.*)$")
        val LINK: Regex = Regex("\\]\\(([^)\\s]+)\\)")
    }
}
