package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Many callers: the coalescer documents itself as thread-safe, because progress is posted from
 * whichever thread finished a chunk of work. Sixteen workers hammer one shared id and one private
 * id each, with a fixed seed per worker, and the invariants that must hold whatever the
 * interleaving are asserted afterwards. Leak: ids abandoned mid-progress while the clock moves must
 * not accumulate.
 */
class ProgressCoalescerAdversarialTest {

    @Test
    fun `sixteen workers on a shared id and private ids never lose a terminal frame or disagree with wouldSuppress`() =
        runTest(timeout = 60.seconds) {
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO, timeSource = TestTimeSource())
            val start = CompletableDeferred<Unit>()

            val problems: List<List<String>> = withContext(Dispatchers.Default) {
                withTimeout(40.seconds) {
                    val workers = List(WORKERS) { worker: Int ->
                        async {
                            start.await()
                            runWorker(coalescer, worker)
                        }
                    }
                    start.complete(Unit)
                    workers.awaitAll()
                }
            }

            val flat: List<String> = problems.flatten()
            assertTrue(flat.isEmpty(), "invariants broken (first 5 of ${flat.size}): ${flat.take(5)}")
            assertTrue(coalescer.postedCountForTest() <= WORKERS + 1, "posted map grew past the id count: ${coalescer.postedCountForTest()}")
            assertTrue(coalescer.latestCountForTest() <= WORKERS + 1, "latest map grew past the id count: ${coalescer.latestCountForTest()}")

            coalescer.forget(SHARED)
            repeat(WORKERS) { worker: Int -> coalescer.forget("own-$worker") }
            assertEquals(0, coalescer.postedCountForTest(), "forgetting every id must empty the baseline map")
            assertEquals(0, coalescer.latestCountForTest(), "forgetting every id must empty the newest-serial map")
        }

    @Test
    fun `at most one ticket per id is live after concurrent prepares and the newest one is it`() =
        runTest(timeout = 60.seconds) {
            val coalescer = ProgressCoalescer(bucketPercent = 1, minInterval = Duration.ZERO, timeSource = TestTimeSource())
            val start = CompletableDeferred<Unit>()

            val tickets: List<ProgressCoalescer.PostTicket> = withContext(Dispatchers.Default) {
                withTimeout(40.seconds) {
                    val workers = List(WORKERS) { worker: Int ->
                        async {
                            start.await()
                            val random = Random(SEED + worker)
                            buildList {
                                repeat(ITERATIONS) {
                                    val progress: NotificationProgress? = when (random.nextInt(4)) {
                                        0 -> null
                                        else -> NotificationProgress.Determinate(random.nextInt(0, 100))
                                    }
                                    coalescer.prepare(SHARED, progress, content = random.nextInt(2))?.let { add(it) }
                                }
                            }
                        }
                    }
                    start.complete(Unit)
                    workers.awaitAll().flatten()
                }
            }

            val live: List<ProgressCoalescer.PostTicket> = tickets.filter { ticket: ProgressCoalescer.PostTicket -> coalescer.isLive(ticket) }
            assertTrue(live.size <= 1, "${live.size} tickets of one id are live at once")
            val newest: ProgressCoalescer.PostTicket = tickets.maxByOrNull { ticket: ProgressCoalescer.PostTicket -> ticket.serial }
                ?: error("no ticket was issued")
            assertTrue(coalescer.isLive(newest), "the newest ticket (serial ${newest.serial}) is not the live one")
        }

    @Test
    fun `ids abandoned mid-progress while the clock moves never pile up`() {
        val time = TestTimeSource()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = 500.milliseconds, timeSource = time)
        var maxPosted = 0
        var maxLatest = 0

        repeat(ABANDONED_IDS) { index: Int ->
            // Mid-progress and never finished: no complete, no rollback, no terminal frame, no cancel.
            coalescer.prepare("run-$index", NotificationProgress.Determinate(10 + index % 80))
            time += 1.seconds
            maxPosted = maxOf(maxPosted, coalescer.postedCountForTest())
            maxLatest = maxOf(maxLatest, coalescer.latestCountForTest())
        }

        // Stale after max(120 * 500 ms, 10 min) = 600 s, scanned once per 60 s: about 660 ids live.
        assertTrue(maxPosted < 1_000, "baseline map peaked at $maxPosted for $ABANDONED_IDS abandoned ids")
        assertTrue(maxLatest < 1_000, "newest-serial map peaked at $maxLatest for $ABANDONED_IDS abandoned ids")
    }

    private suspend fun runWorker(coalescer: ProgressCoalescer, worker: Int): List<String> = coroutineScope {
        val problems: MutableList<String> = mutableListOf()
        val random = Random(SEED + worker)
        val own = "own-$worker"
        repeat(ITERATIONS) { iteration: Int ->
            val where = "worker=$worker iteration=$iteration seed=${SEED + worker}"
            // The shared id: a terminal frame must always get a ticket, whatever the others do.
            val terminal: NotificationProgress? =
                if (random.nextBoolean()) null else NotificationProgress.Determinate(100)
            val terminalTicket: ProgressCoalescer.PostTicket? = coalescer.prepare(SHARED, terminal)
            if (terminalTicket == null) problems += "terminal frame suppressed on the shared id ($where)"
            when (random.nextInt(3)) {
                0 -> terminalTicket?.let { coalescer.complete(it) }
                1 -> terminalTicket?.let { coalescer.rollback(it) }
                else -> Unit
            }
            val shared: NotificationProgress = NotificationProgress.Determinate(random.nextInt(0, 100))
            coalescer.prepare(SHARED, shared)?.let { ticket: ProgressCoalescer.PostTicket ->
                when (random.nextInt(3)) {
                    0 -> coalescer.complete(ticket)
                    1 -> coalescer.rollback(ticket)
                    else -> Unit
                }
            }
            if (random.nextInt(8) == 0) coalescer.forget(SHARED)

            // The private id: nobody else touches it, so the question and the decision must agree.
            val progress: NotificationProgress? = when (random.nextInt(5)) {
                0 -> null
                else -> NotificationProgress.Determinate(random.nextInt(0, 101))
            }
            val predicted: Boolean = coalescer.wouldSuppress(own, progress)
            val ticket: ProgressCoalescer.PostTicket? = coalescer.prepare(own, progress)
            if (predicted != (ticket == null)) {
                problems += "wouldSuppress=$predicted but prepare ${if (ticket == null) "suppressed" else "posted"} ($where, progress=$progress)"
            }
            if (ticket != null && !coalescer.isLive(ticket)) problems += "a fresh ticket of a private id is not live ($where)"
            when (random.nextInt(3)) {
                0 -> ticket?.let { coalescer.complete(it) }
                1 -> ticket?.let { coalescer.rollback(it) }
                else -> Unit
            }
        }
        problems
    }

    private companion object {
        const val WORKERS: Int = 16
        const val ITERATIONS: Int = 400
        const val SEED: Int = 20_261_107
        const val ABANDONED_IDS: Int = 10_000
        const val SHARED: String = "shared"
    }
}
