package io.github.jamal_wia.kmptoolkit.downloader

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * The process-wide commit locks under real threads: exclusion per unit, cancelled waiters and holders,
 * and the non-suspending probe that cancels use, never running beside a holder and never leaking an entry.
 */
class UnitCommitLocksStormRound3Test {

    @Test
    fun `holders of one unit never overlap and a probe never runs beside a holder, with a third of the callers cancelled`() {
        val base = "/storm/round3"
        val keys: List<String> = (0 until 4).map { "unit-$it" }
        val inside: Map<String, AtomicInteger> = keys.associateWith { AtomicInteger() }
        val overlaps = AtomicInteger()
        val probeBesideHolder = AtomicInteger()
        val probesRan = AtomicInteger()
        repeat(60) { round: Int ->
            val stop = AtomicBoolean(false)
            val probers: List<Thread> = (0 until 3).map { index: Int ->
                thread(name = "probe-$index") {
                    var i = 0
                    while (!stop.get()) {
                        val key: String = keys[(i++ + index) % keys.size]
                        val ran: Boolean = UnitCommitLocks.tryWithUnitLock(base, key) {
                            probesRan.incrementAndGet()
                            if (inside.getValue(key).get() != 0) probeBesideHolder.incrementAndGet()
                        }
                        if (!ran) Thread.onSpinWait()
                    }
                }
            }
            runBlocking(Dispatchers.Default) {
                withTimeout(30_000) {
                    val jobs = (0 until 64).map { index: Int ->
                        val key: String = keys[index % keys.size]
                        launch(start = CoroutineStart.DEFAULT) {
                            UnitCommitLocks.withUnitLock(base, key) {
                                if (inside.getValue(key).incrementAndGet() != 1) overlaps.incrementAndGet()
                                try {
                                    yield()
                                } finally {
                                    inside.getValue(key).decrementAndGet()
                                }
                            }
                        }
                    }
                    jobs.forEachIndexed { index: Int, job ->
                        if (index % 3 == 0) launch {
                            delay((index % 5).toLong())
                            job.cancel()
                        }
                    }
                    jobs.joinAll()
                }
            }
            stop.set(true)
            probers.forEach { it.join(10_000) }
            assertTrue(probers.none { it.isAlive }, "round $round: a prober is blocked")
            for (key: String in keys) {
                // Holders are all gone: a cancelled one released its slot, so the probe runs for every key.
                var ran = false
                repeat(1_000) { if (!ran) ran = UnitCommitLocks.tryWithUnitLock(base, key) { } }
                assertTrue(ran, "round $round: the entry of $key leaked, so cancels can never clean it")
                assertEquals(0, inside.getValue(key).get(), "round $round: $key")
            }
        }
        assertEquals(0, overlaps.get(), "two holders of one unit ran together")
        assertEquals(0, probeBesideHolder.get(), "a probe ran while a commit held the unit")
        assertTrue(probesRan.get() > 0, "the probe never got the registry: the race is not exercised")
    }
}
