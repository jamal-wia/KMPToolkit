package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.concurrent.Volatile
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
 *
 * @param writeIdleTimerDisabled, [isMainThread], [postToMain] test seams: the defaults are the real
 *   `UIApplication` flag and the real main queue. A unit-test binary has no `UIApplication`, and a
 *   fake queue is what makes "a call queued from another thread runs after a later main-thread
 *   call" reproducible.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IOSScreenWakeLockController(
    private val writeIdleTimerDisabled: (Boolean) -> Unit = { disabled ->
        UIApplication.sharedApplication.idleTimerDisabled = disabled
    },
    private val isMainThread: () -> Boolean = { NSThread.isMainThread() },
    private val postToMain: (() -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_main_queue(), block) },
) : ScreenWakeLockController {

    // What the caller asked for last, recorded on the caller's thread at the call. A call made off
    // the main thread is queued, and a later call on the main thread writes inline, so the queued
    // one runs last. If it wrote its own value, a hold queued by a background collector would land
    // after the screen's main-thread teardown released it, and the screen would never sleep. Every
    // main-thread pass writes this instead, so the last call wins.
    @Volatile
    private var requested: Boolean = false

    override fun setKeepScreenOn(enabled: Boolean) {
        requested = enabled
        if (isMainThread()) applyRequested() else postToMain { applyRequested() }
    }

    /** Main thread only. */
    private fun applyRequested() {
        writeIdleTimerDisabled(requested)
    }
}
