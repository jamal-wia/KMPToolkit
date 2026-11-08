package io.github.jamal_wia.kmptoolkit.video.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The pending-seek slot of the AVPlayer engine is written by seeking callers on any thread and
 * cleared by AVFoundation's completion blocks on whichever thread they run on. Under contention a
 * completion may only ever clear the seek it belongs to.
 */
class PendingSeekSlotContentionAdversarialTest {

    private fun joinAll(what: String, workers: List<Thread>) {
        workers.forEach { worker: Thread ->
            worker.join(TimeUnit.SECONDS.toMillis(20))
            if (worker.isAlive) fail("$what: ${worker.name} did not finish")
        }
    }

    @Test
    fun `when every seek has completed, in any order and from any thread, nothing is pending`() {
        repeat(ROUNDS) { round: Int ->
            val slot = PendingSeekSlot()
            val go = CountDownLatch(1)
            val workers: List<Thread> = List(THREADS) { id: Int ->
                thread(name = "seek-$round-$id") {
                    go.await()
                    val mine: MutableList<PendingSeekSlot.Token> = mutableListOf()
                    repeat(STEPS) { step: Int ->
                        mine += slot.begin((id * STEPS + step).toLong())
                        // Complete an older seek of this thread while newer ones are in flight.
                        if (step % 3 == 2) slot.complete(mine.removeAt(0))
                    }
                    mine.forEach { slot.complete(it) }
                }
            }
            go.countDown()
            joinAll("round $round", workers)
            assertNull(slot.targetMs, "round $round: a completed seek is still pending")
        }
    }

    @Test
    fun `a completion that arrives after a newer seek began never clears it, whatever the targets`() {
        repeat(ROUNDS * 20) { round: Int ->
            val slot = PendingSeekSlot()
            val older: PendingSeekSlot.Token = slot.begin(1_000L)
            val newerBegun = CountDownLatch(1)
            val newer: AtomicTokenBox = AtomicTokenBox()
            val seeker = thread(name = "newer-$round") {
                // Same target as the older seek: only identity tells them apart.
                newer.token = slot.begin(if (round % 2 == 0) 1_000L else 2_000L)
                newerBegun.countDown()
            }
            // The completion races the newer begin: before it, it clears the older seek; after it, it must not clear the newer.
            slot.complete(older)
            if (!newerBegun.await(5, TimeUnit.SECONDS)) fail("round $round: the newer seek did not begin")
            seeker.join(5_000L)
            assertTrue(slot.targetMs != null, "round $round: the older seek's completion cleared the newer seek")
            slot.complete(requireNotNull(newer.token))
            assertNull(slot.targetMs)
            assertEquals(null, slot.targetMs)
        }
    }

    private class AtomicTokenBox {
        @Volatile
        var token: PendingSeekSlot.Token? = null
    }

    private companion object {
        const val ROUNDS: Int = 100
        const val THREADS: Int = 6
        const val STEPS: Int = 200
    }
}
