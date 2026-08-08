package io.github.jamal_wia.kmptoolkit.platform.connectivity

import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkStateTrackerTest {

    @Test
    fun `starts unknown before the platform reports anything`() {
        assertEquals(ConnectivityStatus.UNKNOWN, NetworkStateTracker().status.value)
    }

    @Test
    fun `first available network makes it online`() {
        val tracker = NetworkStateTracker()

        tracker.onAvailable("wifi")

        assertEquals(ConnectivityStatus.ONLINE, tracker.status.value)
    }

    @Test
    fun `losing the only network makes it offline`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")

        tracker.onLost("wifi")

        assertEquals(ConnectivityStatus.OFFLINE, tracker.status.value)
    }

    @Test
    fun `stays online while a second network survives the handover`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")

        tracker.onLost("wifi")

        assertEquals(ConnectivityStatus.ONLINE, tracker.status.value)
    }

    @Test
    fun `goes offline only once the last network of several is lost`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")
        tracker.onLost("wifi")

        tracker.onLost("cellular")

        assertEquals(ConnectivityStatus.OFFLINE, tracker.status.value)
    }

    @Test
    fun `losing an unknown network changes nothing`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")

        tracker.onLost("ethernet")

        assertEquals(ConnectivityStatus.ONLINE, tracker.status.value)
    }

    @Test
    fun `losing a network that was never available leaves the status unknown`() {
        val tracker = NetworkStateTracker()

        tracker.onLost("wifi")

        assertEquals(ConnectivityStatus.UNKNOWN, tracker.status.value)
    }

    @Test
    fun `the same network reported available twice is one network`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")
        tracker.onAvailable("wifi")

        tracker.onLost("wifi")

        assertEquals(ConnectivityStatus.OFFLINE, tracker.status.value)
    }

    @Test
    fun `unavailable forgets every network and reports offline`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cellular")

        tracker.onUnavailable()

        assertEquals(ConnectivityStatus.OFFLINE, tracker.status.value)
        // Nothing is left to lose, so a stale loss must not be able to change anything.
        tracker.onLost("wifi")
        assertEquals(ConnectivityStatus.OFFLINE, tracker.status.value)
    }

    @Test
    fun `indeterminate returns to unknown when the platform stops being a source of truth`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")

        tracker.onIndeterminate()

        assertEquals(ConnectivityStatus.UNKNOWN, tracker.status.value)
    }

    @Test
    fun `recovers to online after coming back from offline`() {
        val tracker = NetworkStateTracker()
        tracker.onAvailable("wifi")
        tracker.onLost("wifi")

        tracker.onAvailable("cellular")

        assertEquals(ConnectivityStatus.ONLINE, tracker.status.value)
    }
}
