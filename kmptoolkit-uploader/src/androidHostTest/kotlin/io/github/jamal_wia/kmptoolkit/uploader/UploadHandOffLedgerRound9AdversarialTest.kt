package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.ExistingWorkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Round 9 (A2), mutation survivors and clock: the hand-off ledger under a monotonic clock that wraps, and the
 * attempt-stamp rules of a counted hand-off at their edges (equal, older, newer, unstamped) which the earlier tests pin
 * only for an older duplicate.
 */
class UploadHandOffLedgerRound9AdversarialTest {

    private var now: Long = 0L
    private val ledger = UploadHandOffLedger { now }

    private fun request(name: String) = UploadRequest("https://example.invalid/$name", fields = emptyList())

    @Test
    fun `the prune age is measured by subtraction so a nanoTime that wraps is neither early nor late`() {
        val age: Long = UploadHandOffLedger.PRUNE_AGE.inWholeNanoseconds
        // finished 1000 ns before the counter wraps; one second later (wrapped) it is far younger than the prune age.
        now = Long.MAX_VALUE - 1_000L
        ledger.jobStarted("a")
        ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false)
        ledger.jobEnded("a")
        now = Long.MIN_VALUE + 1_000_000_000L
        ledger.policyForHandOff("other") // prunes
        assertEquals(ExistingWorkPolicy.REPLACE, ledger.policyForHandOff("a"), "a finished mark one second old was pruned across the wrap")

        // Finished just before the wrap, read one millisecond past the prune age (wrapped): dropped.
        val ledger2 = UploadHandOffLedger { now }
        now = Long.MAX_VALUE - 1_000L
        ledger2.jobStarted("b")
        ledger2.finishServing("b", ledger2.handOffsSeen("b"), stopped = false)
        ledger2.jobEnded("b")
        now = (Long.MAX_VALUE - 1_000L) + age + 1_000_000L
        ledger2.policyForHandOff("other")
        assertNull(ledger2.entry("b"), "a finished mark older than the prune age survived across the wrap")
        assertEquals(ExistingWorkPolicy.KEEP, ledger2.policyForHandOff("b"))

        // A nanoTime that starts negative.
        val ledger3 = UploadHandOffLedger { now }
        now = Long.MIN_VALUE + 5L
        ledger3.jobStarted("c")
        ledger3.finishServing("c", ledger3.handOffsSeen("c"), stopped = false)
        ledger3.jobEnded("c")
        now = Long.MIN_VALUE + 5L + age - 1_000_000L
        ledger3.policyForHandOff("other")
        assertEquals(ExistingWorkPolicy.REPLACE, ledger3.policyForHandOff("c"), "a mark one millisecond younger than the prune age was dropped")
    }

    @Test
    fun `two hand-offs of the same attempt keep the newer request, a newer attempt replaces, an older one never`() {
        ledger.jobStarted("a")
        val first = request("first")
        val second = request("second")
        val third = request("third")
        val stale = request("stale")
        ledger.policyForHandOff("a", first, attempt = 3)
        ledger.policyForHandOff("a", second, attempt = 3)
        assertSame(second, ledger.latestRequest("a"), "a second hand-off of the same attempt must carry the request the job serves")
        ledger.policyForHandOff("a", third, attempt = 4)
        assertSame(third, ledger.latestRequest("a"))
        ledger.policyForHandOff("a", stale, attempt = 2)
        assertSame(third, ledger.latestRequest("a"), "a duplicate of an older attempt replaced a newer attempt's request")
        assertEquals(4, ledger.latestHandOff("a")?.attempt)
    }

    @Test
    fun `every counted hand-off moves the counter, a stale duplicate included, so the job looks again`() {
        ledger.jobStarted("a")
        ledger.policyForHandOff("a", request("new"), attempt = 5)
        val seen: Long = ledger.handOffsSeen("a")
        ledger.policyForHandOff("a", request("dup"), attempt = 4)
        assertEquals(seen + 1, ledger.handOffsSeen("a"), "a late duplicate was not counted, so a job could finish past it")
        assertEquals(UploadHandOffLedger.Finish.MISSED, ledger.finishServing("a", seen, stopped = false))
    }

    @Test
    fun `an unstamped hand-off is never older than a stamped one and replaces it, and a stamped one then replaces it back`() {
        ledger.jobStarted("a")
        val stamped = request("stamped")
        val unstamped = request("unstamped")
        val later = request("later")
        ledger.policyForHandOff("a", stamped, attempt = 7)
        ledger.policyForHandOff("a", unstamped, attempt = null)
        assertSame(unstamped, ledger.latestRequest("a"))
        assertNull(ledger.latestHandOff("a")?.attempt)
        ledger.policyForHandOff("a", later, attempt = 1)
        assertSame(later, ledger.latestRequest("a"), "against an unstamped latest no attempt is older")
    }

    @Test
    fun `a second job of the item that starts while one is active keeps the counted request`() {
        ledger.jobStarted("a")
        val counted = request("counted")
        ledger.policyForHandOff("a", counted, attempt = 2)
        ledger.jobStarted("a")
        assertSame(counted, ledger.latestRequest("a"), "a concurrent job start dropped the counted hand-off")
        ledger.jobEnded("a")
        ledger.jobEnded("a")
        ledger.jobStarted("a")
        assertNull(ledger.latestRequest("a"), "a job that starts alone inherited an earlier request")
    }

    @Test
    fun `forget with a job unwinding drops the request and stamp but keeps the count, and without a job drops the entry`() {
        ledger.jobStarted("a")
        ledger.policyForHandOff("a", request("x"), attempt = 1)
        val seen: Long = ledger.handOffsSeen("a")
        ledger.forget("a")
        assertNull(ledger.latestRequest("a"))
        assertNull(ledger.latestHandOff("a"))
        assertEquals(seen, ledger.handOffsSeen("a"), "forget lost the hand-off count of a job still unwinding")
        assertEquals(1, ledger.entry("a")?.activeJobs)
        ledger.jobEnded("a")
        ledger.forget("a")
        assertNull(ledger.entry("a"))
    }

    @Test
    fun `a job that starts after the last one finished does not inherit the request that was counted for it`() {
        ledger.jobStarted("a")
        ledger.policyForHandOff("a", request("counted"), attempt = 2)
        assertEquals(UploadHandOffLedger.Finish.MISSED, ledger.finishServing("a", 0L, stopped = false))
        assertEquals(UploadHandOffLedger.Finish.FINISHED, ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false))
        ledger.jobEnded("a")
        // The finished mark keeps the entry (and its request) until a hand-off consumes it; a job that starts now begins from its own.
        assertEquals(true, ledger.entry("a") != null)
        ledger.jobStarted("a")
        assertNull(ledger.latestRequest("a"), "a new job inherited the request counted for a finished one")
        assertNull(ledger.latestHandOff("a"))
    }
}
