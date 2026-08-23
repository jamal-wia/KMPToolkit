package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeLocationProviderTest {

    private val here = GeoCoordinates(latitude = 21.4225, longitude = 39.8262)

    @Test
    fun `starts with no fix by default`() = runTest {
        val provider = FakeLocationProvider()

        assertNull(provider.getCurrentLocation())
        assertNull(provider.observeLocation().first())
    }

    @Test
    fun `starts from the coordinates it was given`() = runTest {
        val provider = FakeLocationProvider(current = here)

        assertEquals(here, provider.getCurrentLocation())
    }

    @Test
    fun `emitting updates both getCurrentLocation and observeLocation`() = runTest {
        val provider = FakeLocationProvider()

        provider.emit(here)

        assertEquals(here, provider.getCurrentLocation())
        assertEquals(here, provider.observeLocation().first())
    }

    @Test
    fun `emitting null clears the fix`() = runTest {
        val provider = FakeLocationProvider(current = here)

        provider.emit(null)

        assertNull(provider.getCurrentLocation())
    }

    @Test
    fun `defaults to the location service being on`() = runTest {
        assertTrue(FakeLocationProvider().isLocationEnabled())
    }

    @Test
    fun `a changed service state is visible to the next read`() = runTest {
        val provider = FakeLocationProvider()

        provider.locationEnabled = false

        assertFalse(provider.isLocationEnabled())
    }

    @Test
    fun `counts how many times openLocationSettings was called`() {
        val provider = FakeLocationProvider()
        assertEquals(0, provider.openSettingsCount)

        provider.openLocationSettings()
        provider.openLocationSettings()

        assertEquals(2, provider.openSettingsCount)
    }
}
