package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.NameRules
import io.github.jamal_wia.kmptoolkit.files.internal.PathRules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4 of the breaker for unit G (character: path algebra): seeded property tests of the lexical rules against
 * oracles written from the documented sentences of docs/kmptoolkit-files/04-api-reference.md, not from the engine
 * code. Every random choice comes from `Random(seed)` with the fixed seeds below; the seed is in every message.
 */
class PathAlgebraR4Test {

    private val posixPieces: List<String> = listOf(
        "a", "b", "..", ".", "...", "", " ", "A", "\\", "c:", "~", "-", "a.", ".a",
        "\u00e9", "e\u0301", "\u00df", "\uD83D\uDE00", "\u212a", "\u0130", "\u0000", "x".repeat(300),
    )
    private val windowsPieces: List<String> = listOf(
        "a", "B", "..", ".", "", "c:", "D:", "d:x", "srv", "share", "?", "UNC", "a.", " ", "CON", "e\u0301", "\u00e9", "\u0000",
    )
    private val windowsPrefixes: List<String> = listOf(
        "", "C:", "C:\\", "c:/", "\\\\srv\\sh", "\\\\", "//srv/sh/", "\\", "/", "\\\\srv", "C:..", "\\\\.\\", "\\\\?\\C:\\",
    )

    private fun posixPath(random: Random, rooted: Boolean): String {
        val builder: StringBuilder = StringBuilder(if (rooted) "/".repeat(random.nextInt(1, 4)) else "")
        repeat(random.nextInt(0, 8)) {
            builder.append(posixPieces[random.nextInt(posixPieces.size)])
            builder.append("/".repeat(random.nextInt(1, 3)))
        }
        if (random.nextBoolean() && builder.isNotEmpty() && !rooted) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    private fun windowsPath(random: Random): String {
        val separators: List<String> = listOf("/", "\\", "//", "\\\\", "/\\")
        val builder: StringBuilder = StringBuilder(windowsPrefixes[random.nextInt(windowsPrefixes.size)])
        repeat(random.nextInt(0, 7)) {
            builder.append(windowsPieces[random.nextInt(windowsPieces.size)])
            builder.append(separators[random.nextInt(separators.size)])
        }
        if (random.nextBoolean() && builder.isNotEmpty()) builder.setLength(builder.length - 1)
        return builder.toString()
    }

    /** The documented rule of `normalizeLexically` on POSIX, written as a stack walk. */
    private fun posixOracle(path: String): String {
        val rooted: Boolean = path.startsWith("/")
        val stack: MutableList<String> = ArrayList()
        // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
        for (segment: String in path.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> when {
                    stack.isNotEmpty() && stack.last() != ".." -> stack.removeAt(stack.lastIndex)
                    rooted -> Unit
                    else -> stack.add("..")
                }
                else -> stack.add(segment)
            }
        }
        return when {
            rooted -> "/" + stack.joinToString("/")
            stack.isEmpty() -> "."
            else -> stack.joinToString("/")
        }
    }

    @Test
    fun `posix normalizeLexically equals the stack walk oracle on random hostile paths`() {
        val seed: Long = 40_401
        val random: Random = Random(seed)
        repeat(30_000) {
            val path: String = posixPath(random, rooted = random.nextBoolean())
            assertEquals(posixOracle(path), PathRules.Posix.normalizeLexically(path), "seed=$seed path='$path'")
        }
    }

    @Test
    fun `a trailing separator or a trailing dot segment never changes the normalized form`() {
        val seed: Long = 40_402
        val random: Random = Random(seed)
        repeat(10_000) {
            val path: String = posixPath(random, rooted = random.nextBoolean())
            if (path.isEmpty()) return@repeat
            val base: String = PathRules.Posix.normalizeLexically(path)
            // path-hygiene-ignore: PH4 the lexical algebra test builds POSIX literals on purpose; no native path is involved
            assertEquals(base, PathRules.Posix.normalizeLexically(path + "/"), "seed=$seed path='$path' + /")
            // path-hygiene-ignore: PH4 the lexical algebra test builds POSIX literals on purpose; no native path is involved
            assertEquals(base, PathRules.Posix.normalizeLexically("$path/."), "seed=$seed path='$path' + /.")
            // path-hygiene-ignore: PH4 the lexical algebra test builds POSIX literals on purpose; no native path is involved
            assertEquals(base, PathRules.Posix.normalizeLexically("$path//"), "seed=$seed path='$path' + //")
        }
    }

