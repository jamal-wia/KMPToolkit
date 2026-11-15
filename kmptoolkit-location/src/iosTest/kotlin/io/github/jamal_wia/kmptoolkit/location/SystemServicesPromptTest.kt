package io.github.jamal_wia.kmptoolkit.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The decision half of [withSystemServicesPrompt], against a scripted provider and a counting alert:
 * whether the system alert is asked for at all, and what is answered. Whether iOS then shows it is a
 * device question no test can answer.
 */
class SystemServicesPromptTest {

    private class ScriptedProvider(var enabled: Boolean) : LocationProvider {
        var settingsOpened = 0
        override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading = HERE
        override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = emptyFlow()
        override suspend fun isLocationEnabled(): Boolean = enabled
        override fun observeLocationEnabled(): Flow<Boolean> = flowOf(enabled)
        override fun openLocationSettings() {
            settingsOpened++
        }
    }

    private var raised = 0
    private val alert = ServicesAlert { raised++ }

    @Test
    fun `with the service off the alert is asked for and the answer is PROMPTED`() = runTest {
        val provider = SystemServicesPromptLocationProvider(ScriptedProvider(enabled = false), alert)

        assertEquals(LocationServicePrompt.PROMPTED, provider.promptToEnableService())
        assertEquals(1, raised)
    }

    @Test
    fun `with the service on nothing is asked and the answer is ALREADY_ON`() = runTest {
        val provider = SystemServicesPromptLocationProvider(ScriptedProvider(enabled = true), alert)

        assertEquals(LocationServicePrompt.ALREADY_ON, provider.promptToEnableService())
        assertEquals(0, raised)
    }

    @Test
    fun `every other member is forwarded to the wrapped provider`() = runTest {
        val wrapped = ScriptedProvider(enabled = false)
        val provider = SystemServicesPromptLocationProvider(wrapped, alert)

        assertEquals(HERE, provider.getCurrentLocation())
        assertEquals(false, provider.isLocationEnabled())
        provider.openLocationSettings()
        assertEquals(1, wrapped.settingsOpened)
        assertEquals(listOf(false), provider.observeLocationEnabled().toList())
    }

    @Test
    fun `the public decorator keeps the wrapped provider's answers`() = runTest {
        val wrapped = ScriptedProvider(enabled = true)

        val provider: LocationProvider = wrapped.withSystemServicesPrompt()

        assertEquals(LocationServicePrompt.ALREADY_ON, provider.promptToEnableService())
        assertEquals(HERE, provider.getCurrentLocation())
    }

    private companion object {
        val HERE: LocationReading = LocationReading.Available(LocationFix(GeoCoordinates(latitude = 21.4225, longitude = 39.8262), timestampEpochMillis = 0L))
    }
}
