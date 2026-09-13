package io.github.jamal_wia.kmptoolkit.location

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * Wraps this provider so that [LocationProvider.promptToEnableService] raises iOS's own "Turn On
 * Location Services" alert when the service is off.
 *
 * iOS gives an app no way to switch Location Services on and no deep link to the page that does.
 * What it does do is put up its own alert — with a button that goes to the right page — when an
 * app asks for a location while the service is off. The decorated provider makes exactly that ask:
 * a one-shot location request, started for the alert it provokes, whose result is neither wanted
 * nor waited for. It returns [LocationServicePrompt.PROMPTED] once the request is made, and
 * [LocationServicePrompt.ALREADY_ON] without asking anything when the service is already on. Every
 * other member is forwarded to this provider unchanged.
 *
 * ```kotlin
 * val location: LocationProvider = createLocationProvider().withSystemServicesPrompt()
 * ```
 *
 * **Opt-in, because the system decides what the ask shows.** The alert appears only when the app
 * is authorized to use location — for an app that is not yet, a location request shows nothing,
 * and this decorator does not ask for authorization: that request, and its moment, stay yours (see
 * `docs/kmptoolkit-location/05-platform-notes.md`). iOS also stops showing the alert once the user
 * has dismissed it for this app, without telling the app. `PROMPTED` therefore means "asked", not
 * "shown"; re-check [LocationProvider.isLocationEnabled] when your screen becomes active again, and
 * fall back to your own explanation plus [LocationProvider.openLocationSettings] if the service is
 * still off.
 *
 * @return a provider that behaves as this one, apart from [LocationProvider.promptToEnableService].
 * @since 1.4.0
 */
public fun LocationProvider.withSystemServicesPrompt(): LocationProvider =
    SystemServicesPromptLocationProvider(delegate = this, alert = CoreLocationServicesAlert())

/** What [SystemServicesPromptLocationProvider] does to provoke the alert; a seam for its tests. */
internal fun interface ServicesAlert {
    suspend fun raise()
}

internal class SystemServicesPromptLocationProvider(
    private val delegate: LocationProvider,
    private val alert: ServicesAlert,
) : LocationProvider by delegate {

    override suspend fun promptToEnableService(): LocationServicePrompt {
        if (delegate.isLocationEnabled()) return LocationServicePrompt.ALREADY_ON
        alert.raise()
        return LocationServicePrompt.PROMPTED
    }
}

/**
 * Starts a one-shot request on a `CLLocationManager` whose only purpose is the alert iOS raises in
 * front of it.
 *
 * The manager and its delegate are held until the request finishes one way or the other — ARC would
 * otherwise collect them while the alert is still up — and one at a time is enough: a second prompt
 * can only follow the first being answered or dismissed, and starting it replaces the first.
 */
private class CoreLocationServicesAlert : ServicesAlert {

    private var pending: DiscardingDelegate? = null

    override suspend fun raise() {
        withContext(Dispatchers.Main) {
            // Created and started on the main queue, like every manager in this module: CoreLocation
            // delivers callbacks on the run loop of the thread that created the manager.
            val delegate = DiscardingDelegate(onFinished = { finished: DiscardingDelegate ->
                if (pending === finished) pending = null
            })
            val manager = CLLocationManager().apply {
                desiredAccuracy = kCLLocationAccuracyHundredMeters
                this.delegate = delegate
            }
            delegate.manager = manager
            pending = delegate
            manager.requestLocation()
        }
    }
}

private class DiscardingDelegate(
    private val onFinished: (DiscardingDelegate) -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    var manager: CLLocationManager? = null

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        finish(manager)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        finish(manager)
    }

    private fun finish(manager: CLLocationManager) {
        manager.stopUpdatingLocation()
        manager.delegate = null
        this.manager = null
        onFinished(this)
    }
}
