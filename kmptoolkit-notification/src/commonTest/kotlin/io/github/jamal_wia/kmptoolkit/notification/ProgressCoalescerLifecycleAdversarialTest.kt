package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TestTimeSource

/**
 * A frame that lost the race to a newer one and gave up (the notifier answers `Coalesced` without
 * ever calling [ProgressCoalescer.rollback]) must not come back as a baseline when the newer frame
 * then fails: nothing of it ever reached the tray.
 */
class ProgressCoalescerLifecycleAdversarialTest {

    private fun coalescer(): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = TestTimeSource())

    private fun ProgressCoalescer.prepareProgress(percent: Int): ProgressCoalescer.PostTicket =
        requireNotNull(prepare("a", NotificationProgress.Determinate(percent))) { "$percent% was suppressed" }

    @Test
    fun `an older frame that gave up as overtaken is not a baseline once the newer frame fails`() {
        val coalescer: ProgressCoalescer = coalescer()
        val older: ProgressCoalescer.PostTicket = coalescer.prepareProgress(10)
        val newer: ProgressCoalescer.PostTicket = coalescer.prepareProgress(50)

        // The older frame finished building first, saw it was overtaken and returned without a rollback.
        assertFalse(coalescer.isLive(older))
        coalescer.rollback(newer)

        assertFalse(
            coalescer.wouldSuppress("a", NotificationProgress.Determinate(10)),
            "nothing was shown, yet a retry of 10% is redundant against the frame that gave up",
        )
        assertEquals(0, coalescer.postedCountForTest(), "a baseline for a frame that was never shown")
    }

    @Test
    fun `an older frame that gave up as overtaken does not shadow the shown frame once the newer one fails`() {
        val coalescer: ProgressCoalescer = coalescer()
        val shown: ProgressCoalescer.PostTicket = coalescer.prepareProgress(10)
        coalescer.complete(shown)
        val older: ProgressCoalescer.PostTicket = coalescer.prepareProgress(50)
        val newer: ProgressCoalescer.PostTicket = coalescer.prepareProgress(80)
        assertFalse(coalescer.isLive(older))

        coalescer.rollback(newer)

        // The tray still shows 10%. A retry of 50% must be posted; a repeat of 10% is redundant.
        assertFalse(
            coalescer.wouldSuppress("a", NotificationProgress.Determinate(50)),
            "the tray shows 10%, yet 50% is judged redundant against a frame that gave up",
        )
        assertTrue(coalescer.wouldSuppress("a", NotificationProgress.Determinate(10)))
    }

    /**
     * Model check: random interleavings of the notifier's protocol (prepare, then later either
     * complete-if-live, drop-if-overtaken, or rollback; cancels in between). After everything is
     * settled, a determinate update may be redundant only if the tray shows a frame in its bucket.
     */
    @Test
    fun `no interleaving of finishing and failing and cancelling leaves a baseline the tray does not show`() {
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val coalescer: ProgressCoalescer = coalescer()
            var trayBucket: Int? = null
            val open: MutableList<Pair<ProgressCoalescer.PostTicket, Int?>> = mutableListOf()
            val log: StringBuilder = StringBuilder()
            val steps: Int = 4 + random.nextInt(8)
            repeat(steps) {
                when (random.nextInt(5)) {
                    0, 1 -> {
                        val percent: Int = random.nextInt(0, 101)
                        val progress: NotificationProgress? =
                            if (random.nextInt(8) == 0) null else NotificationProgress.Determinate(percent)
                        val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare("a", progress)
                        log.append("prepare($percent${if (progress == null) ",terminal" else ""})=${ticket != null}; ")
                        if (ticket != null) open += ticket to (if (progress is NotificationProgress.Determinate && percent < 100) (percent / 10) * 10 else null)
                    }

                    2, 3 -> if (open.isNotEmpty()) {
                        val (ticket, bucket) = open.removeAt(random.nextInt(open.size))
                        if (random.nextInt(3) == 0) {
                            coalescer.rollback(ticket)
                            log.append("fail; ")
                        } else if (coalescer.isLive(ticket)) {
                            coalescer.complete(ticket)
                            trayBucket = bucket
                            log.append("show($bucket); ")
                        } else {
                            log.append("gaveUp; ")
                        }
                    }

                    else -> {
                        coalescer.forget("a")
                        trayBucket = null
                        open.clear()
                        log.append("cancel; ")
                    }
                }
            }
            // Settle whatever is still open, in random order, each one showing or failing at random.
            while (open.isNotEmpty()) {
                val (ticket, bucket) = open.removeAt(random.nextInt(open.size))
                if (random.nextBoolean()) {
                    coalescer.rollback(ticket)
                    log.append("fail; ")
                } else if (coalescer.isLive(ticket)) {
                    coalescer.complete(ticket)
                    trayBucket = bucket
                    log.append("show($bucket); ")
                } else {
                    log.append("gaveUp; ")
                }
            }
            for (probe in 0..100 step 10) {
                val suppressed: Boolean = coalescer.wouldSuppress("a", NotificationProgress.Determinate(probe))
                if (probe < 100 && suppressed) {
                    assertEquals(
                        probe,
                        trayBucket,
                        "seed $seed: $probe% judged redundant but the tray shows bucket $trayBucket. Steps: $log",
                    )
                }
            }
        }
    }

    private companion object {
        const val SEEDS: Int = 3000
    }
}
