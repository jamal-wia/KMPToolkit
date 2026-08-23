package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.LocationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [LocationProvider] a test drives by hand — no GPS chip, no location permission, no
 * `CLLocationManager` delegate.
 *
 * Lets you write the transitions that are otherwise impossible to stage — a fix arriving after the
 * screen is already showing, or the location service going off mid-session:
 *
 * ```kotlin
 * val location = FakeLocationProvider()
 * val presenter = MapPresenter(location)
 * assertEquals(MapState.NoFix, presenter.state)
 *
 * location.emit(GeoCoordinates(latitude = 21.4225, longitude = 39.8262))
 * assertEquals(MapState.Centered, presenter.state)
 * ```
 *
 * @param current what [getCurrentLocation] returns and what [observeLocation] starts from.
 *   Defaults to `null` — no fix yet, matching a fresh real provider before the first callback.
 * @param locationEnabled what [isLocationEnabled] returns until changed.
 */
public class FakeLocationProvider(
    current: GeoCoordinates? = null,
    public var locationEnabled: Boolean = true,
) : LocationProvider {

    private val mutableLocation: MutableStateFlow<GeoCoordinates?> = MutableStateFlow(current)

    /** How many times [openLocationSettings] has been called. */
    public var openSettingsCount: Int = 0
        private set

    /**
     * Publishes [coordinates] as the current fix: the next [getCurrentLocation] call returns it,
     * and every [observeLocation] collector receives it immediately.
     */
    public fun emit(coordinates: GeoCoordinates?) {
        mutableLocation.value = coordinates
    }

    override suspend fun getCurrentLocation(): GeoCoordinates? = mutableLocation.value

    override fun observeLocation(): Flow<GeoCoordinates?> = mutableLocation.asStateFlow()

    override suspend fun isLocationEnabled(): Boolean = locationEnabled

    override fun openLocationSettings() {
        openSettingsCount++
    }
}
