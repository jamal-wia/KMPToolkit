package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClaimsInDoubtTest {

    @Test
    fun `an unheld lease is granted as asked`() {
        val id = "claims-plain"
        assertEquals(500L, ClaimsInDoubt.reserve(id, 500L))
        ClaimsInDoubt.drop(id, 500L)
    }

    @Test
    fun `a held lease is shifted one millisecond later`() {
        val id = "claims-later"
        val first: Long = ClaimsInDoubt.reserve(id, 500L)
        val second: Long = ClaimsInDoubt.reserve(id, 500L)
        assertEquals(501L, second)
        ClaimsInDoubt.drop(id, first)
        ClaimsInDoubt.drop(id, second)
    }

    @Test
    fun `leases at the top of the Long range stay distinct`() {
        val id = "claims-top"
        val granted: List<Long> = List(4) { ClaimsInDoubt.reserve(id, Long.MAX_VALUE) }
        assertEquals(4, granted.toSet().size, "grants: $granted")
        assertEquals(Long.MAX_VALUE, granted.first())
        granted.forEach { ClaimsInDoubt.drop(id, it) }
        // everything dropped: the top is free again
        assertEquals(Long.MAX_VALUE, ClaimsInDoubt.reserve(id, Long.MAX_VALUE).also { ClaimsInDoubt.drop(id, it) })
    }

    @Test
    fun `a lease just below the top with the top held does not collide`() {
        val id = "claims-near-top"
        val a: Long = ClaimsInDoubt.reserve(id, Long.MAX_VALUE)
        val b: Long = ClaimsInDoubt.reserve(id, Long.MAX_VALUE - 1)
        val c: Long = ClaimsInDoubt.reserve(id, Long.MAX_VALUE - 1)
        assertEquals(3, setOf(a, b, c).size)
        assertNotEquals(a, b)
        listOf(a, b, c).forEach { ClaimsInDoubt.drop(id, it) }
    }
}
