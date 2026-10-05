package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Sha256Test {

    private val lower: String = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `parse accepts 64 lowercase hex digits and exposes them`() {
        assertEquals(lower, Sha256.parse(lower).hex)
    }

    @Test
    fun `parse accepts uppercase and normalises to lowercase`() {
        val parsed: Sha256 = Sha256.parse(lower.uppercase())

        assertEquals(lower, parsed.hex)
        assertEquals(Sha256.parse(lower), parsed)
    }

    @Test
    fun `parse rejects a wrong length with a message naming the problem`() {
        val tooShort: IllegalArgumentException =
            assertFailsWith<IllegalArgumentException> { Sha256.parse(lower.dropLast(1)) }
        assertTrue("64 hex digits" in tooShort.message.orEmpty(), tooShort.message)
        assertFailsWith<IllegalArgumentException> { Sha256.parse(lower + "0") }
        assertFailsWith<IllegalArgumentException> { Sha256.parse("") }
    }

    @Test
    fun `parse rejects a non-hex character`() {
        assertFailsWith<IllegalArgumentException> { Sha256.parse("g" + lower.drop(1)) }
        assertFailsWith<IllegalArgumentException> { Sha256.parse(" " + lower.drop(1)) }
    }

    @Test
    fun `parseOrNull answers null for null and blank and garbled and wrong length`() {
        assertNull(Sha256.parseOrNull(null))
        assertNull(Sha256.parseOrNull(""))
        assertNull(Sha256.parseOrNull("   "))
        assertNull(Sha256.parseOrNull("not-a-hash"))
        assertNull(Sha256.parseOrNull(lower.dropLast(1)))
        assertNull(Sha256.parseOrNull("z" + lower.drop(1)))
    }

    @Test
    fun `parseOrNull parses a valid value of either case`() {
        assertEquals(Sha256.parse(lower), Sha256.parseOrNull(lower))
        assertEquals(Sha256.parse(lower), Sha256.parseOrNull(lower.uppercase()))
    }

    @Test
    fun `equality and hashCode follow the digest and not the instance or the input case`() {
        val a: Sha256 = Sha256.parse(lower)
        val b: Sha256 = Sha256.parse(lower.uppercase())
        val other: Sha256 = Sha256.parse("0".repeat(64))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, other)
        assertNotEquals<Any?>(a, lower)
        assertNotEquals<Any?>(a, null)
    }

    @Test
    fun `toString names the digest`() {
        assertEquals("Sha256($lower)", Sha256.parse(lower).toString())
    }
}
