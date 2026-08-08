package io.github.jamal_wia.kmptoolkit.platform.connectivity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.StateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.DISPATCH_QUEUE_PRIORITY_BACKGROUND
import platform.darwin.dispatch_get_global_queue

/** The single key the tracker needs: `nw_path_monitor` reports one aggregate path, not interfaces. */
private const val DEFAULT_PATH: String = "nw_path"

/**
 * Creates the iOS [ConnectivityObserver], backed by the Network framework's `nw_path_monitor`.
 *
 * Create one per process and hold it. Call [ConnectivityObserver.close] when you are done — it
 * cancels the monitor, which otherwise keeps a handler alive for the life of the process.
 *
 * Updates are delivered on a background-QoS global queue: reachability changes are not
 * latency-critical, and taking the main queue for them would put a system callback ahead of the
 * UI. The resulting [ConnectivityObserver.status] is a `StateFlow`, so collectors observe it from
 * wherever they like regardless.
 *
 * iOS needs no entitlement or permission for this.
 */
@OptIn(ExperimentalForeignApi::class)
public fun createConnectivityObserver(): ConnectivityObserver = IosConnectivityObserver()

@OptIn(ExperimentalForeignApi::class)
private class IosConnectivityObserver : ConnectivityObserver {

    private val tracker = NetworkStateTracker()
    override val status: StateFlow<ConnectivityStatus> = tracker.status

    private val monitor = nw_path_monitor_create()
    private var closed: Boolean = false

    init {
        nw_path_monitor_set_queue(
            monitor,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_BACKGROUND.toLong(), 0u),
        )
        nw_path_monitor_set_update_handler(monitor) { path ->
            // `satisfied` is the only status that means traffic can flow. `unsatisfied` and
            // `requiresConnection` both mean it cannot right now, which is what OFFLINE says.
            if (nw_path_get_status(path) == nw_path_status_satisfied) {
                tracker.onAvailable(DEFAULT_PATH)
            } else {
                tracker.onUnavailable()
            }
        }
        nw_path_monitor_start(monitor)
    }

    override fun close() {
        if (closed) return
        closed = true
        nw_path_monitor_cancel(monitor)
    }
}
