package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 1 of group loc-core (LOC-6 and the shared age/lifecycle helpers of PLAN 4.2; decision
 * D-23): the age rules at their exact boundaries (one nanosecond either side, ZERO, INFINITE, negative
 * ages and elapsed times from a clock that stepped back, huge finite durations), a seeded comparison of
 * `acceptsCachedFix` / `isFreshDelivery` / `freshestWithin` with the rules written from their KDoc, and
 * `completingAfterTerminalReading` against an upstream that would fail or keep emitting after the
 * terminal reading.
 */
class ReadingPolicyBreakerR1Test {

    private fun fix(latitude: Double): LocationFix = LocationFix(GeoCoordinates(latitude, 0.0), timestampEpochMillis = 0L)

    private val huge: Duration = (Long.MAX_VALUE / 4).milliseconds

    // region age boundaries

    @Test
    fun `the cache rule at one nanosecond either side of every boundary`() {
        val maxAge: Duration = 30.seconds
        assertTrue(acceptsCachedFix(maxAge, maxAge), "equal to maxAge is acceptable")
        assertFalse(acceptsCachedFix(maxAge + 1.nanoseconds, maxAge), "one nanosecond older is not")
        assertTrue(acceptsCachedFix(Duration.ZERO, 1.nanoseconds), "the smallest positive maxAge accepts age zero")
        assertFalse(acceptsCachedFix(Duration.ZERO, Duration.ZERO), "ZERO forces a fresh fix even for age zero")
        assertFalse(acceptsCachedFix(-Duration.INFINITE, Duration.ZERO), "ZERO accepts nothing, not even a clock-jump age")
        assertTrue(acceptsCachedFix(-Duration.INFINITE, 1.nanoseconds), "a negative age counts as zero")
        assertTrue(acceptsCachedFix(Duration.INFINITE, Duration.INFINITE), "INFINITE accepts any age")
        assertTrue(acceptsCachedFix(huge, Duration.INFINITE))
        assertFalse(acceptsCachedFix(Duration.INFINITE, huge), "an infinitely old fix is older than any finite maxAge")
        assertTrue(acceptsCachedFix(huge, huge))
    }

    @Test
    fun `the delivery rule at one nanosecond either side of every boundary`() {
        val maxAge: Duration = 30.seconds
        assertTrue(isFreshDelivery(maxAge, maxAge, Duration.ZERO))
        assertFalse(isFreshDelivery(maxAge + 1.nanoseconds, maxAge, Duration.ZERO))
        assertTrue(isFreshDelivery(10.seconds, Duration.ZERO, 10.seconds), "taken exactly at the request start is fresh under ZERO")
        assertFalse(isFreshDelivery(10.seconds + 1.nanoseconds, Duration.ZERO, 10.seconds), "one nanosecond before the request is not")
        assertTrue(isFreshDelivery(Duration.ZERO, Duration.ZERO, Duration.ZERO))
        assertTrue(isFreshDelivery(-Duration.INFINITE, Duration.ZERO, -Duration.INFINITE), "negative age and elapsed time count as zero")
        assertFalse(isFreshDelivery(1.nanoseconds, Duration.ZERO, -1.days), "a negative elapsed time is zero, not a licence")
        assertTrue(isFreshDelivery(Duration.INFINITE, Duration.INFINITE, Duration.ZERO), "INFINITE accepts any age")
        assertFalse(isFreshDelivery(Duration.INFINITE, huge, huge))
        assertTrue(isFreshDelivery(huge, Duration.ZERO, huge))
    }

