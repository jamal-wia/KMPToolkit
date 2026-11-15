package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The option types and their validation (PLAN D11, D12, D25, section 6.1 "Options"): the defaults
 * are the plan's, every documented bad value throws `IllegalArgumentException`, every documented
 * limit is accepted, and `copy` validates through the constructor.
 */
class LocationOptionsTest {

    // region CurrentLocationOptions

    @Test
    fun `current location defaults are balanced with a thirty second max age and timeout`() {
        val options = CurrentLocationOptions()

        assertEquals(LocationAccuracy.BALANCED, options.accuracy)
        assertEquals(30.seconds, options.maxAge)
        assertEquals(30.seconds, options.timeout)
        assertNull(options.platform)
    }

    @Test
    fun `a negative max age throws`() {
        assertFailsWith<IllegalArgumentException> { CurrentLocationOptions(maxAge = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { CurrentLocationOptions(maxAge = -Duration.INFINITE) }
    }

    @Test
    fun `a max age from zero to infinite is accepted`() {
        assertEquals(Duration.ZERO, CurrentLocationOptions(maxAge = Duration.ZERO).maxAge)
        assertEquals(Duration.INFINITE, CurrentLocationOptions(maxAge = Duration.INFINITE).maxAge)
        assertEquals(1.milliseconds, CurrentLocationOptions(maxAge = 1.milliseconds).maxAge)
    }

    @Test
    fun `a zero or negative timeout throws and an infinite one is accepted`() {
        assertFailsWith<IllegalArgumentException> { CurrentLocationOptions(timeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { CurrentLocationOptions(timeout = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { CurrentLocationOptions(timeout = -Duration.INFINITE) }
        assertEquals(Duration.INFINITE, CurrentLocationOptions(timeout = Duration.INFINITE).timeout)
        assertEquals(1.milliseconds, CurrentLocationOptions(timeout = 1.milliseconds).timeout)
    }

    @Test
    fun `current location copy validates`() {
        val options = CurrentLocationOptions()

        assertFailsWith<IllegalArgumentException> { options.copy(maxAge = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { options.copy(timeout = Duration.ZERO) }
        assertEquals(LocationAccuracy.HIGH_ACCURACY, options.copy(accuracy = LocationAccuracy.HIGH_ACCURACY).accuracy)
    }

    // endregion

    // region LocationUpdatesOptions

    @Test
    fun `updates defaults throttle nothing and seed with thirty seconds`() {
        val options = LocationUpdatesOptions()

        assertEquals(LocationAccuracy.BALANCED, options.accuracy)
        assertEquals(0.0, options.minUpdateDistanceMeters)
        assertEquals(Duration.ZERO, options.minUpdateInterval)
        assertEquals(30.seconds, options.maxSeedAge)
        assertNull(options.platform)
    }

    @Test
    fun `a negative or non-finite min update distance throws`() {
        for (bad: Double in listOf(-1.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("distance $bad") { LocationUpdatesOptions(minUpdateDistanceMeters = bad) }
        }
        assertEquals(0.0, LocationUpdatesOptions(minUpdateDistanceMeters = 0.0).minUpdateDistanceMeters)
        assertEquals(50.5, LocationUpdatesOptions(minUpdateDistanceMeters = 50.5).minUpdateDistanceMeters)
    }

    @Test
    fun `a negative or infinite min update interval throws`() {
        assertFailsWith<IllegalArgumentException> { LocationUpdatesOptions(minUpdateInterval = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { LocationUpdatesOptions(minUpdateInterval = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { LocationUpdatesOptions(minUpdateInterval = -Duration.INFINITE) }
        assertEquals(10.seconds, LocationUpdatesOptions(minUpdateInterval = 10.seconds).minUpdateInterval)
    }

    @Test
    fun `a seed age from zero to infinite is accepted and a negative one throws`() {
        assertEquals(Duration.ZERO, LocationUpdatesOptions(maxSeedAge = Duration.ZERO).maxSeedAge)
        assertEquals(Duration.INFINITE, LocationUpdatesOptions(maxSeedAge = Duration.INFINITE).maxSeedAge)
        assertFailsWith<IllegalArgumentException> { LocationUpdatesOptions(maxSeedAge = (-1).milliseconds) }
        assertFailsWith<IllegalArgumentException> { LocationUpdatesOptions(maxSeedAge = -Duration.INFINITE) }
    }

    @Test
    fun `updates copy validates`() {
        val options = LocationUpdatesOptions()

        assertFailsWith<IllegalArgumentException> { options.copy(minUpdateDistanceMeters = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { options.copy(minUpdateInterval = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { options.copy(maxSeedAge = (-1).seconds) }
    }

    // endregion

    // region the platform slots

    @OptIn(ToolkitInternalApi::class)
    private object FakeCurrentSlot : PlatformCurrentLocationOptions

    @OptIn(ToolkitInternalApi::class)
    private object FakeUpdatesSlot : PlatformLocationUpdatesOptions

    @Test
    fun `a platform slot is carried untouched and compared by value`() {
        val current = CurrentLocationOptions(platform = FakeCurrentSlot)
        val updates = LocationUpdatesOptions(platform = FakeUpdatesSlot)

        assertEquals(FakeCurrentSlot, current.platform)
        assertEquals(FakeUpdatesSlot, updates.platform)
        assertEquals(current, current.copy())
    }

    // endregion

    // region checkMinChangeDegrees

    @OptIn(ToolkitInternalApi::class)
    @Test
    fun `the heading filter check rejects negative and non-finite values and accepts zero`() {
        for (bad: Double in listOf(-1.0, -0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("minChangeDegrees $bad") { checkMinChangeDegrees(bad) }
        }
        checkMinChangeDegrees(0.0)
        checkMinChangeDegrees(1.0)
        checkMinChangeDegrees(359.0)
        checkMinChangeDegrees(Double.MIN_VALUE)
    }

    // endregion
}
