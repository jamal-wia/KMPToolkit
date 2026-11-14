package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round 1 of the breaker for unit G: the lexical path rules under seeded random hostile input, on both syntaxes. */
class PathRulesFuzzR1Test {

    private val posixPieces: List<String> = listOf("a", "B", "..", ".", "", "...", " ", "c:", "\\", "\u00e9", "a.", "~1")
    private val windowsPieces: List<String> = listOf("a", "B", "..", ".", "", "c:", "C:", "srv", "share", "?", "UNC", "a.", " ", "CON")
    private val windowsPrefixes: List<String> = listOf("", "C:", "C:\\", "c:/", "\\\\srv\\sh", "\\\\", "//srv/sh/", "\\", "/", "\\\\?\\C:\\", "\\\\srv", "C:..")
    private val separators: List<String> = listOf("/", "\\", "//", "\\\\", "/\\")

    private fun randomPath(random: Random, prefixes: List<String>, pieces: List<String>, separators: List<String>): String {
        val builder: StringBuilder = StringBuilder(prefixes[random.nextInt(prefixes.size)])
        repeat(random.nextInt(0, 7)) {
            builder.append(pieces[random.nextInt(pieces.size)])
            builder.append(separators[random.nextInt(separators.size)])
        }
        if (random.nextBoolean() && builder.isNotEmpty()) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    private fun segmentsOf(rules: PathRules, path: String): List<String> = rules.parse(path).segments

    private fun forBothSyntaxes(block: (PathRules, List<String>, List<String>, List<String>) -> Unit) {
        block(PathRules.Posix, listOf("", "/", "//", "///"), posixPieces, listOf("/", "//"))
        block(PathRules.Windows, windowsPrefixes, windowsPieces, separators)
    }

    @Test
    fun `normalizeLexically never throws and is idempotent on random paths`() = forBothSyntaxes { rules, prefixes, pieces, seps ->
        val random: Random = Random(20260513)
        repeat(20_000) {
            val path: String = randomPath(random, prefixes, pieces, seps)
            val once: String = rules.normalizeLexically(path)
            val twice: String = rules.normalizeLexically(once)
            assertEquals(once, twice, "not idempotent for '$path' under ${rules::class.simpleName}")
        }
    }

    @Test
    fun `normalizeLexically keeps posix absolute paths absolute and relative paths relative`() {
        val random: Random = Random(7)
        repeat(20_000) {
            val path: String = randomPath(random, listOf("", "/", "//", "///"), posixPieces, listOf("/", "//"))
            val normalized: String = PathRules.Posix.normalizeLexically(path)
            assertEquals(PathRules.Posix.isAbsolute(path), PathRules.Posix.isAbsolute(normalized), "'$path' -> '$normalized'")
        }
    }

    @Test
    fun `a normalized absolute path has no dot segments and no empty segments`() = forBothSyntaxes { rules, prefixes, pieces, seps ->
        val random: Random = Random(11)
        repeat(20_000) {
            val path: String = randomPath(random, prefixes, pieces, seps)
            if (!rules.isAbsolute(path)) return@repeat
            val normalized: String = rules.normalizeLexically(path)
            for (segment: String in segmentsOf(rules, normalized)) {
                assertTrue(segment != "." && segment != ".." && segment.isNotEmpty(), "'$path' -> '$normalized' keeps '$segment'")
            }
        }
    }

    @Test
    fun `parentOf of a joined plain name is the normalized parent`() = forBothSyntaxes { rules, prefixes, pieces, seps ->
        val random: Random = Random(13)
        val names: List<String> = listOf("x", "name.txt", "\u00e9", "a b", "~1")
        repeat(20_000) {
            val base: String = randomPath(random, prefixes, pieces, seps)
            if (!rules.isAbsolute(base)) return@repeat
            val name: String = names[random.nextInt(names.size)]
            val joined: String = rules.join(base, name)
            val parent: String = assertNotNull(rules.parentOf(joined), "no parent of '$joined' (base '$base')")
            assertEquals(rules.normalizeLexically(base), rules.normalizeLexically(parent), "base '$base' joined '$joined' parent '$parent'")
        }
    }

    @Test
    fun `resolveInside is null or a path whose segments begin with the base segments`() = forBothSyntaxes { rules, prefixes, pieces, seps ->
        val random: Random = Random(17)
        repeat(30_000) {
            val base: String = randomPath(random, prefixes, pieces, seps)
            val relative: String = randomPath(random, listOf(""), pieces, seps)
            val resolved: String? = rules.resolveInside(base, relative)
            if (resolved == null) return@repeat
            val normalizedBase: String = rules.normalizeLexically(base)
            val baseParsed = rules.parse(normalizedBase)
            val resolvedParsed = rules.parse(resolved)
            assertEquals(baseParsed.root, resolvedParsed.root, "base '$base' relative '$relative' -> '$resolved'")
            val baseSegments: List<String> = baseParsed.segments.filter { it != "." }
            val resolvedSegments: List<String> = resolvedParsed.segments.filter { it != "." }
            assertTrue(resolvedSegments.size >= baseSegments.size, "base '$base' relative '$relative' -> '$resolved'")
            for (i: Int in baseSegments.indices) {
                assertEquals(baseSegments[i], resolvedSegments[i], "base '$base' relative '$relative' -> '$resolved'")
            }
            assertTrue(
                resolvedSegments.drop(baseSegments.size).none { it == ".." },
                "base '$base' relative '$relative' -> '$resolved' climbs out of the base",
            )
        }
    }

    @Test
    fun `resolveInside of an absolute relative is always null`() = forBothSyntaxes { rules, prefixes, pieces, seps ->
        val random: Random = Random(19)
        repeat(20_000) {
            val base: String = randomPath(random, prefixes, pieces, seps)
            val relative: String = randomPath(random, prefixes, pieces, seps)
            if (rules.isAbsolute(relative)) assertNull(rules.resolveInside(base, relative), "base '$base' relative '$relative'")
        }
    }

    @Test
    fun `resolveInside with a dotdot that climbs out of the base is null whatever follows`() = forBothSyntaxes { rules, _, _, _ ->
        // The final path of "../sibling/x" lies outside "/base": reaching a name equal to the base's own name again
        // must not read as "inside".
        val base: String = if (rules === PathRules.Posix) "/top/base" else "C:\\top\\base"
        val sibling: String = rules.join(rules.parentOf(base)!!, "base2")
        assertNull(rules.resolveInside(base, "../base2/x"), sibling)
        assertNull(rules.resolveInside(base, "../../top2/base/x"))
        assertNull(rules.resolveInside(base, ".."))
        assertNull(rules.resolveInside(base, "a/../.."))
        assertNotNull(rules.resolveInside(base, "a/.."))
        assertEquals(rules.normalizeLexically(base), rules.resolveInside(base, ""))
        assertEquals(rules.normalizeLexically(base), rules.resolveInside(base, "."))
    }

    @Test
    fun `a posix name that looks like a drive or a dotted name is just a name`() {
        val rules: PathRules = PathRules.Posix
        assertEquals("/base/c:", rules.resolveInside("/base", "c:"))
        assertEquals("/base/..a", rules.resolveInside("/base", "..a"))
        assertEquals("/base/a\\b", rules.resolveInside("/base", "a\\b"))
        assertFalse(rules.isAbsolute("c:/x"))
        assertFalse(rules.isAbsolute("\\\\srv\\sh"))
    }

    @Test
    fun `windows keys fold drive letter case and separators and dot segments of one spelling family`() {
        fun key(path: String): String = foldCase(PathRules.Windows.normalizeLexically(path))
        assertEquals(key("C:\\Data\\File.TXT"), key("c:/data/file.txt"))
        assertEquals(key("C:\\Data\\File"), key("C:\\Data\\x\\..\\File\\"))
        assertEquals(key("\\\\SRV\\Share\\f"), key("//srv/share/f"))
        assertEquals(key("\\\\srv\\share\\a\\f"), key("\\\\srv\\share\\a\\.\\f"))
    }
}