    @Test
    fun `windows normalizeLexically ignores which separator was written and never changes rootedness or the drive`() {
        val seed: Long = 40_403
        val random: Random = Random(seed)
        val rules: PathRules = PathRules.Windows
        repeat(30_000) {
            val path: String = windowsPath(random)
            // SUSPECT G16 (adversarial/G/suspects/r4-G16-*): three leading separators change isAbsolute; reported, not asserted here.
            if (path.length >= 3 && path.take(3).all { rules.isSeparator(it) }) return@repeat
            val normalized: String = rules.normalizeLexically(path)
            val flipped: String = path.replace('/', '\\')
            assertEquals(normalized, rules.normalizeLexically(flipped), "seed=$seed path='$path' separators flipped")
            assertEquals(rules.isAbsolute(path), rules.isAbsolute(normalized), "seed=$seed '$path' -> '$normalized' absoluteness")
            assertEquals(rules.rootLength(path) > 0, rules.rootLength(normalized) > 0, "seed=$seed '$path' -> '$normalized' rootedness")
            assertFalse(normalized.contains('/'), "seed=$seed '$path' -> '$normalized' keeps a forward slash")
            if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) {
                assertEquals(path.substring(0, 2), normalized.substring(0, 2), "seed=$seed '$path' -> '$normalized' lost the drive")
            }
        }
    }

    @Test
    fun `parentOf joined with the last segment gives the same normalized path`() {
        val seed: Long = 40_404
        val random: Random = Random(seed)
        repeat(20_000) {
            val path: String = "/" + posixPath(random, rooted = false)
            val parent: String? = PathRules.Posix.parentOf(path)
            val trimmed: String = path.trimEnd('/')
            if (trimmed.isEmpty()) {
                assertNull(parent, "seed=$seed a root has no parent: '$path'")
                return@repeat
            }
            // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
            val last: String = trimmed.substringAfterLast('/')
            assertNotNull(parent, "seed=$seed '$path' has a last segment '$last' and so a parent")
            if (last == "." || last == "..") return@repeat
            assertEquals(
                PathRules.Posix.normalizeLexically(path),
                PathRules.Posix.normalizeLexically(PathRules.Posix.join(parent, last)),
                "seed=$seed path='$path' parent='$parent' last='$last'",
            )
            // The parent never grows and never ends in a separator unless it is the root.
            assertTrue(parent.length < trimmed.length, "seed=$seed '$path' -> parent '$parent'")
            assertTrue(parent == "/" || !parent.endsWith("/"), "seed=$seed '$path' -> parent '$parent' ends in a separator")
        }
    }

    @Test
    fun `join never doubles the separator at the joint and keeps both halves`() {
        val seed: Long = 40_405
        val random: Random = Random(seed)
        repeat(20_000) {
            val parent: String = "/" + posixPath(random, rooted = false)
            var child: String = posixPath(random, rooted = false).trimStart('/')
            if (child.isEmpty()) child = "x"
            val joined: String = PathRules.Posix.join(parent, child)
            assertTrue(joined.startsWith(parent.trimEnd('/')), "seed=$seed parent='$parent' child='$child' -> '$joined'")
            assertTrue(joined.endsWith(child), "seed=$seed parent='$parent' child='$child' -> '$joined'")
            val joint: Int = parent.trimEnd('/').length
            assertEquals('/', joined[joint], "seed=$seed joint of '$parent' + '$child'")
            if (!parent.endsWith("//")) {
                assertTrue(joined.length == joint + 1 + child.length, "seed=$seed parent='$parent' child='$child' -> '$joined' length")
            }
        }
        // Windows: a child that starts with a separator is rooted but not absolute, so it is accepted, and the joint stays single.
        val windowsRandom: Random = Random(seed + 1)
        repeat(5_000) {
            val segmentCount: Int = windowsRandom.nextInt(0, 4)
            val parent: String = "C:\\" + (0 until segmentCount).joinToString("\\") { "p$it" } + (if (segmentCount == 0) "" else listOf("", "\\", "/")[windowsRandom.nextInt(3)])
            val child: String = listOf("\\", "/")[windowsRandom.nextInt(2)] + "x" + windowsRandom.nextInt(10)
            val joined: String = PathRules.Windows.join(parent, child)
            assertTrue(!joined.substring(2).contains("\\\\") && !joined.substring(2).contains("\\/") && !joined.substring(2).contains("/\\") && !joined.substring(2).contains("//"),
                "seed=$seed windows parent='$parent' child='$child' -> '$joined' doubles the separator")
            assertTrue(joined.endsWith(child.substring(1)), "seed=$seed windows '$joined' lost the child '$child'")
        }
        assertEquals("C:\\a\\x", PathRules.Windows.join("C:\\a\\", "\\x"))
        assertFailsWith<IllegalArgumentException> { PathRules.Posix.join("/a", "") }
        assertFailsWith<IllegalArgumentException> { PathRules.Posix.join("/a", "/b") }
        assertFailsWith<IllegalArgumentException> { PathRules.Windows.join("C:\\a", "D:\\b") }
        assertFailsWith<IllegalArgumentException> { PathRules.Windows.join("C:\\a", "\\\\srv\\sh") }
    }

    @Test
    fun `resolveInside on posix is null or a descendant of the normalized base with no dot segments`() {
        val seed: Long = 40_406
        val random: Random = Random(seed)
        repeat(30_000) {
            val base: String = "/" + posixPath(random, rooted = false)
            val relative: String = posixPath(random, rooted = false).trimStart('/')
            val resolved: String? = PathRules.Posix.resolveInside(base, relative)
            val hostile: Boolean = relative.contains('\u0000') || base.contains('\u0000')
            if (hostile) {
                assertNull(resolved, "seed=$seed a NUL must give null: base='$base' relative='$relative'")
                return@repeat
            }
            val normalizedBase: String = posixOracle(base)
            // Oracle of "stays inside": walk the relative segments from the base depth; leaving it is an escape,
            // coming back by name is allowed because the result is judged, not the route.
            val expected: String? = run {
                // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
                val baseSegments: List<String> = normalizedBase.split('/').filter { it.isNotEmpty() }
                // path-hygiene-ignore: PH4 the lexical algebra test builds POSIX literals on purpose; no native path is involved
                val joined: String = posixOracle(if (relative.isEmpty()) base else "$base/$relative")
                // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
                val joinedSegments: List<String> = joined.split('/').filter { it.isNotEmpty() }
                val inside: Boolean = joinedSegments.size >= baseSegments.size &&
                    baseSegments.indices.all { joinedSegments[it] == baseSegments[it] }
                if (inside) joined else null
            }
            assertEquals(expected, resolved, "seed=$seed base='$base' relative='$relative'")
            if (resolved != null) {
                // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
                assertFalse(resolved.split('/').any { it == "." || it == ".." }, "seed=$seed '$resolved' keeps a dot segment")
                assertTrue(PathRules.Posix.isAbsolute(resolved), "seed=$seed '$resolved' is not absolute")
            }
        }
    }

    @Test
    fun `resolveInside against a relative base never climbs above it`() {
        val seed: Long = 40_412
        val random: Random = Random(seed)
        val bases: List<String> = listOf(".", "", "a", "a/b", "./a", "a/./b/", "..", "../a")
        val pieces: List<String> = listOf("a", "b", "..", ".", "", "c")
        repeat(20_000) {
            val base: String = bases[random.nextInt(bases.size)]
            val relative: String = (0 until random.nextInt(0, 6)).joinToString("/") { pieces[random.nextInt(pieces.size)] }.trimStart('/')
            val resolved: String? = PathRules.Posix.resolveInside(base, relative)
            if (resolved == null) return@repeat
            // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
            val baseSegments: List<String> = posixOracle(base).split('/').filter { it.isNotEmpty() && it != "." }
            // path-hygiene-ignore: PH3 the lexical algebra test splits POSIX literals on purpose; no native path is involved
            val resolvedSegments: List<String> = posixOracle(resolved).split('/').filter { it.isNotEmpty() && it != "." }
            assertTrue(resolvedSegments.size >= baseSegments.size && baseSegments.indices.all { resolvedSegments[it] == baseSegments[it] },
                "seed=$seed base='$base' relative='$relative' -> '$resolved' is not under the base")
            // Past the base nothing may climb: the part of the result below the base holds no '..'.
            assertFalse(resolvedSegments.drop(baseSegments.size).contains(".."), "seed=$seed base='$base' relative='$relative' -> '$resolved' climbs out of the base")
        }
        assertNull(PathRules.Posix.resolveInside(".", "../x"))
        assertNull(PathRules.Posix.resolveInside("", "a/../../x"))
        assertNull(PathRules.Posix.resolveInside("a", "../../x"))
        assertEquals("a/b", PathRules.Posix.resolveInside("a", "b"))
        assertEquals("x", PathRules.Posix.resolveInside(".", "a/../x"))
    }

    @Test
    fun `resolveInside on windows never leaves the root of the base`() {
        val seed: Long = 40_407
        val random: Random = Random(seed)
        val rules: PathRules = PathRules.Windows
        val bases: List<String> = listOf("C:\\base", "c:/base/", "\\\\srv\\sh\\base", "C:\\", "\\\\srv\\sh", "D:\\a\\b")
        repeat(30_000) {
            val base: String = bases[random.nextInt(bases.size)]
            val relative: String = windowsPath(random)
            val resolved: String? = rules.resolveInside(base, relative)
            if (resolved == null) return@repeat
            val baseRoot: String = rules.parse(rules.normalizeLexically(base)).root
            assertEquals(baseRoot, rules.parse(resolved).root, "seed=$seed base='$base' relative='$relative' -> '$resolved' changed root")
            assertTrue(rules.isAbsolute(resolved), "seed=$seed base='$base' relative='$relative' -> '$resolved' not absolute")
            assertFalse(relative.contains('\u0000'), "seed=$seed NUL accepted: '$relative'")
            // A rooted or drive-qualified relative path is never relative.
            assertTrue(rules.rootLength(relative) == 0, "seed=$seed '$relative' has a root yet resolved to '$resolved'")
        }
        // The named hostile cases.
        assertNull(rules.resolveInside("C:\\base", "D:x"))
        assertNull(rules.resolveInside("C:\\base", "D:\\x"))
        assertNull(rules.resolveInside("C:\\base", "\\x"))
        assertNull(rules.resolveInside("C:\\base", "\\\\srv\\sh\\x"))
        assertNull(rules.resolveInside("C:\\base", "..\\base2\\x"))
        assertNull(rules.resolveInside("C:\\base", "..\\..\\..\\x"))
        assertEquals("C:\\base\\x", rules.resolveInside("C:\\base", "..\\base\\x"))
    }

    @Test
    fun `host independent name rules agree with an oracle on random hostile names`() {
        val seed: Long = 40_408
        val random: Random = Random(seed)
        val alphabet: List<String> = listOf(
            "a", "Z", "0", ".", " ", "/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\u0000", "\u0001", "\u001f", "\u00e9",
            "\u00b9", "\uD83D\uDE00", "\uD83D", "\uDE00", "CON", "con", "NUL", "COM1", "LPT9", "aux", "PRN", "COM", "x".repeat(60),
        )
        repeat(40_000) {
            val name: String = (0 until random.nextInt(0, 7)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
            val bytes: Int = NameRules.utf8Length(name)
            val wellFormed: Boolean = bytes >= 0
            if (wellFormed) {
                assertEquals(name.encodeToByteArray().size, bytes, "seed=$seed utf8 length of '${name.length} chars'")
            }
            val stem: String = name.substringBefore('.').trimEnd(' ')
            val reserved: Boolean = stem.equals("CON", true) || stem.equals("PRN", true) || stem.equals("AUX", true) ||
                stem.equals("NUL", true) ||
                (stem.length == 4 && (stem.startsWith("COM", true) || stem.startsWith("LPT", true)) && stem[3] in "0123456789\u00b9\u00b2\u00b3")
            assertEquals(reserved, FileNames.isWindowsReserved(name), "seed=$seed reserved('$name')")
            val posixPlain: Boolean = name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000') &&
                wellFormed && bytes <= 255
            assertEquals(posixPlain, NameRules.isPlain(name, windows = false), "seed=$seed posix plain('$name')")
            val windowsPlain: Boolean = posixPlain && name.none { it in "<>:\"|?*\\" || it < ' ' } &&
                !name.endsWith('.') && !name.endsWith(' ') && !reserved
            assertEquals(windowsPlain, NameRules.isPlain(name, windows = true), "seed=$seed windows plain('$name')")
        }
        // Named boundary cases of the documentation.
        assertTrue(FileNames.isWindowsReserved("CON .txt"))
        assertTrue(FileNames.isWindowsReserved("NUL "))
        assertTrue(FileNames.isWindowsReserved("com\u00b9.log"))
        assertTrue(FileNames.isWindowsReserved("LPT0"))
        assertFalse(FileNames.isWindowsReserved("COM10"))
        assertFalse(FileNames.isWindowsReserved("CONX"))
        assertFalse(FileNames.isWindowsReserved("xCON"))
        assertTrue(NameRules.isPlain("a".repeat(255), windows = false))
        assertFalse(NameRules.isPlain("a".repeat(256), windows = false))
        assertTrue(NameRules.isPlain("\u00e9".repeat(127), windows = false), "254 bytes")
        assertFalse(NameRules.isPlain("\u00e9".repeat(128), windows = false), "256 bytes")
    }

    @Test
    fun `isTempName accepts exactly prefix digits tmp for a random prefix`() {
        val seed: Long = 40_409
        val random: Random = Random(seed)
        val prefixes: List<String> = listOf(".kmptoolkit-", "p", "1", ".", "a.tmp", "\u00e9", "-1", "x9")
        repeat(20_000) {
            val prefix: String = prefixes[random.nextInt(prefixes.size)]
            val digitCount: Int = random.nextInt(0, 23)
            val digits: String = (0 until digitCount).joinToString("") { random.nextInt(10).toString() }
            val name: String = "$prefix$digits.tmp"
            // A digit run is read greedily from the end of the prefix only when the prefix itself ends in digits: the
            // contract is "<prefix><1..19 ASCII digits>.tmp", so the digits start right after the prefix.
            assertEquals(digitCount in 1..19, FileNames.isTempName(name, prefix), "seed=$seed name='$name' prefix='$prefix'")
            val mutated: String = name.replaceFirst(".tmp", ".tmq")
            assertFalse(FileNames.isTempName(mutated, prefix), "seed=$seed suffix changed: '$mutated'")
            if (digitCount in 1..19) {
                assertFalse(FileNames.isTempName(prefix + digits + "x.tmp", prefix), "seed=$seed junk before the suffix")
                assertFalse(FileNames.isTempName(prefix + "\u0663" + ".tmp", prefix), "an Arabic-Indic digit is not an ASCII digit")
            }
        }
    }

    @Test
    fun `PathKey of folding Always equates unicode forms and case and keeps different names apart`() {
        val seed: Long = 40_410
        val random: Random = Random(seed)
        val pieces: List<String> = listOf(
            "a", "A", "k", "\u212a", "K", "s", "S", "\u017f", "\u00df", "\u1e9e", "ss", "\u03c3", "\u03c2", "\u03a3", "\u00e9", "e\u0301", "E\u0301",
            "\u00c5", "\u212b", "A\u030a", "\u1e9b\u0323", "\u0345", "\u1fb7", "\u1fc7", "\u1ff7", "\u03b1\u0345", "\u1fb3", "\u1fbc\u0342", "\u1fcc\u0342", "\u1ffc\u0342", "\u0130", "i", "I", "\u0131", "\u01c5", "\u01c4", "\u01c6", "x", "y", "..", ".", "z",
        )
        repeat(20_000) {
            val path: String = "/" + (0 until random.nextInt(1, 6)).joinToString("/") { pieces[random.nextInt(pieces.size)] + pieces[random.nextInt(pieces.size)] }
            val key: String = PathKey.of(path, CaseFolding.Always)
            assertEquals(key, PathKey.of(normalizeNfc(path), CaseFolding.Always), "seed=$seed NFC spelling of '$path'")
            assertEquals(key, PathKey.of(normalizeNfd(path), CaseFolding.Always), "seed=$seed NFD spelling of '$path'")
            // A capital with a prosgegrammeni followed by a perispomeni changes which letter carries the mark when the whole
            // string is upper-cased (a different name, not a case spelling of the same one): kept out of the case variants.
            val caseVariantsApply: Boolean = path.none { it == '\u1fbc' || it == '\u1fcc' || it == '\u1ffc' }
            if (caseVariantsApply) {
                assertEquals(key, PathKey.of(path.lowercase(), CaseFolding.Always), "seed=$seed lower case of '$path'")
                assertEquals(key, PathKey.of(path.uppercase(), CaseFolding.Always), "seed=$seed upper case of '$path'")
            }
            // path-hygiene-ignore: PH4 the lexical algebra test builds POSIX literals on purpose; no native path is involved
            assertEquals(key, PathKey.of("$path///", CaseFolding.Always), "seed=$seed trailing separators of '$path'")
            assertEquals(key, PathKey.of(path.replace("/", "//"), CaseFolding.Always), "seed=$seed doubled separators of '$path'")
            // Never maps to a key that is not a fixed point of itself in its normalized-lexical part.
            assertEquals(PathKey.of(path, CaseFolding.Never), PathKey.of(PathKey.of(path, CaseFolding.Never), CaseFolding.Never), "seed=$seed Never is idempotent")
        }
        // Names that are different files must not share a key (the lock would serialize them for nothing).
        assertTrue(PathKey.of("/d/a", CaseFolding.Always) != PathKey.of("/d/b", CaseFolding.Always))
        assertTrue(PathKey.of("/d/a/b", CaseFolding.Always) != PathKey.of("/d/ab", CaseFolding.Always))
        assertTrue(PathKey.of("/d/a", CaseFolding.Always) != PathKey.of("/e/a", CaseFolding.Always))
        assertEquals("/d/a", PathKey.of("/d/x/../a/", CaseFolding.Never))
    }

    @Test
    fun `resolveInside joined with a plain name stays inside and its parent is the base`() {
        val seed: Long = 40_411
        val random: Random = Random(seed)
        repeat(20_000) {
            val rawBase: String = "/" + posixPath(random, rooted = false)
            if (rawBase.contains('\u0000')) return@repeat
            val base: String = PathRules.Posix.normalizeLexically(rawBase)
            val name: String = posixPieces[random.nextInt(posixPieces.size)]
            if (!NameRules.isPlain(name, windows = false) || name.contains('\u0000')) return@repeat
            val resolved: String = assertNotNull(PathRules.Posix.resolveInside(base, name), "seed=$seed base='$base' name='$name'")
            assertEquals(base, PathRules.Posix.parentOf(resolved) ?: "/", "seed=$seed base='$base' name='$name' -> '$resolved'")
        }
    }
}
