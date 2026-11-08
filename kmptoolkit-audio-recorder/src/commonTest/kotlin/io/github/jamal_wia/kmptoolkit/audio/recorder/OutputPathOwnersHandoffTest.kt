package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/** The provisional takeover of [OutputPathOwners]: who owns a path after a later claim ends. */
class OutputPathOwnersHandoffTest {

    @Test
    fun `an uncommitted later claim gives the path back when it is released newest first`() = runTest(timeout = 10.seconds) {
        val path = "/owners/handoff-chain.m4a"
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        val c = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        OutputPathOwners.commit(path, a)
        OutputPathOwners.claim(path, b)
        OutputPathOwners.claim(path, c)

        OutputPathOwners.release(b)
        OutputPathOwners.release(c)

        assertTrue(OutputPathOwners.deleteIfOwner(path, a) {}, "the first claim owns the path again")
        OutputPathOwners.release(a)
    }

    @Test
    fun `a committed later claim never hands the path back`() = runTest(timeout = 10.seconds) {
        val path = "/owners/handoff-committed.m4a"
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        OutputPathOwners.commit(path, a)
        OutputPathOwners.claim(path, b)
        OutputPathOwners.commit(path, b)

        OutputPathOwners.release(b)

        assertFalse(OutputPathOwners.deleteIfOwner(path, a) { error("the displaced claim must not delete") })
        OutputPathOwners.release(a)
    }

    @Test
    fun `a displaced claim released while the later one is pending leaves nothing leaked when that one fails`() =
        runTest(timeout = 10.seconds) {
            val path = "/owners/handoff-residual.m4a"
            val a = OutputPathClaim()
            val b = OutputPathClaim()
            val d = OutputPathClaim()
            OutputPathOwners.claim(path, a)
            OutputPathOwners.claim(path, b)

            OutputPathOwners.release(a)
            OutputPathOwners.release(b)

            assertFalse(OutputPathOwners.deleteIfOwner(path, a) { error("a left the registry") })
            OutputPathOwners.claim(path, d)
            assertTrue(OutputPathOwners.deleteIfOwner(path, d) {}, "a fresh claim owns the path")
            OutputPathOwners.release(d)
        }

    @Test
    fun `a delete by a pending owner gives the path back to the claim it displaced`() = runTest(timeout = 10.seconds) {
        val path = "/owners/handoff-delete.m4a"
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        OutputPathOwners.claim(path, b)

        assertTrue(OutputPathOwners.deleteIfOwner(path, b) {})

        assertTrue(OutputPathOwners.deleteIfOwner(path, a) {}, "the displaced claim owns again after the pending owner's delete")
        OutputPathOwners.release(a)
        OutputPathOwners.release(b)
    }
}
