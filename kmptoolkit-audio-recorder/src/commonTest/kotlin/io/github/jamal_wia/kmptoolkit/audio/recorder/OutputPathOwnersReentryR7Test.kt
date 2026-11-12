package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Round 7, mutation survivors of the path registry: what a delete body may do to the registry while
 * its delete is in progress. A delete in progress is a marker no other operation may step over.
 */
class OutputPathOwnersReentryR7Test {

    @Test
    fun `a second delete by the same claim from inside the delete body is refused`() {
        val path = "/r7/reentry/a.m4a"
        val claim = OutputPathClaim()
        runBlocking { OutputPathOwners.claim(path, claim) }
        var outer = 0
        var inner = 0
        var innerResult = true

        val outerResult: Boolean = OutputPathOwners.deleteIfOwner(path, claim) {
            outer++
            innerResult = OutputPathOwners.deleteIfOwner(path, claim) { inner++ }
        }

        assertTrue(outerResult)
        assertFalse(innerResult, "a delete already in progress must not start a second one")
        assertEquals(1, outer)
        assertEquals(0, inner)
        OutputPathOwners.release(claim)
    }

    @Test
    fun `releasing the deleting claim from inside its delete body does not let a newer claim step over the delete`() {
        val path = "/r7/reentry/b.m4a"
        val first = OutputPathClaim()
        val second = OutputPathClaim()
        runBlocking { OutputPathOwners.claim(path, first) }
        var admittedDuringDelete: Unit? = Unit

        OutputPathOwners.deleteIfOwner(path, first) {
            OutputPathOwners.release(first)
            admittedDuringDelete = runBlocking { withTimeoutOrNull(150) { OutputPathOwners.claim(path, second) } }
        }

        assertNull(admittedDuringDelete, "a newer claim was admitted while the delete was still running")
        runBlocking { withTimeoutOrNull(2_000) { OutputPathOwners.claim(path, second) } }
        OutputPathOwners.release(second)
        OutputPathOwners.release(first)
    }
}
