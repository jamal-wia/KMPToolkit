package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 10 (breaker findings A8-m1): the JCA boundary types every provider failure that is not an `Error`,
 * including a `Throwable` that is neither an `Exception` nor an `Error` (Kotlin has no checked exceptions, so a provider written in
 * Kotlin may throw one, and a Java one can sneaky-throw one). The breaker's own test (`JcaA8BreakerR8Test`) covers all five boundary
 * functions and the store call; here the part it only notes: what the random source had written is wiped on EVERY way out, an
 * `Error` included (wiping is not catching, so rule 3 of the boundary still holds: the Error leaves as the same instance).
 */
class JcaBoundaryRound10Test {

    /** Not an `Exception` and not an `Error`. */
    private class OddThrowable(message: String) : Throwable(message)

    /** A random source that writes recognisable bytes into the caller's buffer and then fails with [failure]. */
    private class WritesThenFails(private val failure: Throwable) : JcaRandom() {
        @Volatile var buffer: ByteArray? = null
        @Volatile var written: ByteArray? = null

        override fun nextBytes(bytes: ByteArray) {
            bytes.fill(0x55)
            buffer = bytes
            written = bytes.copyOf()
            throw failure
        }
    }

    @Test
    fun `a Throwable that is neither an Exception nor an Error from the random source is typed and the half-written bytes are wiped`() {
        val failure = OddThrowable("a provider that throws a bare Throwable")
        val source = WritesThenFails(failure)
        val typed: JcaFailure = assertFailsWith<JcaFailure> { Jca.randomBytes(source, 32) }
        assertSame(failure, typed.cause, "the original is kept as the cause")
        assertTrue(source.written!!.all { it == 0x55.toByte() }, "control: the source wrote before it failed")
        assertTrue(source.buffer!!.all { it == 0.toByte() }, "what the source had written is wiped")
    }

    @Test
    fun `an Error from the random source leaves as the same instance and the half-written bytes are wiped too`() {
        for (error: Error in listOf(InternalError("the DRBG could not read its seed"), OutOfMemoryError("oom"), StackOverflowError())) {
            val source = WritesThenFails(error)
            val thrown: Error = assertFailsWith<Error> { Jca.randomBytes(source, 32) }
            assertSame(error, thrown, "${error::class.simpleName}: an Error is never typed (rule 3)")
            assertTrue(source.written!!.all { it == 0x55.toByte() }, "${error::class.simpleName}: control: the source wrote before it failed")
            assertTrue(source.buffer!!.all { it == 0.toByte() }, "${error::class.simpleName}: wiping is not catching: the bytes are wiped")
        }
    }

    @Test
    fun `a source that succeeds keeps its bytes`() {
        val bytes: ByteArray = Jca.randomBytes(object : JcaRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0x2A)
            }
        }, 16)
        assertEquals(List(16) { 0x2A.toByte() }, bytes.toList(), "control: the success path returns what the source wrote")
    }
}
