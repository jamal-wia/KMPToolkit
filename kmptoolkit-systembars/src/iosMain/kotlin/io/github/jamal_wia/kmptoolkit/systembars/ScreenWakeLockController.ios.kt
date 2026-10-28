package io.github.jamal_wia.kmptoolkit.systembars

import io.github.jamal_wia.kmptoolkit.core.IdleTimerHold
import io.github.jamal_wia.kmptoolkit.core.SharedIdleTimer
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.concurrent.Volatile
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Creates the iOS [ScreenWakeLockController]. */
@OptIn(ToolkitInternalApi::class) // the constructor's default hold source is the shared holder
public fun createScreenWakeLockController(): ScreenWakeLockController = IOSScreenWakeLockController()

/**
 * iOS implementation via `UIApplication.idleTimerDisabled`, through the suite's process-wide
 * [SharedIdleTimer].
 *
 * The flag is one switch for the whole app, and `kmptoolkit-video-player-compose` keeps it on
 * while a video plays. With both writing it directly, one undid the other: a wake-lock release
 * turned the timer back on mid-video, or a video surface restored a value saved while the wake
 * lock held it. Then the timer stayed disabled for good. This controller instead owns at most one
 * hold on the shared holder. It takes the hold while the caller wants the screen on and gives it
 * back when it does not.
 *
 * Unlike the Android implementation, no activity-tracker-style re-application on window recreation
 * is needed: iOS hosts one process-stable `UIWindow` for the app's whole lifetime, and
 * `idleTimerDisabled` is a plain property the OS never resets on its own.
 *
 * @param acquireHold, [isMainThread], [postToMain] test seams. The defaults are the shared holder
 *   and the real main queue. A fake queue is what makes "a call queued from another thread runs
 *   after a later main-thread call" reproducible.
 */
@OptIn(ToolkitInternalApi::class)
internal class IOSScreenWakeLockController(
    private val acquireHold: () -> IdleTimerHold = { SharedIdleTimer.acquire() },
    private val isMainThread: () -> Boolean = { NSThread.isMainThread() },
    private val postToMain: (() -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_main_queue(), block) },
) : ScreenWakeLockController {

    // What the caller asked for last, recorded on the caller's thread at the call. A call made off
    // the main thread is queued, and a later call on the main thread runs inline, so the queued one
    // runs last. If it applied its own value, a hold queued by a background collector would land
    // after the screen's main-thread teardown released it, and the screen would never sleep. Every
    // main-thread pass applies this instead, so the last call wins.
    @Volatile
    private var requested: Boolean = false

    // The one hold this controller owns while the caller wants the screen on. Main thread only.
    private var hold: IdleTimerHold? = null

    override fun setKeepScreenOn(enabled: Boolean) {
        requested = enabled
        if (isMainThread()) applyRequested() else postToMain { applyRequested() }
    }

    /** Main thread only. */
    private fun applyRequested() {
        val held: IdleTimerHold? = hold
        if (requested) {
            if (held == null) hold = acquireHold()
        } else if (held != null) {
            held.release()
            hold = null
        }
    }
}
