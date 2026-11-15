package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Creates the desktop JVM [LocationProvider]. No desktop location source exists in this library,
 * so it answers honestly through the types, never by pretending:
 *
 * - [LocationProvider.getCurrentLocation] answers `Unavailable(UNSUPPORTED)`;
 * - [LocationProvider.observeLocation] emits `Unavailable(UNSUPPORTED)` and completes (the stream
 *   ends after that reason on every platform);
 * - [LocationProvider.isLocationEnabled] is `false`, and [LocationProvider.observeLocationEnabled]
 *   emits `false` once and then stays open (a state stream never ends on its own, and the state
 *   never changes here);
 * - [LocationProvider.promptToEnableService] answers [LocationServicePrompt.UNSUPPORTED];
 * - [LocationProvider.openLocationSettings] does nothing.
 *
 * There is no IP-based fallback: that needs a third-party service, is a privacy question and is
 * city-grade at best. The point of this factory is that shared code (the geo math, every common
 * type, a `LocationProvider` parameter) runs on the desktop unchanged and gets the lifecycle right
 * instead of every app hand-writing the same stub. A future desktop engine can implement
 * [LocationProvider] itself, as a separate artifact.
 *
 * No `logger` parameter (this module's jvm target has no logging dependency), no options, and no
 * state: every call answers the same, from any thread, and nothing is registered or held, so there
 * is nothing to release.
 */
public fun createLocationProvider(): LocationProvider = UnsupportedLocationProvider

/** The desktop provider: stateless, so one instance serves every caller and every thread. */
private object UnsupportedLocationProvider : LocationProvider {

    private val unsupported: LocationReading = LocationReading.Unavailable(LocationUnavailability.UNSUPPORTED)

    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading = unsupported

    // Cold: each collection emits the reason and completes; nothing is registered.
    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = flowOf(unsupported)

    override suspend fun isLocationEnabled(): Boolean = false

    // Emits the (only) state at once and stays open until cancelled, like the state streams of the
    // other platforms; nothing is registered, so cancelling has nothing to release.
    override fun observeLocationEnabled(): Flow<Boolean> = flow {
        emit(false)
        awaitCancellation()
    }

    override fun openLocationSettings() {
        // No settings screen to open on a desktop JVM; never throws.
    }

    override suspend fun promptToEnableService(): LocationServicePrompt = LocationServicePrompt.UNSUPPORTED
}
