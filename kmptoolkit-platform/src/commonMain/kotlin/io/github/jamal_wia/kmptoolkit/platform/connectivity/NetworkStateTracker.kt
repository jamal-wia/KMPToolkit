package io.github.jamal_wia.kmptoolkit.platform.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform-independent half of a [ConnectivityObserver]: turns a stream of per-network
 * up/down events into a single [ConnectivityStatus].
 *
 * It exists separately from the two platform observers so the interesting part — how a set of
 * networks collapses into one status, and when the status is allowed to leave
 * [ConnectivityStatus.UNKNOWN] — is testable without a device, an emulator, or a simulator.
 *
 * **Not thread-safe, by design.** Both platform sources deliver their callbacks serially:
 * Android's `ConnectivityManager.NetworkCallback` runs on one framework handler thread, and
 * `nw_path_monitor` runs on the single dispatch queue it was given. Adding a lock would buy
 * nothing and would have to be held while writing a `StateFlow` that collectors may resume on.
 * Reads of [status] are safe from any thread — that is a `StateFlow` guarantee, not this class's.
 */
internal class NetworkStateTracker {

    private val available: MutableSet<Any> = mutableSetOf()

    private val mutableStatus: MutableStateFlow<ConnectivityStatus> =
        MutableStateFlow(ConnectivityStatus.UNKNOWN)

    /** Current status; starts at [ConnectivityStatus.UNKNOWN] until the first report arrives. */
    val status: StateFlow<ConnectivityStatus> = mutableStatus.asStateFlow()

    /**
     * Records that [network] can carry traffic. Any key that is stable per network works — an
     * Android `Network` handle, or a constant on iOS where the path monitor reports one aggregate
     * path rather than individual interfaces.
     */
    fun onAvailable(network: Any) {
        available.add(network)
        mutableStatus.value = ConnectivityStatus.ONLINE
    }

    /**
     * Records that [network] is gone. The status flips to [ConnectivityStatus.OFFLINE] only once
     * the last known-good network has gone: during a Wi-Fi to cellular handover both are briefly
     * up, and reporting offline in between would produce a flicker that consumers would then have
     * to debounce themselves.
     */
    fun onLost(network: Any) {
        if (!available.remove(network)) return
        if (available.isEmpty()) mutableStatus.value = ConnectivityStatus.OFFLINE
    }

    /**
     * Records that the platform sees no usable network at all — Android's
     * `NetworkCallback.onUnavailable`, or a path status that is not `satisfied`. Clears everything
     * previously reported as available, since the platform has just contradicted it.
     */
    fun onUnavailable() {
        available.clear()
        mutableStatus.value = ConnectivityStatus.OFFLINE
    }

    /**
     * Returns the status to [ConnectivityStatus.UNKNOWN] and forgets every network.
     *
     * Used when the platform stops being a source of truth — registration was refused because the
     * permission is missing, so nothing that follows can be trusted.
     */
    fun onIndeterminate() {
        available.clear()
        mutableStatus.value = ConnectivityStatus.UNKNOWN
    }
}
