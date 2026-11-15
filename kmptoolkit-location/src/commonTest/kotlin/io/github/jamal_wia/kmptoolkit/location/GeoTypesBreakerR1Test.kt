package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Breaker round 1 of group loc-core (LOC-1, LOC-2, LOC-5, LOC-6; decision D-23): boundary attacks on the
 * validating types - `GeoCoordinates` (one ulp past every limit, subnormals, signed zero, NaN and
 * infinities in every slot, `copy`), `GeoBounds` (zero-width and seam boxes, `contains` at +-180 on both
 * sides, `longitudeRanges` agreeing with `contains`), `LocationUpdatesOptions` / `CurrentLocationOptions`
 * (one nanosecond either side of every limit, infinities, `copy`), `checkMinChangeDegrees`, and
 * `fixOrNull`. Derived from the KDoc of each type.
 */
class GeoTypesBreakerR1Test {

    private val limits: DoubleArray = doubleArrayOf(-90.0, 90.0)

    // region GeoCoordinates

    @Test
    fun `one ulp past each limit is rejected and the limit itself is accepted`() {
        for (latitude: Double in limits) {
            assertNotNull(GeoCoordinates.orNull(latitude, 0.0))
            val outside: Double = if (latitude > 0) latitude.nextUp() else latitude.nextDown()
            assertNull(GeoCoordinates.orNull(outside, 0.0), "latitude $outside")
            assertFailsWith<IllegalArgumentException>("latitude $outside") { GeoCoordinates(outside, 0.0) }
        }
        for (longitude: Double in doubleArrayOf(-180.0, 180.0)) {
            assertNotNull(GeoCoordinates.orNull(0.0, longitude))
            val outside: Double = if (longitude > 0) longitude.nextUp() else longitude.nextDown()
            assertNull(GeoCoordinates.orNull(0.0, outside), "longitude $outside")
            assertFailsWith<IllegalArgumentException>("longitude $outside") { GeoCoordinates(0.0, outside) }
        }
    }

