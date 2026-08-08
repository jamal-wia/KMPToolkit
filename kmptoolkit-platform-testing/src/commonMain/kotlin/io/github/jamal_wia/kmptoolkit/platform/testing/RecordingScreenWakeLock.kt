package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.wakelock.ScreenWakeLock
import io.github.jamal_wia.kmptoolkit.platform.wakelock.WakeLockResult

/**
 * A [ScreenWakeLock] that records every request instead of touching a window.
 *
 * The assertion worth writing with it is the one about release: `assertFalse(wakeLock.isHeld)`
 * after a screen is disposed catches the bug where a feature leaves the user's display on
 * indefinitely, which no other kind of test will notice.
 *
 * @param result what [setKeepScreenOn] returns. Set it to [WakeLockResult.NO_ACTIVE_WINDOW] to
 *   model a backgrounded app.
 */
public class RecordingScreenWakeLock(
    public var result: WakeLockResult = WakeLockResult.APPLIED,
) : ScreenWakeLock {

    private val mutableRequests: MutableList<Boolean> = mutableListOf()

    /**
     * Every value passed to [setKeepScreenOn], in order and unfiltered — repeated identical
     * requests are all recorded, so a test can assert that a caller is *not* spamming the
     * platform as well as that it asked for the right thing.
     */
    public val requests: List<Boolean> get() = mutableRequests.toList()

    /** The last value requested, or `false` if nothing was ever requested. */
    public val isHeld: Boolean get() = mutableRequests.lastOrNull() ?: false

    override fun setKeepScreenOn(enabled: Boolean): WakeLockResult {
        mutableRequests.add(enabled)
        return result
    }
}
