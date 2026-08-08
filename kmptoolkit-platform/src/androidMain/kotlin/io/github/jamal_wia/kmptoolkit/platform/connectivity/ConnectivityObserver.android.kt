package io.github.jamal_wia.kmptoolkit.platform.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates the Android [ConnectivityObserver], backed by `ConnectivityManager.NetworkCallback` on
 * every network that reports internet capability and has passed validation.
 *
 * Create one per process and hold it; call [ConnectivityObserver.close] if the process outlives
 * your need for it. Only the application context is retained, so passing an `Activity` here is
 * harmless.
 *
 * The app must declare `android.permission.ACCESS_NETWORK_STATE` itself — this library declares no
 * permission, on purpose. Without it, registration is refused and
 * [ConnectivityObserver.status] stays [ConnectivityStatus.UNKNOWN] for the life of the observer
 * instead of throwing a `SecurityException` out of a constructor. It is a normal install-time
 * permission with no runtime prompt; see `docs/kmptoolkit-platform/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a refused registration is reported. Defaults to discarding it — the typed
 *   [ConnectivityStatus.UNKNOWN] is the API-level signal; the log line is for the developer who
 *   forgot the permission.
 */
public fun createConnectivityObserver(
    context: Context,
    logger: Logger = NoopLogger,
): ConnectivityObserver = AndroidConnectivityObserver(context.applicationContext, logger)

private class AndroidConnectivityObserver(
    context: Context,
    private val logger: Logger,
) : ConnectivityObserver {

    private val tracker = NetworkStateTracker()
    override val status: StateFlow<ConnectivityStatus> = tracker.status

    private val manager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            tracker.onAvailable(network)
        }

        override fun onLost(network: Network) {
            tracker.onLost(network)
        }

        override fun onUnavailable() {
            tracker.onUnavailable()
        }
    }

    /**
     * `false` when registration never happened, so [close] does not try to unregister a callback
     * the system has no record of — which throws `IllegalArgumentException`.
     */
    private var registered: Boolean = false

    private var closed: Boolean = false

    init {
        registered = register()
        if (!registered) tracker.onIndeterminate()
    }

    /**
     * @return whether the system accepted the registration.
     */
    // The permission is genuinely missing from this library's manifest, and that is the design:
    // declaring it here would merge it into every consumer's app (see 05-platform-notes.md). The
    // SecurityException lint is warning about is caught below and turned into a permanent
    // ConnectivityStatus.UNKNOWN, which is the documented contract — so the condition lint exists
    // to prevent is exactly the one this function handles.
    @SuppressLint("MissingPermission")
    private fun register(): Boolean {
        val service: ConnectivityManager = manager ?: return false
        val request: NetworkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // VALIDATED, not just INTERNET: an unvalidated network is one that has an interface
            // but failed the captive-portal probe. Reporting it as ONLINE is how an app ends up
            // retrying requests against a hotel Wi-Fi login page.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        return runCatching {
            service.registerNetworkCallback(request, callback)
            true
        }.getOrElse { cause ->
            // SecurityException when ACCESS_NETWORK_STATE is missing; RuntimeException when the
            // system service is unreachable. Neither is worth crashing a constructor over.
            logger.w(cause) {
                "Could not register the network callback — status stays UNKNOWN. " +
                    "Does the app declare android.permission.ACCESS_NETWORK_STATE?"
            }
            false
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!registered) return
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }
}
