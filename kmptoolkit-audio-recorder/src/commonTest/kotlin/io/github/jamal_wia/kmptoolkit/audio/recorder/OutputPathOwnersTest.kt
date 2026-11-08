package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The ownership registry behind the released-prepare-tail fence, driven directly. */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputPathOwnersTest {

    @Test
    fun `the only claimant may delete and its entry is consumed`() = runTest(timeout = 10.seconds) {
        val path = "/owners/sole.m4a"
        val a = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        var deleted = 0
        assertTrue(OutputPathOwners.deleteIfOwner(path, a) { deleted++ })
        assertFalse(OutputPathOwners.deleteIfOwner(path, a) { deleted++ }, "a consumed claim deletes once")
        assertEquals(1, deleted)
        OutputPathOwners.release(a)
    }

    @Test
    fun `a later claim takes the path over and the earlier owner may no longer delete`() = runTest(timeout = 10.seconds) {
        val path = "/owners/handoff.m4a"
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        OutputPathOwners.claim(path, b)
        var deleted = 0
        assertFalse(OutputPathOwners.deleteIfOwner(path, a) { deleted++ })
        // Releasing the earlier claim must not drop the newer one.
        OutputPathOwners.release(a)
        assertTrue(OutputPathOwners.deleteIfOwner(path, b) { deleted++ })
        assertEquals(1, deleted)
        OutputPathOwners.release(b)
    }

    @Test
    fun `different paths never interfere and a released claim owns nothing`() = runTest(timeout = 10.seconds) {
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        OutputPathOwners.claim("/owners/one.m4a", a)
        OutputPathOwners.claim("/owners/two.m4a", b)
        OutputPathOwners.release(a)
        assertFalse(OutputPathOwners.deleteIfOwner("/owners/one.m4a", a) { error("not the owner any more") })
        assertTrue(OutputPathOwners.deleteIfOwner("/owners/two.m4a", b) {})
        OutputPathOwners.release(b)
    }

    @Test
    fun `a claim that meets a delete in progress waits for it to finish`() = runTest(timeout = 10.seconds) {
        val path = "/owners/during-delete.m4a"
        val a = OutputPathClaim()
        val b = OutputPathClaim()
        OutputPathOwners.claim(path, a)
        var claimed = false
        // The delete body is not suspending, so the "in progress" window is entered from inside the
        // delete callback itself: the claim starts there and must still be waiting when it returns.
        val claiming: Job = launch(start = CoroutineStart.LAZY) {
            OutputPathOwners.claim(path, b)
            claimed = true
        }
        OutputPathOwners.deleteIfOwner(path, a) {
            claiming.start()
            runCurrent()
            assertFalse(claimed, "the claim overtook a running delete")
        }
        // The marker is gone now, so the waiting claim can go through.
        runCurrent()
        claiming.join()
        assertTrue(claimed)
        assertTrue(OutputPathOwners.deleteIfOwner(path, b) {})
        OutputPathOwners.release(a)
        OutputPathOwners.release(b)
    }
}
