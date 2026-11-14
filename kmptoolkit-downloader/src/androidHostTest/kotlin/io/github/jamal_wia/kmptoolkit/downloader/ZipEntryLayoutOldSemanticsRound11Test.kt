package io.github.jamal_wia.kmptoolkit.downloader

import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B round 11, Q2: the linear [ZipEntryLayout] (a tree with one node per segment, since B r10 F-1) against the semantics
 * of the class it replaced (a flat map of every prefix, copied verbatim from the tree before commit e8f6e06e), as a
 * differential over seeded random entry lists. Pure functions, no file system: the disk is a fake that answers like a
 * volume (prefix-closed, optionally folding case or Unicode normalization) and learns an entry when the layout accepts it.
 *
 * The contract compared is what the layout RETURNS (null, or the reason text). The set of paths it asks the disk
 * about is allowed to shrink (the ruling D-148: nothing below an absent path is asked) and is not compared.
 */
class ZipEntryLayoutOldSemanticsRound11Test {

    /** The class as it was before the trie, byte for byte. */
    private class OldLayout {
        private val kinds: HashMap<String, Boolean> = HashMap()

        fun conflict(segments: List<String>, isDirectory: Boolean, existsOnDisk: (String) -> Boolean): String? {
            val wanted: ArrayList<Pair<String, Boolean>> = ArrayList(segments.size)
            for (index: Int in segments.indices) {
                val path: String = segments.subList(0, index + 1).joinToString("/")
                val directory: Boolean = index < segments.lastIndex || isDirectory
                val known: Boolean? = kinds[path]
                if (known == null) {
                    if (existsOnDisk(path)) {
                        return "'$path' is the same name as another entry on this file system (names that differ only in case or Unicode normalization)"
                    }
                } else if (known != directory) {
                    return "'$path' is a file in one entry and a directory in another"
                }
                wanted.add(path to directory)
            }
            wanted.forEach { (path: String, directory: Boolean) -> kinds[path] = directory }
            return null
        }
    }

    /** A volume: paths that exist, compared under a fold. Prefix-closed by construction (an entry creates its parents). */
    private class Volume(private val fold: (String) -> String) {
        private val present: HashSet<String> = HashSet()

        fun exists(path: String): Boolean = fold(path) in present

        fun create(segments: List<String>) {
            for (index: Int in segments.indices) present.add(fold(segments.subList(0, index + 1).joinToString("/")))
        }
    }

    private val alphabet: List<String> = listOf("a", "A", "b", "B", "é", "é", "d", "x.y")
    private val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 11L

    private fun randomName(random: Random): String {
        val depth: Int = 1 + random.nextInt(4)
        val parts: List<String> = List(depth) {
            when (random.nextInt(12)) {
                0 -> "."
                1 -> ""
                2 -> ".."
                else -> alphabet[random.nextInt(alphabet.size)]
            }
        }
        val trailing: String = if (random.nextInt(4) == 0) "/" else ""
        return parts.joinToString("/") + trailing
    }

    private fun run(label: String, fold: (String) -> String, lists: Int, seedOffset: Long): Int {
        val random = Random(seed + seedOffset)
        var decided = 0
        repeat(lists) { listIndex: Int ->
            val old = OldLayout()
            val new = ZipEntryLayout()
            val oldDisk = Volume(fold)
            val newDisk = Volume(fold)
            // A few paths already on the disk before the walk, as a leftover would be.
            repeat(random.nextInt(3)) {
                val pre: List<String> = resolveEntrySegments(randomName(random)) ?: emptyList()
                if (pre.isNotEmpty()) {
                    oldDisk.create(pre)
                    newDisk.create(pre)
                }
            }
            val names: MutableList<String> = ArrayList()
            repeat(1 + random.nextInt(8)) { names.add(randomName(random)) }
            for ((entryIndex: Int, name: String) in names.withIndex()) {
                val segments: List<String> = resolveEntrySegments(name) ?: continue
                if (segments.isEmpty()) continue
                val isDirectory: Boolean = name.endsWith("/")
                val expected: String? = old.conflict(segments, isDirectory) { path: String -> oldDisk.exists(path) }
                val actual: String? = new.conflict(segments, isDirectory) { path: String -> newDisk.exists(path) }
                assertEquals(
                    expected,
                    actual,
                    "$label seed=$seed list=$listIndex entry=$entryIndex names=$names diverged at '$name'",
                )
                if (expected == null) {
                    oldDisk.create(segments)
                    newDisk.create(segments)
                }
                decided++
            }
        }
        return decided
    }

    private val caseSensitive: (String) -> String = { path: String -> path }
    private val caseFolding: (String) -> String = { path: String -> path.lowercase(Locale.ROOT) }
    private val normalizationFolding: (String) -> String = { path: String -> Normalizer.normalize(path, Normalizer.Form.NFD) }
    private val bothFolding: (String) -> String = { path: String ->
        Normalizer.normalize(path, Normalizer.Form.NFD).lowercase(Locale.ROOT)
    }

    @Test
    fun `the trie returns what the flat map returned over a case sensitive volume`() {
        assertTrue(run("case-sensitive", caseSensitive, 4_000, 0L) > 4_000)
    }

    @Test
    fun `the trie returns what the flat map returned over a volume that folds case`() {
        assertTrue(run("case-folding", caseFolding, 4_000, 1L) > 4_000)
    }

    @Test
    fun `the trie returns what the flat map returned over a volume that folds Unicode normalization`() {
        assertTrue(run("normalization-folding", normalizationFolding, 4_000, 2L) > 4_000)
    }

    @Test
    fun `the trie returns what the flat map returned over a volume that folds both`() {
        assertTrue(run("both-folding", bothFolding, 4_000, 3L) > 4_000)
    }

    @Test
    fun `a disk that holds a child but not its parent is asked once and the answer is the first new path`() {
        // Not a real volume (it is not prefix-closed), so the shrink of questions is allowed to change the answer here and
        // only here (D-148); pinned so that a change of this corner is a decision and not an accident.
        val disk: Set<String> = setOf("a/b")
        val old = OldLayout()
        val new = ZipEntryLayout()
        val expected: String? = old.conflict(listOf("a", "b", "c"), false) { it in disk }
        val actual: String? = new.conflict(listOf("a", "b", "c"), false) { it in disk }
        assertNull(actual, "a is absent so nothing below it is asked: the entry is accepted")
        assertNotNull(expected, "the flat map asked a/b as well and found it")
    }
}
