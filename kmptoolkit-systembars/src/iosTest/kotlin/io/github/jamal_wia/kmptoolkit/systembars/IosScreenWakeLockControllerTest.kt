package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ordering contract of the iOS wake lock: the last call to `setKeepScreenOn` decides the flag,
 * whichever thread each call came from.
 *
 * A fake main queue stands in for `dispatch_get_main_queue()`, so "a call made off the main thread is
 * still queued when a later main-thread call runs inline" is a sequence of statements, not timing.
 */
class IosScreenWakeLockControllerTest {

    private class FakeMain {
        var onMainThread: Boolean = true
        val queue: ArrayDeque<() -> Unit> = ArrayDeque()
        var idleTimerDisabled: Boolean = false

        val controller: IOSScreenWakeLockController = IOSScreenWakeLockController(
            writeIdleTimerDisabled = { disabled -> idleTimerDisabled = disabled },
            isMainThread = { onMainThread },
            postToMain = { block -> queue.addLast(block) },
        )

        fun offMainThread(block: () -> Unit) {
            onMainThread = false
            block()
            onMainThread = true
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    @Test
    fun `a call on the main thread applies at once`() {
        val main = FakeMain()

        main.controller.setKeepScreenOn(true)

        assertEquals(true, main.idleTimerDisabled)
    }

    @Test
    fun `a call off the main thread applies once the main queue runs`() {
        val main = FakeMain()

        main.offMainThread { main.controller.setKeepScreenOn(true) }
        assertEquals(false, main.idleTimerDisabled, "UIKit is not touched off the main thread")
        main.drain()

        assertEquals(true, main.idleTimerDisabled)
    }

    @Test
    fun `a release made on the main thread is not undone by an earlier hold queued from another thread`() {
        // A session collector off the main thread turns the flag on; the screen's teardown turns it
        // off on the main thread while that hold is still queued. The release is the last call.
        val main = FakeMain()

        main.offMainThread { main.controller.setKeepScreenOn(true) }
        main.controller.setKeepScreenOn(false)
        main.drain()

        assertEquals(false, main.idleTimerDisabled, "the queued hold must not land after the release")
    }

    @Test
    fun `a hold made on the main thread is not undone by an earlier release queued from another thread`() {
        val main = FakeMain()
        main.controller.setKeepScreenOn(true)

        main.offMainThread { main.controller.setKeepScreenOn(false) }
        main.controller.setKeepScreenOn(true)
        main.drain()

        assertEquals(true, main.idleTimerDisabled, "the queued release must not land after the hold")
    }
}
