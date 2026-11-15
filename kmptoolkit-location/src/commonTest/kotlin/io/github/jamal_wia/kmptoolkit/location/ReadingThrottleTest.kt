@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The common throttle (PLAN D16, M10, section 6.1 "Throttle helper", review M1 of round 1): a
 * trailing-edge throttle. An `Unavailable` is never delayed or dropped and discards the pending fix,
 * the distance is measured from the last emitted fix, and a fix that only the interval holds back is
 * emitted when the interval ends instead of being lost.
 *
 * Time is virtual: the scripted upstreams `delay` on the test scheduler and the throttle measures the
 * interval on `testScheduler.timeSource`, so each test states exactly when every reading arrives and
 * when it comes out, and nothing waits on real time. The storms compare many collections with a
 * straight-line reference model, each with its own seed, and print the seed on a mismatch.
 */
class ReadingThrottleTest {

    /** About 111.195 m per 0.001 degree of latitude on the sphere. */
    private fun at(latitude: Double): LocationReading =
        LocationReading.Available(LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L))

    private fun unavailable(reason: LocationUnavailability): LocationReading = LocationReading.Unavailable(reason)

    private fun options(distanceMeters: Double = 0.0, interval: Duration = Duration.ZERO): LocationUpdatesOptions =
        LocationUpdatesOptions(minUpdateDistanceMeters = distanceMeters, minUpdateInterval = interval)

    /** A reading and the virtual millisecond at which it left the throttle. */
    private data class Timed(val atMillis: Long, val reading: LocationReading)

    /** Scripts one upstream: [emitAt] waits for an absolute virtual time and then emits. */
    private class Script(private val scheduler: TestCoroutineScheduler, private val out: FlowCollector<LocationReading>) {
        suspend fun emitAt(millis: Long, reading: LocationReading) {
            idleUntil(millis)
            out.emit(reading)
        }

        suspend fun idleUntil(millis: Long) {
            val wait: Long = millis - scheduler.currentTime
            if (wait > 0) delay(wait)
        }
    }

    /** Runs [script] as the upstream until it completes and returns what left the throttle, with the virtual time of each. */
    private suspend fun TestScope.runTimed(
        options: LocationUpdatesOptions,
        isSeed: (LocationReading) -> Boolean = { false },
        script: suspend Script.() -> Unit,
    ): List<Timed> {
        val scheduler: TestCoroutineScheduler = testScheduler
        return flow { Script(scheduler, this).script() }
            .throttledBy(options, scheduler.timeSource, isSeed)
            .map { reading: LocationReading -> Timed(scheduler.currentTime, reading) }
            .toList()
    }

    /** An upstream that plays [steps] (absolute virtual millis), counts registrations and releases, then suspends or completes. */
    private class ScriptedUpstream(
        private val scheduler: TestCoroutineScheduler,
        private val steps: List<Pair<Long, LocationReading>>,
        private val thenSuspend: Boolean = true,
        private val thenFail: Boolean = false,
    ) {
        var registered: Int = 0
            private set
        var released: Int = 0
            private set

        val flow: Flow<LocationReading> = flow {
            registered++
            try {
                for ((millis: Long, reading: LocationReading) in steps) {
                    val wait: Long = millis - scheduler.currentTime
                    if (wait > 0) delay(wait)
                    emit(reading)
                }
                if (thenFail) throw IllegalStateException("platform failure")
                if (thenSuspend) awaitCancellation()
            } finally {
                released++
            }
        }
    }

    // region interval: trailing edge

    @Test
    fun `with no interval and no distance every reading passes at once`() = runTest {
        val result: List<Timed> = runTimed(options()) {
            emitAt(0, at(0.0)); emitAt(10, at(0.0)); emitAt(20, at(0.0001))
        }

        assertEquals(listOf(Timed(0, at(0.0)), Timed(10, at(0.0)), Timed(20, at(0.0001))), result)
    }

    @Test
    fun `a fix inside the interval is emitted when the interval ends not dropped`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(3_000, at(2.0)) // inside the interval: held, emitted at 5 000
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(5_000, at(2.0))), result)
    }

    @Test
    fun `a fix that jitters 20 ms early is emitted 20 ms late not dropped`() = runTest {
        // The Android case: the platform honours the interval itself, delivery jitter makes one fix come early.
        val result: List<Timed> = runTimed(options(interval = 10.seconds)) {
            emitAt(0, at(1.0))
            emitAt(9_980, at(2.0))
            emitAt(20_000, at(3.0)) // a full interval after the second emission (10 000): passes at once, no wait
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(10_000, at(2.0)), Timed(20_000, at(3.0))), result)
    }

    @Test
    fun `a fix at exactly the interval passes at once`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(5_000, at(2.0))
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(5_000, at(2.0))), result)
    }

    @Test
    fun `the newest fix inside the interval wins and the older held one is replaced`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0))
            emitAt(2_000, at(3.0))
            emitAt(4_000, at(4.0))
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(5_000, at(4.0))), result)
    }

    @Test
    fun `the interval is measured from the last emitted fix and restarts after a held fix is emitted`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(3_000, at(2.0)) // held, emitted at 5 000
            emitAt(6_000, at(3.0)) // 1 s after the emission at 5 000: held again, emitted at 10 000
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(5_000, at(2.0)), Timed(10_000, at(3.0))), result)
    }

    @Test
    fun `a user who moved and then stopped is not left on the stale position`() = runTest {
        // iOS: 60 s interval, 100 m filter. The user walks 100 m 10 s after the last emission and stops:
        // the platform sends nothing more, the throttle itself must deliver the fix at 60 s.
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(0L to at(0.0), 10_000L to at(0.0009)))
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch {
            upstream.flow.throttledBy(options(distanceMeters = 100.0, interval = 1.minutes), scheduler.timeSource)
                .collect { reading: LocationReading -> received += Timed(currentTime, reading) }
        }

        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(listOf(Timed(0, at(0.0))), received, "nothing is emitted before the interval ends")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(Timed(0, at(0.0)), Timed(60_000, at(0.0009))), received)

        job.cancelAndJoin()
        assertEquals(1, upstream.released)
    }

    @Test
    fun `without a seed marker the first fix starts the interval and a live fix soon after waits for its end`() = runTest {
        // The default: nothing is a seed, so a first fix is an ordinary emission. The fix is held, not lost.
        val result: List<Timed> = runTimed(options(interval = 30.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0))
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(30_000, at(2.0))), result)
    }

    @Test
    fun `a fix and a deadline at the same instant give the same result in either order`() = runTest {
        // f2 is held until 10 000; f3 arrives exactly at the deadline. The deadline is honoured first:
        // f2 is emitted at 10 000 and f3 becomes the held fix for 20 000. A tick of the first deadline
        // must not flush f3 early.
        val result: List<Timed> = runTimed(options(interval = 10.seconds)) {
            emitAt(0, at(1.0))
            emitAt(3_000, at(2.0))
            emitAt(10_000, at(3.0))
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(10_000, at(2.0)), Timed(20_000, at(3.0))), result)
    }

    @Test
    fun `a held fix is still emitted at the deadline when the upstream completes first and then the stream completes`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0))
        }
        val completedAt: Long = currentTime

        assertEquals(listOf(Timed(0, at(1.0)), Timed(5_000, at(2.0))), result)
        assertEquals(5_000L, completedAt, "the stream completes right after the held fix, not before it")
    }

    // endregion

    // region seed (D-21): a cached seed sets the distance reference but starts no interval

    @Test
    fun `the first live fix after a seed passes at once even inside the interval`() = runTest {
        val seed: LocationReading = at(1.0)
        val result: List<Timed> = runTimed(options(interval = 30.seconds), isSeed = { reading: LocationReading -> reading === seed }) {
            emitAt(0, seed)
            emitAt(1_000, at(2.0)) // a fresh fix beats the cached one: no wait
            emitAt(2_000, at(3.0)) // the interval started with the live fix: held until 31 000
        }

        assertEquals(listOf(Timed(0, seed), Timed(1_000, at(2.0)), Timed(31_000, at(3.0))), result)
    }

    @Test
    fun `a live fix too close to the seed is dropped and a far one later still passes at once`() = runTest {
        val seed: LocationReading = at(0.0)
        val result: List<Timed> = runTimed(
            options(distanceMeters = 100.0, interval = 30.seconds),
            isSeed = { reading: LocationReading -> reading === seed },
        ) {
            emitAt(0, seed)
            emitAt(1_000, at(0.0001)) // 11 m from the seed: dropped, nothing held (no interval is running)
            emitAt(5_000, at(0.002)) // 222 m: passes at once
            idleUntil(60_000)
        }

        assertEquals(listOf(Timed(0, seed), Timed(5_000, at(0.002))), result)
    }

    @Test
    fun `only the marked reading is a seed`() = runTest {
        val seed: LocationReading = at(1.0)
        val result: List<Timed> = runTimed(options(interval = 30.seconds), isSeed = { reading: LocationReading -> reading === seed }) {
            emitAt(0, at(1.0)) // equal to the seed but not the marked instance: an ordinary first fix
            emitAt(1_000, at(2.0))
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(30_000, at(2.0))), result)
    }

    @Test
    fun `a reason after a seed passes at once and the next fix passes at once too`() = runTest {
        val seed: LocationReading = at(1.0)
        val result: List<Timed> = runTimed(options(interval = 30.seconds), isSeed = { reading: LocationReading -> reading === seed }) {
            emitAt(0, seed)
            emitAt(500, unavailable(LocationUnavailability.SERVICE_DISABLED))
            emitAt(1_000, at(2.0))
        }

        assertEquals(
            listOf(Timed(0, seed), Timed(500, unavailable(LocationUnavailability.SERVICE_DISABLED)), Timed(1_000, at(2.0))),
            result,
        )
    }

    @Test
    fun `cancelling after a seed and a held live fix releases the upstream and leaves no timer behind`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val seed: LocationReading = at(1.0)
        val upstream = ScriptedUpstream(scheduler, listOf(0L to seed, 100L to at(2.0), 200L to at(3.0)))
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch {
            upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource) { reading: LocationReading -> reading === seed }
                .collect { reading: LocationReading -> received += Timed(currentTime, reading) }
        }

        advanceTimeBy(300)
        runCurrent()
        // Control: the seed and the first live fix are out, the second live fix is held behind an hour-long interval.
        assertEquals(listOf(Timed(0, seed), Timed(100, at(2.0))), received)
        assertEquals(0, upstream.released)
        assertTrue(job.isActive)

        job.cancelAndJoin()

        assertEquals(1, upstream.released)
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "no timer is left")
        assertEquals(2, received.size, "the held fix is not delivered after the cancellation")
    }

    // endregion

    // region distance

    @Test
    fun `the distance is measured from the last emitted fix not from the last received one`() = runTest {
        val result: List<Timed> = runTimed(options(distanceMeters = 100.0)) {
            emitAt(0, at(0.0))
            emitAt(1, at(0.00054)) // about 60 m from the first: dropped
            emitAt(2, at(0.00108)) // about 120 m from the first, 60 m from the dropped one: passes
            emitAt(3, at(0.00110)) // about 2 m from the emitted one: dropped
        }

        assertEquals(listOf(Timed(0, at(0.0)), Timed(2, at(0.00108))), result)
    }

    @Test
    fun `a move of exactly the minimum distance passes`() = runTest {
        val first: GeoCoordinates = GeoCoordinates(0.0, 0.0)
        val second: GeoCoordinates = GeoCoordinates(0.001, 0.0)
        val exact: Double = first.distanceInMetersTo(second)

        val result: List<Timed> = runTimed(options(distanceMeters = exact)) {
            emitAt(0, LocationReading.Available(LocationFix(first, 0L)))
            emitAt(1, LocationReading.Available(LocationFix(second, 0L)))
        }

        assertEquals(2, result.size)
    }

    @Test
    fun `a fix that is too close is dropped and discards the held fix`() = runTest {
        // f2 is far enough but early: held. f3 is back near f1: the newest position says there is nothing to report.
        val result: List<Timed> = runTimed(options(distanceMeters = 100.0, interval = 10.seconds)) {
            emitAt(0, at(0.0))
            emitAt(1_000, at(0.002)) // 222 m: held
            emitAt(2_000, at(0.0001)) // 11 m from the emitted fix: dropped, and the held fix goes with it
            idleUntil(30_000)
        }

        assertEquals(listOf(Timed(0, at(0.0))), result)
    }

    @Test
    fun `a fix must satisfy both the interval and the distance`() = runTest {
        val result: List<Timed> = runTimed(options(distanceMeters = 100.0, interval = 5.seconds)) {
            emitAt(0, at(0.0))
            emitAt(10_000, at(0.0001)) // old enough, 11 m: dropped
            emitAt(10_000, at(0.002)) // 222 m, interval over: passes at once
            emitAt(11_000, at(0.004)) // far enough, only 1 s after the last emitted: held, emitted at 15 000
            emitAt(15_000, at(0.004)) // the same position as the fix just emitted: dropped
        }

        assertEquals(listOf(Timed(0, at(0.0)), Timed(10_000, at(0.002)), Timed(15_000, at(0.004))), result)
    }

    // endregion

    // region Unavailable is never hidden (M10) and discards the held fix

    @Test
    fun `an Unavailable is never dropped whatever the interval and the distance`() = runTest {
        val hostile: LocationUpdatesOptions = options(distanceMeters = 1.0e9, interval = 1.hours)
        val reasons: List<LocationReading> = LocationUnavailability.entries.map { reason: LocationUnavailability -> unavailable(reason) }

        val result: List<Timed> = runTimed(hostile) {
            emitAt(0, at(0.0))
            for (reason: LocationReading in reasons) emitAt(0, reason) // immediately after a fix, all inside the interval
            for (reason: LocationReading in reasons) emitAt(0, reason) // repeated reasons are not deduplicated either
        }

        assertEquals((listOf(at(0.0)) + reasons + reasons).map { reading: LocationReading -> Timed(0, reading) }, result)
    }

    @Test
    fun `a fix after an emitted Unavailable passes even inside the interval and the distance`() = runTest {
        val result: List<Timed> = runTimed(options(distanceMeters = 1.0e9, interval = 1.hours)) {
            emitAt(0, at(1.0))
            emitAt(0, unavailable(LocationUnavailability.SERVICE_DISABLED))
            emitAt(0, at(1.0)) // the consumer's latest value is a reason: this fix must reach it
            emitAt(0, at(1.0)) // and the throttle applies again from here: dropped (too close)
        }

        assertEquals(
            listOf(Timed(0, at(1.0)), Timed(0, unavailable(LocationUnavailability.SERVICE_DISABLED)), Timed(0, at(1.0))),
            result,
        )
    }

    @Test
    fun `an Unavailable discards the held fix and it is never emitted afterwards`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0)) // held until 5 000
            emitAt(2_000, unavailable(LocationUnavailability.SERVICE_DISABLED)) // passes at once, the held fix predates it
            idleUntil(30_000)
        }

        assertEquals(listOf(Timed(0, at(1.0)), Timed(2_000, unavailable(LocationUnavailability.SERVICE_DISABLED))), result)
    }

    @Test
    fun `after an Unavailable discarded a held fix the next fix passes at once and the throttle restarts from it`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0)) // held
            emitAt(2_000, unavailable(LocationUnavailability.NO_FIX))
            emitAt(2_500, at(3.0)) // passes at once: the reference is gone
            emitAt(3_000, at(4.0)) // held until 7 500
        }

        assertEquals(
            listOf(
                Timed(0, at(1.0)),
                Timed(2_000, unavailable(LocationUnavailability.NO_FIX)),
                Timed(2_500, at(3.0)),
                Timed(7_500, at(4.0)),
            ),
            result,
        )
    }

    @Test
    fun `a reason at the exact deadline comes after the fix that was held for that deadline`() = runTest {
        val result: List<Timed> = runTimed(options(interval = 5.seconds)) {
            emitAt(0, at(1.0))
            emitAt(1_000, at(2.0)) // held until 5 000
            emitAt(5_000, unavailable(LocationUnavailability.NO_FIX))
        }

        assertEquals(
            listOf(Timed(0, at(1.0)), Timed(5_000, at(2.0)), Timed(5_000, unavailable(LocationUnavailability.NO_FIX))),
            result,
        )
    }

    // endregion

    // region cancellation, release, re-initialisation

    @Test
    fun `cancelling a collection mid-interval releases the upstream and leaves no timer behind`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0)))
        val received: MutableList<Timed> = mutableListOf()
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource)
                .collect { reading: LocationReading -> received += Timed(currentTime, reading) }
        }

        advanceTimeBy(150)
        runCurrent()
        // Control: the registration is live, the first fix came out and the second one is held for the interval.
        assertEquals(1, upstream.registered)
        assertEquals(0, upstream.released)
        assertTrue(job.isActive)
        assertEquals(listOf(Timed(0, at(1.0))), received)

        job.cancelAndJoin()

        assertEquals(1, upstream.released, "the upstream registration is released on cancellation")
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "no timer is left: an hour-long one would have moved the clock")
        assertEquals(listOf(Timed(0, at(1.0))), received, "the held fix is not delivered after the cancellation")
    }

    @Test
    fun `a new collection after a cancelled one starts fresh with no stale held fix`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(scheduler.currentTime to at(1.0)))
        val throttled: Flow<LocationReading> = upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource)

        for (round: Int in 1..3) {
            val arrived = CompletableDeferred<LocationReading>()
            val job: Job = launch(start = CoroutineStart.UNDISPATCHED) { throttled.collect { reading: LocationReading -> arrived.complete(reading) } }
            assertEquals(at(1.0), arrived.await(), "round $round: the first fix of a collection is never throttled")
            job.cancelAndJoin()
            assertEquals(round, upstream.released, "round $round")
        }

        assertEquals(3, upstream.registered, "every collection registers anew")
    }

    @Test
    fun `a held fix of a cancelled collection never appears in the next one`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val first = ScriptedUpstream(scheduler, listOf(0L to at(1.0), 10L to at(2.0)))
        val firstReceived: MutableList<LocationReading> = mutableListOf()
        val firstJob: Job = launch {
            first.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource).collect { firstReceived += it }
        }
        advanceTimeBy(100)
        runCurrent()
        firstJob.cancelAndJoin()

        val second = ScriptedUpstream(scheduler, listOf(scheduler.currentTime + 100 to at(9.0)))
        val secondReceived: MutableList<LocationReading> = mutableListOf()
        val secondJob: Job = launch {
            second.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource).collect { secondReceived += it }
        }
        advanceTimeBy(60_000)
        runCurrent()
        secondJob.cancelAndJoin()

        assertEquals(listOf(at(1.0)), firstReceived)
        assertEquals(listOf(at(9.0)), secondReceived, "the first collection's held fix must not leak into the second")
    }

    @Test
    fun `an upstream failure reaches the collector releases the upstream and delivers no held fix`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(0L to at(1.0), 100L to at(2.0)), thenFail = true)
        val received: MutableList<LocationReading> = mutableListOf()
        var failure: Throwable? = null

        try {
            upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource).collect { received += it }
        } catch (expected: IllegalStateException) {
            failure = expected
        }

        assertEquals("platform failure", failure?.message)
        assertEquals(listOf(at(1.0)), received)
        assertEquals(1, upstream.released)
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "no timer is left after the failure")
    }

    @Test
    fun `a collector that stops after the first reading releases the upstream and the timer`() = runTest {
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(scheduler, listOf(0L to at(1.0), 10L to at(2.0)))

        val head: LocationReading = upstream.flow.throttledBy(options(interval = 1.hours), scheduler.timeSource).first()

        assertEquals(at(1.0), head)
        assertEquals(1, upstream.released)
        val before: Long = currentTime
        testScheduler.advanceUntilIdle()
        assertEquals(before, currentTime, "no timer is left")
    }

    @Test
    fun `a slow consumer keeps the order of readings and reasons and loses nothing it should get`() = runTest {
        // The consumer needs 5 s for the first reading. Meanwhile a fix, a reason and a fix arrive; the loop is
        // blocked in its emit, the messages wait in order, and the reason still discards the held fix.
        val scheduler: TestCoroutineScheduler = testScheduler
        val upstream = ScriptedUpstream(
            scheduler,
            listOf(
                0L to at(1.0),
                1_000L to at(2.0),
                2_000L to unavailable(LocationUnavailability.SERVICE_DISABLED),
                3_000L to at(3.0),
            ),
            thenSuspend = false,
        )
        val received: MutableList<Timed> = mutableListOf()

        upstream.flow.throttledBy(options(interval = 10.seconds), scheduler.timeSource).collect { reading: LocationReading ->
            received += Timed(currentTime, reading)
            if (received.size == 1) delay(5.seconds)
        }

        assertEquals(
            listOf(Timed(0, at(1.0)), Timed(5_000, unavailable(LocationUnavailability.SERVICE_DISABLED)), Timed(5_000, at(3.0))),
            received,
        )
        assertEquals(1, upstream.released)
    }

    // endregion

    // region storms against a reference model

    /** One scripted arrival; [seed] marks the first reading as a cached seed (D-21). */
    private class Step(val atMillis: Long, val reading: LocationReading, val seed: Boolean = false)

    /**
     * The straight-line statement of the rules on absolute time, written independently of the operator:
     * what a list of steps must produce, with the time of each output. A deadline that is due at or
     * before a step is honoured before the step; when the script ends, a held fix is still emitted at
     * its deadline.
     */
    private fun expected(steps: List<Step>, distanceMeters: Double, interval: Duration): List<Timed> {
        val intervalMillis: Long = interval.inWholeMilliseconds
        val out: MutableList<Timed> = mutableListOf()
        var lastFix: LocationFix? = null
        var lastAt: Long = 0
        var held: LocationFix? = null

        fun flushDue(now: Long) {
            val fix: LocationFix = held ?: return
            val deadline: Long = lastAt + intervalMillis
            if (deadline <= now) {
                out += Timed(deadline, LocationReading.Available(fix))
                lastFix = fix
                lastAt = deadline
                held = null
            }
        }

        for (step: Step in steps) {
            flushDue(step.atMillis)
            val reading: LocationReading = step.reading
            if (reading is LocationReading.Unavailable) {
                out += Timed(step.atMillis, reading)
                lastFix = null
                held = null
                continue
            }
            val fix: LocationFix = (reading as LocationReading.Available).fix
            val reference: LocationFix? = lastFix
            if (reference == null) {
                out += Timed(step.atMillis, reading)
                lastFix = fix
                // A seed sets the distance reference but starts no interval.
                lastAt = if (step.seed) Long.MIN_VALUE / 4 else step.atMillis
                held = null
            } else if (reference.coordinates.distanceInMetersTo(fix.coordinates) < distanceMeters) {
                held = null
            } else if (step.atMillis - lastAt >= intervalMillis) {
                out += Timed(step.atMillis, reading)
                lastFix = fix
                lastAt = step.atMillis
                held = null
            } else {
                held = fix
            }
        }
        flushDue(Long.MAX_VALUE)
        return out
    }

    private fun script(rng: Random, length: Int): List<Step> {
        var now: Long = 0
        val steps: MutableList<Step> = MutableList(length) {
            now += rng.nextInt(0, 3_000)
            val reading: LocationReading = if (rng.nextInt(10) == 0) {
                unavailable(LocationUnavailability.entries[rng.nextInt(LocationUnavailability.entries.size)])
            } else {
                LocationReading.Available(
                    LocationFix(GeoCoordinates(rng.nextDouble(0.0, 0.01), rng.nextDouble(0.0, 0.01)), timestampEpochMillis = 0L),
                )
            }
            Step(now, reading)
        }
        // Half of the scripts start with a cached seed.
        val first: Step = steps[0]
        if (first.reading is LocationReading.Available && rng.nextBoolean()) steps[0] = Step(first.atMillis, first.reading, seed = true)
        return steps
    }

    @Test
    fun `sixteen concurrent collections in virtual time each match the reference model`() = runTest(timeout = 120.seconds) {
        val workers = 16
        val distances: List<Double> = listOf(0.0, 50.0, 500.0)
        val intervals: List<Duration> = listOf(Duration.ZERO, 1.seconds, 5.seconds)

        val mismatches: List<String?> = withContext(Dispatchers.Default) {
            (0 until workers).map { worker: Int ->
                async {
                    val seed: Int = GEO_TEST_SEED + 1_000 + worker
                    val rng = Random(seed)
                    val distance: Double = distances[rng.nextInt(distances.size)]
                    val interval: Duration = intervals[rng.nextInt(intervals.size)]
                    val steps: List<Step> = script(rng, 2_000)
                    // Each worker owns its test scheduler, so the virtual clock and the timer agree for that worker alone.
                    // The work is finite (a scripted upstream that completes), so draining the scheduler is bounded.
                    val worldOfWorker = TestScope()
                    val collected: Deferred<List<Timed>> = worldOfWorker.async {
                        val seedReading: LocationReading? = steps.firstOrNull { step: Step -> step.seed }?.reading
                        worldOfWorker.runTimed(options(distance, interval), isSeed = { reading: LocationReading -> reading === seedReading }) {
                            for (step: Step in steps) emitAt(step.atMillis, step.reading)
                        }
                    }
                    worldOfWorker.testScheduler.advanceUntilIdle()
                    check(collected.isCompleted) { "seed=$seed: the scripted collection did not complete" }
                    val actual: List<Timed> = collected.getCompleted()
                    val model: List<Timed> = expected(steps, distance, interval)
                    if (actual == model) {
                        null
                    } else {
                        val firstDifference: Int = actual.indices.firstOrNull { index: Int -> index >= model.size || actual[index] != model[index] } ?: model.size
                        "seed=$seed distance=$distance interval=$interval: ${actual.size} readings against ${model.size} expected, first difference at $firstDifference"
                    }
                }
            }.awaitAll()
        }

        assertTrue(mismatches.all { it == null }, mismatches.filterNotNull().joinToString("; "))
    }

    @Test
    fun `sixteen concurrent collections on real threads with no interval match the model`() = runTest(timeout = 120.seconds) {
        // No interval, so nothing needs real time: this storm only exercises the loop, the upstream child and the
        // bounded channel on genuinely parallel threads.
        val workers = 16
        val distances: List<Double> = listOf(0.0, 50.0, 500.0)

        val mismatches: List<String?> = withContext(Dispatchers.Default) {
            (0 until workers).map { worker: Int ->
                async {
                    val seed: Int = GEO_TEST_SEED + 2_000 + worker
                    val rng = Random(seed)
                    val distance: Double = distances[rng.nextInt(distances.size)]
                    val steps: List<Step> = script(rng, 5_000)
                    val actual: List<LocationReading> = flow { for (step: Step in steps) emit(step.reading) }
                        .throttledBy(options(distance, Duration.ZERO))
                        .toList()
                    val model: List<LocationReading> = expected(steps, distance, Duration.ZERO).map { it.reading }
                    if (actual == model) null else "seed=$seed distance=$distance: ${actual.size} readings against ${model.size} expected"
                }
            }.awaitAll()
        }

        assertTrue(mismatches.all { it == null }, mismatches.filterNotNull().joinToString("; "))
    }

    @Test
    fun `cancelling real-thread collections mid-stream releases every upstream`() = runTest(timeout = 120.seconds) {
        val workers = 16
        val counts: List<Pair<Int, Int>> = withContext(Dispatchers.Default) {
            (0 until workers).map { worker: Int ->
                async {
                    val rng = Random(GEO_TEST_SEED + 3_000 + worker)
                    var registered = 0
                    var released = 0
                    val started = CompletableDeferred<Unit>()
                    val upstream: Flow<LocationReading> = flow {
                        registered++
                        try {
                            while (true) {
                                emit(at(rng.nextDouble(0.0, 0.01)))
                                started.complete(Unit)
                            }
                        } finally {
                            released++
                        }
                    }
                    val job: Job = launch { upstream.throttledBy(options(distanceMeters = 0.0)).collect { } }
                    started.await()
                    job.cancelAndJoin()
                    registered to released
                }
            }.awaitAll()
        }

        assertTrue(counts.all { (registered: Int, released: Int) -> registered == 1 && released == 1 }, "registered/released per worker: $counts")
    }

    // endregion
}
