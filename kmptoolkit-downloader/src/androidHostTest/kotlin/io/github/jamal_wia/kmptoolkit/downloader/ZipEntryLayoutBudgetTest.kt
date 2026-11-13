package io.github.jamal_wia.kmptoolkit.downloader

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B round 10, F-1: the layout check of one entry costs memory linear in the entry's name. Measured with a per-thread
 * allocation counter (not a clock): before the fix, 4000 segments (an 8 kB entry name) allocated about 61 MB in one
 * [ZipEntryLayout.conflict] call (a copy of every prefix, x4 per doubling = quadratic), 2000 about 15 MB, 1000 about 4 MB.
 * The zip maximum name (64 kB, 32 767 segments) would be about 4 GB allocated and about 1 GB live.
 */
class ZipEntryLayoutBudgetTest {
    @Test
    fun `an entry name of four thousand segments allocates a linear amount in the layout check`() {
        val name: String = "a/".repeat(4_000) + "f.bin"
        val segments: List<String> = resolveEntrySegments(name)!!
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before: Long = bean.getThreadAllocatedBytes(Thread.currentThread().id)
        val reason: String? = ZipEntryLayout().conflict(segments, false) { false }
        val allocated: Long = bean.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertNull(reason)
        // 8 kB of name: anything near a megabyte per kilobyte of name is a copy of every prefix.
        assertTrue(allocated < 4L shl 20, "one 8 kB entry name allocated $allocated bytes")
    }
}
