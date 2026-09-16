package io.github.jamal_wia.kmptoolkit.location

/** What came of asking the system to turn the device's location service back on. */
public enum class LocationServicePrompt {

    /** The service was already on — nothing was asked and nothing needed to be. */
    ALREADY_ON,

    /**
     * The system's own in-place dialog was raised. Whether the user accepted it is not reported
     * here: read [LocationProvider.isLocationEnabled] again once your screen returns to the
     * foreground.
     */
    PROMPTED,

    /**
     * This platform (or this implementation) offers no in-place prompt, so the only route left is
     * [LocationProvider.openLocationSettings].
     */
    UNSUPPORTED,

    /**
     * There is a prompt, but this moment is wrong for it — the caller has no window on screen to
     * raise it over. Nothing was asked and nothing is settled.
     */
    NOT_NOW,
}
