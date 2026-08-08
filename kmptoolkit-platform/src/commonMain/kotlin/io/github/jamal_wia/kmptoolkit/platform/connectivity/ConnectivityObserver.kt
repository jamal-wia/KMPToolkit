package io.github.jamal_wia.kmptoolkit.platform.connectivity

import kotlinx.coroutines.flow.StateFlow

/**
 * Reports network reachability as a live [StateFlow] of [ConnectivityStatus].
 *
 * Obtain one from the platform factory (`createConnectivityObserver(context)` on Android,
 * `createConnectivityObserver()` on iOS) and pass it into shared code as this interface — shared
 * code never names the factory. See `docs/01-architecture.md`.
 *
 * The observer registers with the OS as soon as it is constructed and keeps a platform callback
 * alive until [close] is called, so an app creates **one** and holds it for the process lifetime.
 * Creating one per screen would register one system callback per screen.
 *
 * Nothing here is finer-grained than reachability on purpose: metered, roaming, VPN and
 * "expensive" are all platform-specific enough that a common abstraction over them would either
 * lie on one platform or be a union type nobody can act on.
 */
public interface ConnectivityObserver {

    /**
     * The current status, and every change to it.
     *
     * Starts at [ConnectivityStatus.UNKNOWN] and is replaced by the platform's first report. It is
     * a `StateFlow`, so a late collector immediately receives the current value rather than
     * waiting for the next change, and repeated identical reports are conflated away.
     */
    public val status: StateFlow<ConnectivityStatus>

    /**
     * Unregisters the platform callback and stops updating [status].
     *
     * Idempotent: calling it more than once is a no-op. After it returns, [status] keeps its last
     * value forever — it does not reset to [ConnectivityStatus.UNKNOWN], because the last known
     * answer is more useful than no answer, and a closed observer is one nobody should still be
     * collecting.
     */
    public fun close()
}
