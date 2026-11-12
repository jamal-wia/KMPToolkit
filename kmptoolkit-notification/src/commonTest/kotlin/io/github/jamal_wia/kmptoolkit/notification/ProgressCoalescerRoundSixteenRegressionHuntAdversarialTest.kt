package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Round 16 (regression hunt on the round 15 fix of the abandoned-run horizon). 03-guide "Abandoned runs": an id
 * is forgotten only "once the id has been quiet for far longer than" the horizon; a run that keeps sending
 * frames is not quiet, whatever else happens to it. Every test here uses the internal ticket protocol the two
 * notifiers use (prepare, complete or rollback) on a test clock.
 */
class ProgressCoalescerRoundSixteenRegressionHuntAdversarialTest {

    private val time = TestTimeSource()

    private fun coalescer(minInterval: Duration = 500.milliseconds): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = minInterval, timeSource = time)

    private fun ProgressCoalescer.post(id: String, percent: Int?, content: Any? = null): Boolean {
        val progress: NotificationProgress? = percent?.let { NotificationProgress.Determinate(it) }
        val ticket: ProgressCoalescer.PostTicket = prepare(id, progress, content) ?: return false
        complete(ticket, acknowledged = true)
        return true
    }

    @Test
    fun `the same refusal right after a short run keeps the baseline`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertTrue(coalescer.post("a", 30))
        time += 5.seconds
        val refused: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("a", NotificationProgress.Determinate(45)))
        coalescer.rollback(refused)
        assertNull(coalescer.prepare("a", NotificationProgress.Determinate(31)))
    }

    @Test
    fun `a frame acknowledged by the platform sheds the baseline it kept for a rollback`() {
        // ProgressCoalescer.complete KDoc: with acknowledged = true "nothing can undo it and the record sheds the
        // baseline it kept for a rollback"; with false it keeps it until acknowledge or rollback settles it.
        fun lateFrameIsRedundantAfterRollback(acknowledged: Boolean): Boolean {
            val coalescer: ProgressCoalescer = coalescer()
            assertTrue(coalescer.post("a", 20))
            time += 1.seconds
            val second: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("a", NotificationProgress.Determinate(40)))
            coalescer.complete(second, acknowledged = acknowledged)
            coalescer.rollback(second)
            time += 1.seconds
            return coalescer.prepare("a", NotificationProgress.Determinate(21)) == null
        }
        assertTrue(lateFrameIsRedundantAfterRollback(acknowledged = false), "kept baseline: the 20 percent frame is the tray state again")
        assertFalse(lateFrameIsRedundantAfterRollback(acknowledged = true), "shed baseline: nothing to fall back to")
    }

    @Test
    fun `frames sparser than the one minute scan spacing but inside the horizon keep a run alive for three hours`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertTrue(coalescer.post("a", 31))
        val posted: MutableList<Int> = mutableListOf()
        for (step in 1..20) {
            time += 9.minutes
            if (coalescer.post("a", 32)) posted += step
        }
        assertEquals(emptyList(), posted, "a frame every nine minutes is not quiet for ten, so no in-bucket frame may post")
    }

    @Test
    fun `a clock that steps backwards neither forgets a live run nor throws and recovers when it catches up`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertTrue(coalescer.post("a", 31))
        time += 30.minutes
        // The clock went back a long way: the run sends a frame again.
        time += (-45).minutes
        assertFalse(coalescer.post("a", 32), "in-bucket frame after the clock stepped back")
        assertFalse(coalescer.post("a", 33))
        time += 1.minutes
        assertFalse(coalescer.post("a", 34), "the run was seen a minute ago on the (odd) clock; not forgotten")
    }

    @Test
    fun `thousands of ids that went quiet are all forgotten by one later post of another id`() {
        val coalescer: ProgressCoalescer = coalescer()
        repeat(5000) { index: Int -> assertTrue(coalescer.post("id-$index", 31)) }
        assertEquals(5000, coalescer.postedCountForTest())
        time += 25.minutes

        assertTrue(coalescer.post("late", 31))

        assertEquals(1, coalescer.postedCountForTest(), "every quiet id must be dropped, only the new one remains")
        assertEquals(1, coalescer.latestCountForTest(), "only the newest ticket of the one live id remains")
    }

    @Test
    fun `thousands of ids that are all still live are all kept`() {
        val coalescer: ProgressCoalescer = coalescer()
        repeat(2000) { index: Int -> assertTrue(coalescer.post("id-$index", 31)) }
        repeat(30) {
            time += 5.minutes
            repeat(2000) { index: Int -> assertFalse(coalescer.post("id-$index", 32), "id-$index at step $it") }
        }
        assertEquals(2000, coalescer.postedCountForTest())
    }

    @Test
    fun `an id reused after forget starts a fresh run and an old ticket completing late does not resurrect the old baseline`() {
        val coalescer: ProgressCoalescer = coalescer()
        val old: ProgressCoalescer.PostTicket = assertNotNull(coalescer.prepare("a", NotificationProgress.Determinate(31)))
        coalescer.forget("a")
        // A new run of the same id.
        assertTrue(coalescer.post("a", 80))
        // The old ticket's caller wakes up after the new run: it is no longer live and completing it must not touch the new run.
        assertFalse(coalescer.isLive(old))
        coalescer.complete(old, acknowledged = true)
        assertNull(coalescer.prepare("a", NotificationProgress.Determinate(81)), "81 is in the 80 bucket of the new run")
        assertEquals(1, coalescer.postedCountForTest())
    }

    @Test
    fun `the id of a finished run at 100 percent starts a run that is not measured against the old one`() {
        val coalescer: ProgressCoalescer = coalescer()
        assertTrue(coalescer.post("a", 55))
        assertTrue(coalescer.post("a", 100))
        assertEquals(0, coalescer.postedCountForTest())
        time += 1.seconds
        assertTrue(coalescer.post("a", 51), "a new run's first frame always posts, even in the bucket of the old run")
        assertFalse(coalescer.post("a", 52))
    }

    @Test
    fun `random frames cancels and clock jumps agree with the documented model`() {
        for (seed in 1..60) {
            val random = Random(seed)
            val coalescer: ProgressCoalescer = coalescer(500.milliseconds)
            val staleAfter: Duration = 10.minutes
            // id -> (bucket, content, last posted, last seen)
            class Known(val bucket: Int, val content: Int, val postedAt: Duration, var seenAt: Duration)
            val model: MutableMap<String, Known> = mutableMapOf()
            var now: Duration = Duration.ZERO
            repeat(400) { step: Int ->
                val jump: Duration = when (random.nextInt(10)) {
                    0 -> 25.minutes
                    1 -> 9.minutes
                    2 -> 2.minutes
                    3 -> 30.seconds
                    else -> random.nextInt(1, 1500).milliseconds
                }
                time += jump
                now += jump
                val id: String = "id-${random.nextInt(4)}"
                when (random.nextInt(12)) {
                    0 -> { coalescer.forget(id); model.remove(id) }
                    1 -> { coalescer.clear(); model.clear() }
                    2 -> {
                        val posted: Boolean = coalescer.post(id, null)
                        assertTrue(posted, "seed $seed step $step: a terminal frame always posts")
                        model.remove(id)
                    }
                    3 -> {
                        assertTrue(coalescer.post(id, 100), "seed $seed step $step: 100 always posts")
                        model.remove(id)
                    }
                    else -> {
                        val percent: Int = random.nextInt(0, 100)
                        val content: Int = random.nextInt(2)
                        val known: Known? = model[id]
                        val quiet: Duration? = known?.let { now - it.seenAt }
                        val posted: Boolean = coalescer.post(id, percent, content)
                        val bucket: Int = percent / 10 * 10
                        when {
                            known == null || quiet!! > staleAfter * 1.2 -> {
                                assertTrue(posted, "seed $seed step $step: no baseline (or a forgotten one) so the frame posts")
                                model[id] = Known(bucket, content, now, now)
                            }
                            quiet <= staleAfter -> {
                                val expected: Boolean = now - known.postedAt >= 500.milliseconds &&
                                    (known.bucket != bucket || known.content != content)
                                assertEquals(expected, posted, "seed $seed step $step id $id quiet $quiet")
                                if (posted) model[id] = Known(bucket, content, now, now) else known.seenAt = now
                            }
                            else -> {
                                // Inside the tolerance band: either answer is allowed, resynchronise the model.
                                if (posted) model[id] = Known(bucket, content, now, now) else known.seenAt = now
                            }
                        }
                    }
                }
            }
        }
    }
}
