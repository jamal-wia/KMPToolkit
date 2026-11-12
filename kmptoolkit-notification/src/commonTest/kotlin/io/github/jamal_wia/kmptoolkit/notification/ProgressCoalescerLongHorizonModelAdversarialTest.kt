package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Long-horizon property test of the progress coalescer against a reference model, in virtual time.
 *
 * Every seed drives one coalescer through tens of thousands of sequential operations (an update that is then shown
 * or refused by the platform, a forget, a clear) while the virtual monotonic clock jumps by anything from a
 * millisecond to a day, so a run spans weeks. The model re-implements only the documented behaviour: a rate limit
 * of one frame per minimum interval, a bucket and content comparison after it, a refused frame restoring the frame the
 * tray still shows, and an abandoned run being forgotten once it has been quiet for the stale horizon. After every
 * operation the verdict, the dry-run verdict and the number of remembered runs must equal the model's.
 */
class ProgressCoalescerLongHorizonModelAdversarialTest {

    private class MPost(val bucket: Int, val content: Int?, val at: Long, var lastSeen: Long)

    private class Model(private val bucketPercent: Int, private val minInterval: Duration) {
        val posted: MutableMap<String, MPost> = HashMap()
        val lastShown: MutableMap<String, MPost> = HashMap()
        private val staleAfter: Duration = maxOf(minInterval * 120, 10.minutes)
        private var lastPrune: Long = 0L

        fun wouldSuppress(id: String, progress: NotificationProgress?, content: Int?, now: Long): Boolean =
            decide(id, progress, content, now) == null

        private fun prune(now: Long) {
            val spacing: Long = (staleAfter / 10).inWholeNanoseconds
            if (now - lastPrune < spacing) return
            lastPrune = now
            val limit: Long = staleAfter.inWholeNanoseconds
            val stale: List<String> = posted.filterValues { now - it.lastSeen > limit }.keys.toList()
            stale.forEach { key: String ->
                posted.remove(key)
                lastShown.remove(key)
            }
            lastShown.entries.removeAll { posted[it.key] == null && now - it.value.at > limit }
        }

        /** Returns the bucket to record, [RESET] or null for a suppressed update. */
        private fun decide(id: String, progress: NotificationProgress?, content: Int?, now: Long): Int? {
            if (progress !is NotificationProgress.Determinate) return RESET
            val percent: Int = progress.percent.coerceIn(0, 100)
            if (percent == 100) return RESET
            val bucket: Int = (percent / bucketPercent) * bucketPercent
            val previous: MPost = posted[id] ?: return bucket
            if (now - previous.at < minInterval.inWholeNanoseconds) return null
            return if (previous.bucket == bucket && previous.content == content) null else bucket
        }

        /** Returns true when the real coalescer must have issued a ticket. */
        fun update(id: String, progress: NotificationProgress?, content: Int?, now: Long, shown: Boolean): Boolean {
            prune(now)
            val verdict: Int? = decide(id, progress, content, now)
            if (verdict == null) {
                posted[id]?.lastSeen = now
                return false
            }
            if (verdict == RESET) {
                posted.remove(id)
                if (shown) lastShown.remove(id)
                return true
            }
            val recorded = MPost(verdict, content, now, now)
            posted[id] = recorded
            if (shown) {
                lastShown[id] = recorded
            } else {
                val previous: MPost? = lastShown[id]
                if (previous == null) posted.remove(id) else posted[id] = previous
            }
            return true
        }

        fun forget(id: String) {
            posted.remove(id)
            lastShown.remove(id)
        }

        fun clear() {
            posted.clear()
            lastShown.clear()
        }

        companion object {
            const val RESET: Int = -1
        }
    }

    private fun jump(random: Random): Duration = when (random.nextInt(1000)) {
        in 0..599 -> random.nextInt(0, 1500).milliseconds
        in 600..899 -> random.nextInt(1, 4000).milliseconds
        in 900..969 -> random.nextInt(1, 40).seconds
        in 970..989 -> random.nextInt(1, 30).minutes
        in 990..996 -> random.nextInt(1, 14).hours
        else -> random.nextInt(1, 25).hours + random.nextInt(0, 2).days
    }

