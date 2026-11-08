package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TestTimeSource

/**
 * Randomised interleavings of the notifier protocol checked against a model of what the tray shows.
 */
class ProgressCoalescerModelAdversarialTest {

    private fun coalescer(): ProgressCoalescer =
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = TestTimeSource())

    /**
     * Model check: random interleavings of the notifier's protocol (prepare, then later either
     * complete-if-live, rollback when overtaken, or rollback on failure; cancels in between). After everything is
     * settled, a determinate update may be redundant only if the tray shows a frame in its bucket.
     */
    @Test
    fun `no interleaving of finishing and failing and abandoning and cancelling leaves a baseline the tray does not show`() {
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
                            coalescer.rollback(ticket); log.append("gaveUp+rollback; ")
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
                    coalescer.rollback(ticket); log.append("gaveUp+rollback; ")
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
        const val SEEDS: Int = 5000
    }
}
