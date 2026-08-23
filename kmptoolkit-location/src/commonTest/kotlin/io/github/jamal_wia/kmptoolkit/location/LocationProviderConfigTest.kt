package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers [LocationProviderConfig]'s validation. A bad value here does not fail loudly at
 * observation time — it produces a provider that either throttles updates away entirely or hangs
 * a single-fix request forever, so the constructor is where these get caught.
 */
class LocationProviderConfigTest {

    @Test
    fun `default config throttles nothing and times out after thirty seconds`() {
        val config = LocationProviderConfig()

        assertEquals(0f, config.minUpdateDistanceMeters)
        assertEquals(0L, config.minUpdateIntervalMillis)
        assertEquals(30_000L, config.singleFixTimeoutMillis)
    }

    @Test
    fun `a negative min update distance is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LocationProviderConfig(minUpdateDistanceMeters = -1f)
        }
    }

    @Test
    fun `a negative min update interval is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LocationProviderConfig(minUpdateIntervalMillis = -1L)
        }
    }

    @Test
    fun `a zero or negative single fix timeout is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            LocationProviderConfig(singleFixTimeoutMillis = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            LocationProviderConfig(singleFixTimeoutMillis = -1L)
        }
    }
}
