package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipEntryLayoutTest {

    private fun layout(): ZipEntryLayout = ZipEntryLayout()
    private val never: (String) -> Boolean = { false }

    @Test
    fun `the same file name twice is not a conflict`() {
        val layout: ZipEntryLayout = layout()
        assertNull(layout.conflict(listOf("a", "b.txt"), false, never))
        assertNull(layout.conflict(listOf("a", "b.txt"), false, never))
    }

    @Test
    fun `a name that is a file and a directory conflicts in either order`() {
        val fileFirst: ZipEntryLayout = layout()
        assertNull(fileFirst.conflict(listOf("a"), false, never))
        assertNotNull(fileFirst.conflict(listOf("a", "b.txt"), false, never))
        assertNotNull(layout().also { it.conflict(listOf("a"), false, never) }.conflict(listOf("a"), true, never))

        val dirFirst: ZipEntryLayout = layout()
        assertNull(dirFirst.conflict(listOf("a"), true, never))
        assertNotNull(dirFirst.conflict(listOf("a"), false, never))
    }

    @Test
    fun `a parent made by a child and later named as a directory is fine`() {
        val layout: ZipEntryLayout = layout()
        assertNull(layout.conflict(listOf("d", "x.txt"), false, never))
        assertNull(layout.conflict(listOf("d"), true, never))
    }

    @Test
    fun `a path the disk already holds under another spelling conflicts`() {
        val layout: ZipEntryLayout = layout()
        val disk: MutableSet<String> = mutableSetOf()
        val exists: (String) -> Boolean = { path: String -> path.lowercase() in disk.map { it.lowercase() } }
        assertNull(layout.conflict(listOf("Data.txt"), false, exists))
        disk += "Data.txt"
        assertEquals(null, layout.conflict(listOf("Data.txt"), false, exists))
        assertNotNull(layout.conflict(listOf("data.txt"), false, exists))
    }

    @Test
    fun `a new entry of four thousand segments asks the disk exactly once`() {
        val layout: ZipEntryLayout = layout()
        val asked: MutableList<String> = mutableListOf()
        val segments: List<String> = List(4_000) { "a" } + "f.bin"

        val reason: String? = layout.conflict(segments, false) { path: String -> asked.add(path); false }

        assertNull(reason)
        assertEquals(listOf("a"), asked, "only the first path the walk has not created is asked")
    }

    @Test
    fun `a second entry in the same tree asks only for its first new segment`() {
        val layout: ZipEntryLayout = layout()
        assertNull(layout.conflict(listOf("a", "b", "c", "f.bin"), false, never))
        val asked: MutableList<String> = mutableListOf()

        val reason: String? = layout.conflict(listOf("a", "b", "d", "e", "g.bin"), false) { path: String -> asked.add(path); false }

        assertNull(reason)
        assertEquals(listOf("a/b/d"), asked)
    }

    @Test
    fun `a path the walk created is never asked of the disk`() {
        val layout: ZipEntryLayout = layout()
        assertNull(layout.conflict(listOf("a", "b", "f.bin"), false, never))
        val asked: MutableList<String> = mutableListOf()
        val record: (String) -> Boolean = { path: String -> asked.add(path); true }

        assertNull(layout.conflict(listOf("a", "b", "f.bin"), false, record))
        assertNull(layout.conflict(listOf("a", "b"), true, record))
        assertNull(layout.conflict(listOf("a"), true, record))

        assertEquals(emptyList(), asked)
    }

    @Test
    fun `a first new path that is on the disk is the same name conflict and nothing below it is recorded`() {
        val layout: ZipEntryLayout = layout()
        val asked: MutableList<String> = mutableListOf()

        val reason: String? = layout.conflict(listOf("a", "b", "c"), false) { path: String -> asked.add(path); path == "a" }

        assertNotNull(reason)
        assertEquals(listOf("a"), asked)
        // The failed entry recorded nothing: the same walk asks again for "a" and sees it absent.
        assertNull(layout.conflict(listOf("a", "b", "c"), false, never))
    }

    @Test
    fun `a file and a directory conflict reason names the path that clashes`() {
        val layout: ZipEntryLayout = layout()
        assertNull(layout.conflict(listOf("a", "b"), false, never))

        val reason: String? = layout.conflict(listOf("a", "b", "c"), false, never)

        assertNotNull(reason)
        assertEquals("'a/b' is a file in one entry and a directory in another", reason)
    }

    @Test
    fun `the path length counts the base one separator per segment and every segment in UTF-8`() {
        assertEquals(4, entryPathBytes("/x", listOf("a")))
        assertEquals(2, entryPathBytes("/x", emptyList()))
        // At the Android limit (4095 is allowed, 4096 is not) with ASCII.
        assertEquals(4_095, entryPathBytes("", listOf("a".repeat(4_094))))
        assertEquals(4_096, entryPathBytes("", listOf("a".repeat(4_095))))
        // And at the iOS limit (1023 and 1024).
        assertEquals(1_023, entryPathBytes("", listOf("a".repeat(1_022))))
        assertEquals(1_024, entryPathBytes("", listOf("a".repeat(1_023))))
        // Two-byte characters count 2 each: 2,047 of them and the separator are 4,095 bytes, 2,048 are 4,097.
        assertEquals(4_095, entryPathBytes("", listOf("\u00e9".repeat(2_047))))
        assertEquals(4_097, entryPathBytes("", listOf("\u00e9".repeat(2_048))))
        // Three bytes (BMP) and four bytes (a surrogate pair), in the base as well as in a segment.
        assertEquals(3 + 1 + 3, entryPathBytes("\u65e5", listOf("\u672c")))
        assertEquals(4 + 1 + 4, entryPathBytes("\uD83D\uDE00", listOf("\uD83D\uDE00")))
    }

    @Test
    fun `the path length of any text equals the bytes encodeToByteArray writes`() {
        val samples: List<String> = listOf("", "abc", "caf\u00e9", "\u65e5\u672c\u8a9e", "\uD83D\uDE00x", "a\uD83D\uDE00\u00e9\u65e5")
        for (sample: String in samples) {
            assertEquals(sample.encodeToByteArray().size + 1, entryPathBytes("", listOf(sample)), "for ${sample.map { it.code }}")
        }
    }

    @Test
    fun `a lone surrogate counts as three bytes so that the limit is never undercounted`() {
        assertEquals(1 + 3, entryPathBytes("", listOf("\uD83D")))
        assertEquals(1 + 3 + 1, entryPathBytes("", listOf("\uDE00x")))
    }

    @Test
    fun `a long entry name is cut for a message and a surrogate pair is not split`() {
        assertEquals("short", shortEntryName("short"))
        assertEquals("a".repeat(64), shortEntryName("a".repeat(64)))
        assertEquals("a".repeat(64) + "\u2026", shortEntryName("a".repeat(65)))
        // A pair that straddles the cut is dropped whole.
        val straddling: String = "a".repeat(63) + "\uD83D\uDE00" + "b".repeat(10)
        assertEquals("a".repeat(63) + "\u2026", shortEntryName(straddling))
        assertTrue(shortEntryName("x".repeat(70_000)).length <= 65)
    }
}
