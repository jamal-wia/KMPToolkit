package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.UIKit.UIViewController
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_t
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * `SystemBarsController.release()` ("a window write already queued to the main thread when release is called is
 * dropped, and the controller never touches the window afterwards"), on iOS, where the write is a block on the
 * main queue. The test thread is the main thread: it queues a write from a global queue, releases, assigns a host
 * AFTER the release, and then services the main queue itself (spinning the run loop up to a FIFO sentinel posted
 * behind the write), so the queued block runs while a host is present. A positive control without release shows
 * that the same sequence does invalidate the host.
 */
class IosQueuedWriteAfterReleaseTest {

    private class CountingHost : UIViewController(nibName = null, bundle = null) {
        var invalidations: Int = 0
        override fun setNeedsStatusBarAppearanceUpdate() {
            invalidations++
            super.setNeedsStatusBarAppearanceUpdate()
        }
    }

    private fun queueWriteFromBackground(controller: SystemBarsController) {
        val queued: dispatch_semaphore_t = dispatch_semaphore_create(0)
        dispatch_async(dispatch_get_global_queue(0, 0u)) {
            controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))
            dispatch_semaphore_signal(queued)
        }
        assertEquals(0L, dispatch_semaphore_wait(queued, dispatch_time(DISPATCH_TIME_NOW, 20_000_000_000L)), "the background write did not return")
    }

    /** Runs the main queue up to a sentinel posted now: every block queued before it has run when this returns. */
    private fun drainMainQueue() {
        var reached = false
        dispatch_async(dispatch_get_main_queue()) { reached = true }
        var turns = 0
        while (!reached) {
            check(turns++ < 2_000) { "the main queue did not reach the sentinel" }
            NSRunLoop.currentRunLoop.runMode(NSDefaultRunLoopMode, beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.01))
        }
    }

    @Test
    fun `control a write queued from another thread invalidates the host when the controller is not released`() {
        assertTrue(NSThread.isMainThread(), "the test is the main thread")
        val controller: IosSystemBarsController = createSystemBarsController()
        val host = CountingHost()
        controller.hostViewController = host
        queueWriteFromBackground(controller)
        drainMainQueue()
        assertTrue(host.invalidations >= 1, "the queued write never reached the host: the harness does not service the main queue")
    }

    @Test
    fun `a write queued before the release never touches a host assigned after it`() {
        assertTrue(NSThread.isMainThread(), "the test is the main thread")
        val controller: IosSystemBarsController = createSystemBarsController()
        queueWriteFromBackground(controller)
        controller.release()
        val host = CountingHost()
        controller.hostViewController = host
        drainMainQueue()
        assertEquals(0, host.invalidations, "a released controller touched the host through a queued write")
    }
}
