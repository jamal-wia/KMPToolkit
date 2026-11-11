package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.ExistingWorkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UploadHandOffLedgerTest {

    private var now: Long = 0L
    private val ledger = UploadHandOffLedger { now }

    @Test
    fun `a hand-off of an item with no job keeps and creates no entry`() {
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
        assertNull(ledger.entry("a"))
    }

    @Test
    fun `a hand-off while a job is serving keeps, and the job then serves again`() {
        ledger.jobStarted("a")
        val seen: Long = ledger.handOffsSeen("a")

        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))

        assertEquals(UploadHandOffLedger.Finish.MISSED, ledger.finishServing("a", seen, stopped = false))
        // The job reads the counter again before the next iteration, and then finishes.
        assertEquals(UploadHandOffLedger.Finish.FINISHED, ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false))
    }

    @Test
    fun `after a finished job the next hand-off replaces and the one behind it keeps`() {
        ledger.jobStarted("a")
        assertEquals(UploadHandOffLedger.Finish.FINISHED, ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false))

        assertEquals(ExistingWorkPolicy.REPLACE, ledger.policyForHandOff("a"))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
    }

    @Test
    fun `a stopped job leaves the item open`() {
        ledger.jobStarted("a")
        assertEquals(UploadHandOffLedger.Finish.LEFT_OPEN, ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = true))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
    }

    @Test
    fun `a job with another active job of the item leaves it open`() {
        ledger.jobStarted("a")
        ledger.jobStarted("a")
        assertEquals(UploadHandOffLedger.Finish.LEFT_OPEN, ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
    }

    @Test
    fun `an ended job that never finished serving leaves no entry`() {
        ledger.jobStarted("a")
        ledger.jobEnded("a")
        assertNull(ledger.entry("a"))
    }

    @Test
    fun `an ended job that finished serving keeps its mark until a hand-off consumes it`() {
        ledger.jobStarted("a")
        ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false)
        ledger.jobEnded("a")

        assertEquals(ExistingWorkPolicy.REPLACE, ledger.policyForHandOff("a"))
        assertNull(ledger.entry("a"))
    }

    @Test
    fun `forget clears a finished mark`() {
        ledger.jobStarted("a")
        ledger.finishServing("a", ledger.handOffsSeen("a"), stopped = false)
        ledger.jobEnded("a")

        ledger.forget("a")

        assertNull(ledger.entry("a"))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a"))
    }

    @Test
    fun `forget keeps the count of a job that is still unwinding`() {
        ledger.jobStarted("a")
        ledger.forget("a")
        assertEquals(1, ledger.entry("a")?.activeJobs)
        ledger.jobEnded("a")
        assertNull(ledger.entry("a"))
    }

    @Test
    fun `an inactive finished entry older than the prune age is dropped`() {
        ledger.jobStarted("old")
        ledger.finishServing("old", ledger.handOffsSeen("old"), stopped = false)
        ledger.jobEnded("old")
        now += 5.minutes.inWholeNanoseconds + 1.seconds.inWholeNanoseconds

        // Any later hand-off of any item prunes it; the pruned item then gets the equivalent KEEP.
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("other"))

        assertNull(ledger.entry("old"))
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("old"))
    }

    @Test
    fun `a recent finished entry survives pruning`() {
        ledger.jobStarted("recent")
        ledger.finishServing("recent", ledger.handOffsSeen("recent"), stopped = false)
        ledger.jobEnded("recent")
        now += 1.minutes.inWholeNanoseconds

        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("other"))

        assertEquals(ExistingWorkPolicy.REPLACE, ledger.policyForHandOff("recent"))
    }

    @Test
    fun `a counted hand-off keeps the newest request for the serving job, and an uncounted one keeps none`() {
        val first = UploadRequest(url = "https://upload.invalid/1", fields = emptyList())
        val second = UploadRequest(url = "https://upload.invalid/2", fields = emptyList())
        assertEquals(ExistingWorkPolicy.KEEP, ledger.policyForHandOff("a", first))
        assertNull(ledger.latestRequest("a"), "no job serves the item: nothing is counted or kept")

        ledger.jobStarted("a")
        ledger.policyForHandOff("a", first)
        ledger.policyForHandOff("a", second)

        assertEquals(second, ledger.latestRequest("a"))
        assertEquals(2L, ledger.handOffsSeen("a"))
    }
}