    private fun runSeed(seed: Int, bucketPercent: Int, minInterval: Duration, steps: Int) {
        val random = Random(seed)
        val clock = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = bucketPercent, minInterval = minInterval, timeSource = clock)
        val model = Model(bucketPercent, minInterval)
        val ids: List<String> = listOf("a", "b", "Aa", "BB", "c", "")
        var now: Long = 0L
        val log: ArrayDeque<String> = ArrayDeque()
        repeat(steps) { step: Int ->
            val step0: Duration = jump(random)
            clock += step0
            now += step0.inWholeNanoseconds
            val id: String = ids[random.nextInt(ids.size)]
            val kind: Int = random.nextInt(1000)
            val line: String
            when {
                kind < 940 -> {
                    val progress: NotificationProgress? = when (random.nextInt(40)) {
                        0 -> null
                        1 -> NotificationProgress.Indeterminate
                        2 -> NotificationProgress.Determinate(100 + random.nextInt(50))
                        3 -> NotificationProgress.Determinate(-random.nextInt(50))
                        else -> NotificationProgress.Determinate(random.nextInt(0, 101))
                    }
                    val content: Int? = if (random.nextInt(3) == 0) null else random.nextInt(2)
                    val shown: Boolean = random.nextInt(100) >= 15
                    line = "t=$now update($id,$progress,$content,shown=$shown)"
                    val dry: Boolean = coalescer.wouldSuppress(id, progress, content)
                    val modelDry: Boolean = model.wouldSuppress(id, progress, content, now)
                    val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare(id, progress, content)
                    val expectTicket: Boolean = model.update(id, progress, content, now, shown)
                    log.addLast(line); if (log.size > 12) log.removeFirst()
                    assertEquals(expectTicket, ticket != null, "seed $seed step $step: ticket differs. ${log.joinToString(" | ")}")
                    assertEquals(modelDry, dry, "seed $seed step $step: dry run differs from the model. ${log.joinToString(" | ")}")
                    if (ticket != null) {
                        if (shown) coalescer.complete(ticket) else coalescer.rollback(ticket)
                    }
                }
                kind < 995 -> {
                    coalescer.forget(id); model.forget(id)
                    log.addLast("t=$now forget($id)"); if (log.size > 12) log.removeFirst()
                }
                else -> {
                    coalescer.clear(); model.clear()
                    log.addLast("t=$now clear"); if (log.size > 12) log.removeFirst()
                }
            }
            assertEquals(
                model.posted.size, coalescer.postedCountForTest(),
                "seed $seed step $step: remembered runs differ from the model. ${log.joinToString(" | ")}",
            )
            assertEquals(
                0, coalescer.claimCountForTest(), "seed $seed step $step: a claim leaked without any being taken",
            )
        }
        // Quiescence: after a quiet month everything is forgotten by the next update that is not itself recorded.
        clock += 30.days
        val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare("zzz", NotificationProgress.Determinate(100))
        assertNotNull(ticket)
        coalescer.complete(ticket)
        assertEquals(0, coalescer.postedCountForTest(), "seed $seed: runs survived a quiet month")
    }

    @Test
    fun `a long run of updates over weeks of virtual time matches the reference model with the default shape`() {
        repeat(3) { seed: Int -> runSeed(seed, bucketPercent = 5, minInterval = 1.seconds, steps = 15_000) }
    }

    @Test
    fun `a long run of updates over weeks of virtual time matches the reference model with a coarse bucket and a long interval`() {
        repeat(3) { seed: Int -> runSeed(1000 + seed, bucketPercent = 25, minInterval = 30.seconds, steps = 15_000) }
    }

    @Test
    fun `a long run of updates over weeks of virtual time matches the reference model with no interval and a fine bucket`() {
        repeat(2) { seed: Int -> runSeed(2000 + seed, bucketPercent = 1, minInterval = Duration.ZERO, steps = 15_000) }
    }
}
