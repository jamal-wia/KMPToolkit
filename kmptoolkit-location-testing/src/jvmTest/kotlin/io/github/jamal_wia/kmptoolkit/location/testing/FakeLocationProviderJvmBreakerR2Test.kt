package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.LocationReading
import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 2 of group loc-jvm-fakes (BREAKER-REVIEW.md, D-23), JVM only: memory retention of
 * the round-1 snapshot cell of [FakeLocationProvider]. A snapshot keeps the latest terminal reading
 * so a collection that conflation skipped can still end on it; that must not turn into a chain of
 * older snapshots or readings (a fake that a long test or a soak drives would grow without bound).
 *
 * Red if a `Snapshot` pointed to its predecessor (or to a previous snapshot through its terminal
 * field), if the fake kept a history of readings, or if a finished collection kept a reading alive.
 * Control: the latest reading is still reachable after the same GC passes (the check can see a
 * retained object).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeLocationProviderJvmBreakerR2Test {


    private fun clearedAfterGc(references: List<WeakReference<*>>): Boolean {
        repeat(50) {
            System.gc()
            if (references.all { it.get() == null }) return true
        }
        return references.all { it.get() == null }
    }

    /** Emits an available reading whose fix carries a fresh payload; only a weak reference escapes. */
    private fun emitTracked(provider: FakeLocationProvider, index: Int): WeakReference<TestFixDetails> {
        val payload = TestFixDetails("p$index")
        provider.emit(LocationReading.Available(fakeFix(0.0, 0.0, timestampEpochMillis = index.toLong(), platformDetails = payload)))
        return WeakReference(payload)
    }

    @Test
    fun `superseded readings are not retained by the snapshot cell, the terminal reading or a finished collection`() = runTest {
        val provider = FakeLocationProvider()
        val gate = CompletableDeferred<Unit>()
        val seen: MutableList<LocationReading> = mutableListOf()
        val job: Job = backgroundScope.launch {
            provider.observeLocation().collect {
                seen += it // only the seed and the terminal reading reach this collection (no payload)
                if (seen.size == 1) gate.await() // busy on the seed: a live collection that has not read anything since
            }
        }
        runCurrent()
        assertEquals(1, provider.activeObservers.value, "control: live")

        val superseded: MutableList<WeakReference<TestFixDetails>> = mutableListOf()
        superseded += emitTracked(provider, 1)
        provider.emit(permissionMissing)
        for (i in 2..20_000) {
            val ref: WeakReference<TestFixDetails> = emitTracked(provider, i)
            if (i % 1_000 == 0 || i < 10) superseded += ref
        }
        val latest: WeakReference<TestFixDetails> = emitTracked(provider, 20_001)

        assertTrue(clearedAfterGc(superseded), "readings superseded while a terminal reading is pending are retained: ${superseded.count { it.get() != null }} of ${superseded.size}")
        assertNotNull(latest.get(), "control: the latest reading stays reachable through the cell")

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(noFix, permissionMissing), seen, "control: the collection ended on the pending terminal reading")
        assertTrue(job.isCompleted)
        assertEquals(0, provider.activeObservers.value)

        provider.emit(serviceDisabled)
        assertTrue(clearedAfterGc(listOf(latest)), "a reading superseded after the collection finished is retained")
    }
}