    /** The two rules restated from their KDoc on whole nanoseconds, compared on seeded durations around each other. */
    @Test
    fun `seeded ages match the rules restated from the KDoc`() {
        val seed: Int = GEO_TEST_SEED + 720
        val rng = Random(seed)
        fun draw(): Duration = when (rng.nextInt(6)) {
            0 -> Duration.ZERO
            1 -> Duration.INFINITE
            2 -> -rng.nextLong(1, 1_000_000_000).nanoseconds
            3 -> rng.nextLong(0, 3).nanoseconds
            else -> rng.nextLong(0, 60_000_000_000).nanoseconds
        }
        repeat(50_000) { index: Int ->
            val age: Duration = draw()
            val maxAge: Duration = draw().let { if (it.isNegative()) -it else it }
            val since: Duration = draw()
            val effectiveAge: Duration = if (age.isNegative()) Duration.ZERO else age
            val effectiveSince: Duration = if (since.isNegative()) Duration.ZERO else since
            val cache: Boolean = when {
                maxAge == Duration.ZERO -> false
                maxAge == Duration.INFINITE -> true
                else -> effectiveAge <= maxAge
            }
            val delivery: Boolean = maxAge == Duration.INFINITE || effectiveAge <= effectiveSince || effectiveAge <= maxAge
            assertEquals(cache, acceptsCachedFix(age, maxAge), "seed=$seed #$index: cache age=$age maxAge=$maxAge")
            assertEquals(delivery, isFreshDelivery(age, maxAge, since), "seed=$seed #$index: delivery age=$age maxAge=$maxAge since=$since")
        }
    }

    @Test
    fun `the freshest cache is chosen among clock-jump and boundary ages and ties keep the first`() {
        val a: LocationFix = fix(1.0)
        val b: LocationFix = fix(2.0)
        val c: LocationFix = fix(3.0)
        assertSame(b, freshestWithin(listOf(AgedFix(a, 30.seconds), AgedFix(b, 1.nanoseconds), AgedFix(c, 30.seconds + 1.nanoseconds)), 30.seconds))
        assertSame(a, freshestWithin(listOf(AgedFix(a, 5.seconds), AgedFix(b, 5.seconds)), 30.seconds), "ties keep the first")
        assertNull(freshestWithin(listOf(AgedFix(a, -1.seconds), AgedFix(b, Duration.ZERO)), Duration.ZERO), "ZERO accepts no cache at all")
        assertNull(freshestWithin(emptyList(), Duration.INFINITE))
        assertSame(c, freshestWithin(listOf(AgedFix(a, Duration.INFINITE), AgedFix(c, huge)), Duration.INFINITE))
        assertEquals(
            LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
            seedReading(listOf(AgedFix(a, 30.seconds + 1.nanoseconds)), 30.seconds, LocationUnavailability.SERVICE_DISABLED),
        )
        val seed: LocationReading = seedReading(listOf(AgedFix(a, 31.seconds), AgedFix(b, 30.seconds)), 30.seconds, LocationUnavailability.NO_FIX)
        assertEquals(LocationReading.Available(b), seed)
    }

    // endregion

    // region completingAfterTerminalReading

    @Test
    fun `a terminal reading ends the stream before an upstream failure that would follow it`() = runTest {
        for (reason: LocationUnavailability in listOf(LocationUnavailability.PERMISSION_MISSING, LocationUnavailability.UNSUPPORTED)) {
            var releasedAfterTerminal = false
            val upstream: Flow<LocationReading> = flow {
                try {
                    emit(LocationReading.Unavailable(reason))
                    throw IllegalStateException("the upstream must not be resumed after a terminal reading")
                } finally {
                    releasedAfterTerminal = true
                }
            }

            val result: List<LocationReading> = upstream.completingAfterTerminalReading().toList()

            assertEquals(listOf<LocationReading>(LocationReading.Unavailable(reason)), result, "reason $reason")
            assertTrue(releasedAfterTerminal, "reason $reason: the upstream is released")
        }
    }

    @Test
    fun `an empty upstream completes empty and a non-terminal stream passes through untouched`() = runTest {
        assertEquals(emptyList(), emptyFlow<LocationReading>().completingAfterTerminalReading().toList())
        val readings: List<LocationReading> = listOf(
            LocationReading.Unavailable(LocationUnavailability.SERVICE_DISABLED),
            LocationReading.Available(fix(1.0)),
            LocationReading.Unavailable(LocationUnavailability.NO_FIX),
            LocationReading.Available(fix(2.0)),
        )
        assertEquals(readings, flow { readings.forEach { emit(it) } }.completingAfterTerminalReading().toList())
    }

    // endregion
}
