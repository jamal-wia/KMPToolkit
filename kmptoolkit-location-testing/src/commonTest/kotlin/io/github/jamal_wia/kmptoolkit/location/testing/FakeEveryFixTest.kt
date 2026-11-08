package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** `emit` is documented as handing the fix to every `observeLocation` collector; a repeated fix is still a fix. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeEveryFixTest {

    private val here = GeoCoordinates(latitude = 21.4225, longitude = 39.8262)

    @Test
    fun `a fix equal to the previous one is delivered to the collector again`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        val seen = mutableListOf<GeoCoordinates?>()
        val job = launch { provider.observeLocation().toList(seen) }
        provider.emit(here)
        provider.emit(here)
        provider.emit(null)
        provider.emit(null)
        job.cancel()
        assertEquals(listOf(null, here, here, null, null), seen)
    }

    @Test
    fun `a collector that starts after an emit gets the fix first and the current location is the last emitted`() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeLocationProvider()
        provider.emit(here)
        val seen = mutableListOf<GeoCoordinates?>()
        val job = launch { provider.observeLocation().toList(seen) }
        job.cancel()
        assertEquals(listOf<GeoCoordinates?>(here), seen)
        assertEquals(here, provider.getCurrentLocation())
        provider.emit(null)
        assertEquals(null, provider.getCurrentLocation())
    }
}
