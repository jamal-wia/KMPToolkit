package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Creates the iOS [ScreenWakeLockController]. */
public fun createScreenWakeLockController(): ScreenWakeLockController = IOSScreenWakeLockController()

/**
 * iOS implementation via `UIApplication.idleTimerDisabled`.
 *
 * `UIApplication` is main-thread-only, like every UIKit type, so the write is dispatched to the
 * main queue. Unlike the Android implementation, no activity-tracker-style re-application on window
 * recreation is needed: iOS hosts one process-stable `UIWindow` for the app's whole lifetime, and
 * `idleTimerDisabled` is a plain property the OS never resets on its own.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSScreenWakeLockController : ScreenWakeLockController {

    override fun setKeepScreenOn(enabled: Boolean) {
        onMainThread { UIApplication.sharedApplication.idleTimerDisabled = enabled }
    }

    private inline fun onMainThread(crossinline block: () -> Unit) {
        if (NSThread.isMainThread()) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }
}
