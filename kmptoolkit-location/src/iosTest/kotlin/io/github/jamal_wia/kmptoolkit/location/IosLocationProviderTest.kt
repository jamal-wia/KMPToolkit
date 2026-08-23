package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * A real `CLLocationManager` needs a granted authorization to ever produce a fix, which a headless
 * test run cannot arrange, so [LocationProvider.getCurrentLocation] and
 * [LocationProvider.observeLocation] are not exercised here. [LocationProvider.isLocationEnabled]
 * only reads the device-wide service toggle — no authorization needed — so it is safe to call for
 * real; this test passes as long as the call completes without throwing.
 */
class IosLocationProviderTest {

    @Test
    fun `isLocationEnabled completes without throwing`() = runTest {
        val provider: LocationProvider = createLocationProvider()

        provider.isLocationEnabled()
    }
}
