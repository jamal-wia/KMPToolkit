package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.connectivity.ConnectivityObserver
import io.github.jamal_wia.kmptoolkit.platform.connectivity.ConnectivityStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [ConnectivityObserver] a test drives by hand.
 *
 * Lets you write the transitions that are otherwise impossible to stage — going offline mid-request
 * and coming back, or never learning the status at all because the app forgot
 * `ACCESS_NETWORK_STATE`:
 *
 * ```kotlin
 * val connectivity = FakeConnectivityObserver()
 * val sync = Sync(connectivity)
 * connectivity.emit(ConnectivityStatus.OFFLINE)
 * assertTrue(sync.isPaused)
 * ```
 *
 * @param initial the starting status. Defaults to [ConnectivityStatus.UNKNOWN], matching what a
 *   real observer reports before the platform's first callback — start from
 *   [ConnectivityStatus.ONLINE] only if the code under test is meant to skip that phase.
 */
public class FakeConnectivityObserver(
    initial: ConnectivityStatus = ConnectivityStatus.UNKNOWN,
) : ConnectivityObserver {

    private val mutableStatus: MutableStateFlow<ConnectivityStatus> = MutableStateFlow(initial)

    override val status: StateFlow<ConnectivityStatus> = mutableStatus.asStateFlow()

    /**
     * How many times [close] has been called.
     *
     * Assert on it to prove that whatever owns the observer really does release it — a leaked
     * system callback is invisible in production until it is not.
     */
    public var closeCount: Int = 0
        private set

    /** Whether [close] has been called at least once. */
    public val isClosed: Boolean get() = closeCount > 0

    /**
     * Publishes [status] to collectors.
     *
     * Works after [close] as well, on purpose: a test that asserts a collector *stopped* reacting
     * needs to be able to push a change that should be ignored.
     */
    public fun emit(status: ConnectivityStatus) {
        mutableStatus.value = status
    }

    override fun close() {
        closeCount++
    }
}
