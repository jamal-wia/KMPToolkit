package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The age policy and the stream-lifecycle rule shared by the platform providers (PLAN D8, D10, D11,
 * section 4.2). The age rules are pure; the lifecycle operator is checked for completion, for the
 * release of the upstream registration (a counter with a control assertion that it existed first),
 * and for re-collection after completion. Nothing here waits on time.
 */
class ReadingPolicyTest {

    private fun fix(latitude: Double): LocationFix = LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L)

    private fun reading(latitude: Double): LocationReading = LocationReading.Available(fix(latitude))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    // region acceptsCachedFix (the cache decision)

    @Test
    fun `zero max age forces a fresh fix and accepts no cache whatever its age`() {
        assertFalse(acceptsCachedFix(Duration.ZERO, Duration.ZERO))
        assertFalse(acceptsCachedFix(1.milliseconds, Duration.ZERO))
        assertFalse(acceptsCachedFix(-1.seconds, Duration.ZERO))
    }

    @Test
    fun `infinite max age accepts any cache`() {
        assertTrue(acceptsCachedFix(Duration.ZERO, Duration.INFINITE))
        assertTrue(acceptsCachedFix(365.seconds * 100_000, Duration.INFINITE))
        assertTrue(acceptsCachedFix(Duration.INFINITE, Duration.INFINITE))
    }

    @Test
    fun `a cache is acceptable up to and including the max age`() {
        val maxAge: Duration = 30.seconds

        assertTrue(acceptsCachedFix(Duration.ZERO, maxAge))
        assertTrue(acceptsCachedFix(29_999.milliseconds, maxAge))
        assertTrue(acceptsCachedFix(30.seconds, maxAge))
        assertFalse(acceptsCachedFix(30_001.milliseconds, maxAge))
    }

    @Test
    fun `a negative age from a clock that stepped back counts as zero`() {
        assertTrue(acceptsCachedFix(-5.seconds, 1.seconds))
    }

    // endregion

    // region isFreshDelivery (the delivery decision)

    @Test
    fun `zero max age accepts a fix taken after the request began`() {
        // A delivered fix always has an age above zero (the delivery latency); it was taken after the request,
        // so it is fresh. acceptsCachedFix would reject it, which is why the delivery check is a separate rule.
        assertTrue(isFreshDelivery(50.milliseconds, Duration.ZERO, 2.seconds))
        assertTrue(isFreshDelivery(Duration.ZERO, Duration.ZERO, Duration.ZERO))
        assertFalse(acceptsCachedFix(50.milliseconds, Duration.ZERO), "the cache rule would have discarded this fresh fix")
    }

    @Test
    fun `zero max age rejects a fix older than the request`() {
        assertFalse(isFreshDelivery(20.seconds, Duration.ZERO, 2.seconds))
        assertFalse(isFreshDelivery(2_001.milliseconds, Duration.ZERO, 2.seconds))
        assertTrue(isFreshDelivery(2.seconds, Duration.ZERO, 2.seconds), "a fix taken exactly when the request began is fresh")
    }

    @Test
    fun `a positive max age also accepts an older fix up to the max age`() {
        assertTrue(isFreshDelivery(20.seconds, 30.seconds, 2.seconds))
        assertTrue(isFreshDelivery(30.seconds, 30.seconds, 2.seconds))
        assertFalse(isFreshDelivery(30_001.milliseconds, 30.seconds, 2.seconds))
        // The request has been running for a minute, so a 45 s old fix was taken during it.
        assertTrue(isFreshDelivery(45.seconds, 30.seconds, 60.seconds))
    }

    @Test
    fun `infinite max age accepts any delivery`() {
        assertTrue(isFreshDelivery(Duration.INFINITE, Duration.INFINITE, Duration.ZERO))
        assertTrue(isFreshDelivery(365.seconds * 100_000, Duration.INFINITE, 1.seconds))
    }

    @Test
    fun `a negative age or a negative elapsed time counts as zero`() {
        assertTrue(isFreshDelivery(-5.seconds, Duration.ZERO, Duration.ZERO))
        assertFalse(isFreshDelivery(1.seconds, Duration.ZERO, -5.seconds))
        assertTrue(isFreshDelivery(1.seconds, 1.seconds, -5.seconds))
    }

    // endregion

    // region freshestWithin and seedReading

    @Test
    fun `the freshest cache within the max age wins and an older one is ignored`() {
        val old = AgedFix(fix(1.0), 20.seconds)
        val young = AgedFix(fix(2.0), 3.seconds)
        val tooOld = AgedFix(fix(3.0), 90.seconds)

        assertEquals(fix(2.0), freshestWithin(listOf(old, tooOld, young), 30.seconds))
        assertEquals(fix(1.0), freshestWithin(listOf(old, tooOld), 30.seconds))
    }

    @Test
    fun `no cache within the max age and no candidates both give null`() {
        assertNull(freshestWithin(emptyList(), 30.seconds))
        assertNull(freshestWithin(listOf(AgedFix(fix(1.0), 31.seconds)), 30.seconds))
        assertNull(freshestWithin(listOf(AgedFix(fix(1.0), Duration.ZERO)), Duration.ZERO))
    }

    @Test
    fun `equal ages keep the first candidate`() {
        val first = AgedFix(fix(1.0), 5.seconds)
        val second = AgedFix(fix(2.0), 5.seconds)

        assertEquals(first.fix, freshestWithin(listOf(first, second), 30.seconds))
    }

    @Test
    fun `the seed is the freshest fix within the seed age else the given reason`() {
        val candidates: List<AgedFix> = listOf(AgedFix(fix(1.0), 10.seconds), AgedFix(fix(2.0), 40.seconds))

        assertEquals(reading(1.0), seedReading(candidates, 30.seconds, LocationUnavailability.NO_FIX))
        assertEquals(
            unavailable(LocationUnavailability.NO_FIX),
            seedReading(candidates, 5.seconds, LocationUnavailability.NO_FIX),
        )
        assertEquals(
            unavailable(LocationUnavailability.SERVICE_DISABLED),
            seedReading(emptyList(), Duration.INFINITE, LocationUnavailability.SERVICE_DISABLED),
        )
        assertEquals(reading(2.0), seedReading(listOf(AgedFix(fix(2.0), 40.seconds)), Duration.INFINITE, LocationUnavailability.NO_FIX))
        assertEquals(
            unavailable(LocationUnavailability.NO_FIX),
            seedReading(candidates, Duration.ZERO, LocationUnavailability.NO_FIX),
        )
    }

    // endregion

    // region endsStream

    @Test
    fun `only a missing permission and an unsupported source end a stream`() {
        assertTrue(unavailable(LocationUnavailability.PERMISSION_MISSING).endsStream)
        assertTrue(unavailable(LocationUnavailability.UNSUPPORTED).endsStream)
        assertFalse(unavailable(LocationUnavailability.SERVICE_DISABLED).endsStream)
        assertFalse(unavailable(LocationUnavailability.NO_FIX).endsStream)
        assertFalse(reading(1.0).endsStream)
    }

    @Test
    fun `every reason is classified so a new one forces a decision here`() {
        val terminal: Set<LocationUnavailability> = setOf(LocationUnavailability.PERMISSION_MISSING, LocationUnavailability.UNSUPPORTED)
        for (reason: LocationUnavailability in LocationUnavailability.entries) {
            assertEquals(reason in terminal, unavailable(reason).endsStream, "reason $reason")
        }
        assertEquals(4, LocationUnavailability.entries.size, "a new reason must be classified in endsStream and in PLAN D8")
    }

    // endregion

    // region completingAfterTerminalReading: completion, release, re-collection

    /** An upstream that counts registrations and releases, like a platform listener behind a stream. */
    private class CountingUpstream(private val readings: List<LocationReading>, private val thenSuspend: Boolean) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<LocationReading> = flow {
            registered++
            try {
                for (item: LocationReading in readings) emit(item)
                if (thenSuspend) awaitCancellation()
            } finally {
                released++
            }
        }
    }

    @Test
    fun `permission missing completes the stream after emitting it and releases the upstream`() = runTest {
        val upstream = CountingUpstream(
            listOf(reading(1.0), unavailable(LocationUnavailability.NO_FIX), unavailable(LocationUnavailability.PERMISSION_MISSING), reading(2.0)),
            thenSuspend = true,
        )

        val received: List<LocationReading> = upstream.flow.completingAfterTerminalReading().toList()

        assertEquals(
            listOf(reading(1.0), unavailable(LocationUnavailability.NO_FIX), unavailable(LocationUnavailability.PERMISSION_MISSING)),
            received,
        )
        assertEquals(1, upstream.registered)
        assertEquals(1, upstream.released, "the upstream registration is released when the stream completes")
    }

    @Test
    fun `unsupported completes the stream after emitting it`() = runTest {
        val upstream = CountingUpstream(listOf(unavailable(LocationUnavailability.UNSUPPORTED), reading(1.0)), thenSuspend = true)

        assertEquals(listOf(unavailable(LocationUnavailability.UNSUPPORTED)), upstream.flow.completingAfterTerminalReading().toList())
        assertEquals(1, upstream.released)
    }

    @Test
    fun `service disabled and no fix keep the stream open`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received: MutableList<LocationReading> = mutableListOf()
        val upstream = CountingUpstream(
            listOf(unavailable(LocationUnavailability.SERVICE_DISABLED), unavailable(LocationUnavailability.NO_FIX), reading(5.0)),
            thenSuspend = true,
        )
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.flow.completingAfterTerminalReading().collect { item: LocationReading ->
                received += item
                if (item == reading(5.0)) gate.complete(Unit)
            }
        }

        gate.await()
        // Control: the upstream existed and is still registered, the stream did not complete.
        assertEquals(1, upstream.registered)
        assertTrue(job.isActive, "service disabled and no fix must not complete the stream")
        assertEquals(3, received.size)

        job.cancelAndJoin()
        assertEquals(1, upstream.released, "cancellation releases the upstream")
    }

    @Test
    fun `collecting again after a terminal reading starts a new run that completes again`() = runTest {
        val upstream = CountingUpstream(listOf(unavailable(LocationUnavailability.PERMISSION_MISSING)), thenSuspend = true)
        val stream: Flow<LocationReading> = upstream.flow.completingAfterTerminalReading()

        assertEquals(1, stream.toList().size)
        assertEquals(1, stream.toList().size)

        assertEquals(2, upstream.registered, "each collection registers anew (re-initialisation after release)")
        assertEquals(2, upstream.released)
    }

    @Test
    fun `the operator returns the same readings it was given`() = runTest {
        val given: LocationReading = reading(7.0)
        val received: List<LocationReading> = CountingUpstream(listOf(given), thenSuspend = false).flow.completingAfterTerminalReading().toList()

        assertSame(given, received.single())
    }

    // endregion
}