    @Test
    fun `subnormals and signed zeros are valid and non-finite values are rejected in every slot`() {
        for (value: Double in doubleArrayOf(0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE)) {
            assertNotNull(GeoCoordinates.orNull(value, value), "value $value")
            GeoCoordinates(value, value)
        }
        val bad: DoubleArray = doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE, -Double.MAX_VALUE)
        for (value: Double in bad) {
            assertNull(GeoCoordinates.orNull(value, 0.0), "latitude $value")
            assertNull(GeoCoordinates.orNull(0.0, value), "longitude $value")
            assertNull(GeoCoordinates.orNull(value, value), "both $value")
            assertFailsWith<IllegalArgumentException> { GeoCoordinates(value, 0.0) }
            assertFailsWith<IllegalArgumentException> { GeoCoordinates(0.0, value) }
        }
        val valid = GeoCoordinates(1.0, 2.0)
        assertFailsWith<IllegalArgumentException>("copy validates") { valid.copy(latitude = Double.NaN) }
        assertFailsWith<IllegalArgumentException>("copy validates") { valid.copy(longitude = 180.0.nextUp()) }
    }

    @Test
    fun `orNull and the constructor agree on every seeded value`() {
        val seed: Int = GEO_TEST_SEED + 710
        val rng = Random(seed)
        val specials: DoubleArray = doubleArrayOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0, 0.0, 90.0, -90.0, 180.0, -180.0,
            90.0.nextUp(), (-90.0).nextDown(), 180.0.nextUp(), (-180.0).nextDown(), Double.MIN_VALUE,
        )
        fun draw(): Double = if (rng.nextInt(3) == 0) specials[rng.nextInt(specials.size)] else rng.nextDouble(-200.0, 200.0)
        repeat(20_000) { index: Int ->
            val latitude: Double = draw()
            val longitude: Double = draw()
            val viaOrNull: GeoCoordinates? = GeoCoordinates.orNull(latitude, longitude)
            val viaConstructor: GeoCoordinates? = runCatching { GeoCoordinates(latitude, longitude) }.getOrNull()
            assertEquals(viaConstructor, viaOrNull, "seed=$seed #$index: ($latitude, $longitude)")
        }
    }

    // endregion

    // region GeoBounds

    @Test
    fun `the antimeridian is one meridian for contains in every box shape that touches it`() {
        val north = 10.0
        val south = -10.0
        val atPlus = GeoCoordinates(0.0, 180.0)
        val atMinus = GeoCoordinates(0.0, -180.0)
        val boxes: List<GeoBounds> = listOf(
            GeoBounds(south, 170.0, north, 180.0),
            GeoBounds(south, -180.0, north, -170.0),
            GeoBounds(south, 170.0, north, -170.0),
            GeoBounds(south, 180.0, north, 180.0),
            GeoBounds(south, -180.0, north, -180.0),
            GeoBounds(south, 180.0, north, -180.0),
            GeoBounds(south, -180.0, north, 180.0),
            GeoBounds(south, 170.0, north, -180.0),
            GeoBounds(south, 180.0, north, -170.0),
        )
        for (box: GeoBounds in boxes) {
            assertTrue(atPlus in box, "$box must contain longitude 180")
            assertTrue(atMinus in box, "$box must contain longitude -180")
            assertFalse(GeoCoordinates(north.nextUp(), 180.0) in box, "$box: latitude just above north")
            assertFalse(GeoCoordinates(south.nextDown(), -180.0) in box, "$box: latitude just below south")
        }
        assertFalse(GeoCoordinates(0.0, 0.0) in GeoBounds(south, 180.0, north, -180.0), "a zero-width seam box holds only the seam")
        assertFalse(GeoBounds(0.0, 0.0, 0.0, -0.0).crossesAntimeridian, "-0.0 east of 0.0 west is not a crossing")
        assertTrue(GeoCoordinates(0.0, -0.0) in GeoBounds(0.0, 0.0, 0.0, 0.0), "signed zero is the same meridian")
    }

    @Test
    fun `longitude ranges agree with contains away from the seam on seeded boxes`() {
        val seed: Int = GEO_TEST_SEED + 711
        val rng = Random(seed)
        repeat(20_000) { index: Int ->
            val south: Double = rng.nextDouble(-90.0, 90.0)
            val north: Double = rng.nextDouble(south, 90.0)
            val box = GeoBounds(south, rng.nextDouble(-180.0, 180.0), north, rng.nextDouble(-180.0, 180.0))
            val point = GeoCoordinates(rng.nextDouble(south, north), rng.nextDouble(-179.999, 179.999))
            val byRanges: Boolean = box.longitudeRanges.any { range -> point.longitude in range }
            assertEquals(byRanges, point in box, "seed=$seed #$index: $point in $box (ranges ${box.longitudeRanges})")
            assertTrue(box.longitudeRanges.all { range -> range.start <= range.endInclusive }, "seed=$seed #$index: $box")
        }
    }

    @Test
    fun `invalid boxes are rejected the same way by orNull and the constructor`() {
        val bad: List<DoubleArray> = listOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0),
            doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, Double.NaN, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, Double.NaN, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, Double.NaN),
            doubleArrayOf((-90.0).nextDown(), 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 90.0.nextUp(), 0.0),
            doubleArrayOf(0.0, (-180.0).nextDown(), 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 180.0.nextUp()),
            doubleArrayOf(0.0, Double.NEGATIVE_INFINITY, 0.0, Double.POSITIVE_INFINITY),
        )
        for (values: DoubleArray in bad) {
            assertNull(GeoBounds.orNull(values[0], values[1], values[2], values[3]), values.contentToString())
            assertFailsWith<IllegalArgumentException>(values.contentToString()) { GeoBounds(values[0], values[1], values[2], values[3]) }
        }
        assertFailsWith<IllegalArgumentException>("copy validates") { GeoBounds(0.0, 0.0, 1.0, 1.0).copy(north = -1.0) }
    }

    // endregion

    // region options

    @Test
    fun `stream options accept every limit and reject one nanosecond or one ulp past it`() {
        LocationUpdatesOptions(minUpdateInterval = Duration.ZERO, minUpdateDistanceMeters = 0.0, maxSeedAge = Duration.ZERO)
        LocationUpdatesOptions(minUpdateInterval = 1.nanoseconds, minUpdateDistanceMeters = Double.MIN_VALUE, maxSeedAge = Duration.INFINITE)
        LocationUpdatesOptions(minUpdateDistanceMeters = -0.0)
        LocationUpdatesOptions(minUpdateDistanceMeters = Double.MAX_VALUE)
        for (interval: Duration in listOf((-1).nanoseconds, Duration.INFINITE, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException>("interval $interval") { LocationUpdatesOptions(minUpdateInterval = interval) }
        }
        for (distance: Double in doubleArrayOf(-Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("distance $distance") { LocationUpdatesOptions(minUpdateDistanceMeters = distance) }
        }
        for (age: Duration in listOf((-1).nanoseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException>("seed age $age") { LocationUpdatesOptions(maxSeedAge = age) }
        }
        assertFailsWith<IllegalArgumentException>("copy validates") { LocationUpdatesOptions().copy(minUpdateInterval = Duration.INFINITE) }
    }

    @Test
    fun `one-shot options accept every limit and reject one nanosecond past it`() {
        CurrentLocationOptions(maxAge = Duration.ZERO, timeout = 1.nanoseconds)
        CurrentLocationOptions(maxAge = Duration.INFINITE, timeout = Duration.INFINITE)
        for (age: Duration in listOf((-1).nanoseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException>("max age $age") { CurrentLocationOptions(maxAge = age) }
        }
        for (timeout: Duration in listOf(Duration.ZERO, (-1).nanoseconds, -Duration.INFINITE)) {
            assertFailsWith<IllegalArgumentException>("timeout $timeout") { CurrentLocationOptions(timeout = timeout) }
        }
        assertFailsWith<IllegalArgumentException>("copy validates") { CurrentLocationOptions().copy(timeout = Duration.ZERO) }
    }

    @OptIn(ToolkitInternalApi::class)
    @Test
    fun `the heading filter check accepts zero and every finite non-negative value only`() {
        for (value: Double in doubleArrayOf(0.0, -0.0, Double.MIN_VALUE, 360.0, Double.MAX_VALUE)) checkMinChangeDegrees(value)
        for (value: Double in doubleArrayOf(-Double.MIN_VALUE, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("value $value") { checkMinChangeDegrees(value) }
        }
    }

    // endregion

    @Test
    fun `fixOrNull returns the fix of an Available and null for every reason`() {
        val fix = LocationFix(GeoCoordinates(1.0, 2.0), timestampEpochMillis = Long.MIN_VALUE)
        assertEquals(fix, LocationReading.Available(fix).fixOrNull())
        for (reason: LocationUnavailability in LocationUnavailability.entries) {
            assertNull(LocationReading.Unavailable(reason).fixOrNull(), "reason $reason")
        }
    }
}
