package io.github.jamal_wia.kmptoolkit.platform.connectivity

/**
 * What the platform currently reports about the device's ability to reach the network.
 *
 * Three states, not a `Boolean`, because "we do not know" is a real and common condition — the
 * observer has just been created and the OS has not called back yet, or the Android app never
 * declared `android.permission.ACCESS_NETWORK_STATE` and the system refuses to tell us anything.
 * Collapsing that into `false` would make every consumer show an offline state for a device that
 * is perfectly online, which is exactly the bug this type exists to prevent.
 *
 * None of these values is a promise that a request will succeed: a validated network can still be
 * behind a captive portal that changed its mind, and a server can be down. Treat [ONLINE] as
 * "worth trying", not as "will work".
 */
public enum class ConnectivityStatus {

    /**
     * At least one network is up and the platform considers it usable for internet traffic.
     *
     * On Android this means a network with `NET_CAPABILITY_INTERNET` **and**
     * `NET_CAPABILITY_VALIDATED` — it passed the captive-portal probe. On iOS it means the
     * `nw_path` status is `satisfied`.
     */
    ONLINE,

    /**
     * The platform reported that no usable network is available.
     *
     * This is an actual report, not an assumption: the observer never starts in this state, and
     * never falls back to it when it cannot determine the truth — that is [UNKNOWN].
     */
    OFFLINE,

    /**
     * The observer has no answer yet, or cannot get one at all.
     *
     * Two distinct situations produce it, and a consumer usually treats both the same way (assume
     * reachable, let the request fail if it must):
     * - **Not yet reported.** Every observer starts here and stays here until the platform's first
     *   callback arrives, typically within milliseconds of construction.
     * - **Permanently unavailable.** On Android, the consuming app did not declare
     *   `android.permission.ACCESS_NETWORK_STATE`, so registering the network callback was
     *   refused. The status then stays [UNKNOWN] for the life of the observer. See
     *   `docs/kmptoolkit-platform/05-platform-notes.md`.
     */
    UNKNOWN,
}
