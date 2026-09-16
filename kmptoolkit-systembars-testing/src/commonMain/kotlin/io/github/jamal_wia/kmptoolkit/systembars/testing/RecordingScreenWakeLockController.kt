package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.ScreenWakeLockController

/**
 * Test double for [ScreenWakeLockController] that records every [setKeepScreenOn] call.
 *
 * Intended for unit tests asserting that a screen's wiring toggles the wake lock at the right
 * moments (session start/stop, screen teardown) without needing a real Activity or `UIApplication`.
 */
public class RecordingScreenWakeLockController : ScreenWakeLockController {

    private val mutableCalls: MutableList<Boolean> = mutableListOf()

    /** Every value passed to [setKeepScreenOn] so far, oldest first. */
    public val calls: List<Boolean> get() = mutableCalls.toList()

    /** The value from the most recent [setKeepScreenOn] call, or `false` if none has happened yet. */
    public val isKeptOn: Boolean get() = mutableCalls.lastOrNull() ?: false

    override fun setKeepScreenOn(enabled: Boolean) {
        mutableCalls.add(enabled)
    }

    /** Forgets every recorded call. */
    public fun clear() {
        mutableCalls.clear()
    }
}
