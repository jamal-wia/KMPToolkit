package io.github.jamal_wia.kmptoolkit.platform.wakelock

import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [ScreenWakeLock], backed by `UIApplication.idleTimerDisabled`.
 *
 * There is no per-window state to reapply as on Android: `idleTimerDisabled` is a property of the
 * shared application that the system does not reset on its own, so setting it once holds until you
 * clear it — which is exactly why [ScreenWakeLock.setKeepScreenOn] insists that you do.
 *
 * The write is dispatched to the main queue, since `UIApplication` is main-thread-only. It
 * therefore always reports [WakeLockResult.APPLIED]: there is no window to be missing, and the
 * property cannot fail. No permission or entitlement is required.
 */
public fun createScreenWakeLock(): ScreenWakeLock = IosScreenWakeLock

private object IosScreenWakeLock : ScreenWakeLock {

    override fun setKeepScreenOn(enabled: Boolean): WakeLockResult {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.idleTimerDisabled = enabled
        }
        return WakeLockResult.APPLIED
    }
}
