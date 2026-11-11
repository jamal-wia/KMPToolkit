package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
