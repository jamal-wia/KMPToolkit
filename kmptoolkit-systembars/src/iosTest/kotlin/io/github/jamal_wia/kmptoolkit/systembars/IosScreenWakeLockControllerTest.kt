package io.github.jamal_wia.kmptoolkit.systembars

import io.github.jamal_wia.kmptoolkit.core.IdleTimerHold
import io.github.jamal_wia.kmptoolkit.core.IdleTimerHolder
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.core.newIdleTimerHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS wake lock over the suite's shared idle-timer holder.
 *
 * Two properties are pinned down here:
 * - The last call to `setKeepScreenOn` decides, whichever thread each call came from.
 * - The wake lock and any other holder of the shared flag (in the app, a playing video surface from
 *   `kmptoolkit-video-player-compose`) cooperate instead of undoing each other.
 *
 * One fake flag and one fake main queue stand in for UIKit, shared by the holder and the controller,
 * so every interleaving is a sequence of statements, not timing.
 */
@OptIn(ToolkitInternalApi::class)
class IosScreenWakeLockControllerTest {

    private class FakeMain {
        var onMainThread: Boolean = true
        val queue: ArrayDeque<() -> Unit> = ArrayDeque()
        var idleTimerDisabled: Boolean = false

        private val isMainThread: () -> Boolean = { onMainThread }
        private val postToMain: (() -> Unit) -> Unit = { block -> queue.addLast(block) }

        /** The holder the wake lock and the "video surface" below both use, as in the app. */
        val holder: IdleTimerHolder = newIdleTimerHolder(
            readFlag = { idleTimerDisabled },
            writeFlag = { disabled -> idleTimerDisabled = disabled },
            isMainThread = isMainThread,
            postToMain = postToMain,
        )

        val controller: IOSScreenWakeLockController = IOSScreenWakeLockController(
            acquireHold = { holder.acquire() },
            isMainThread = isMainThread,
            postToMain = postToMain,
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

    @Test
    fun `repeated holds are one hold so one release lets the screen sleep`() {
        val main = FakeMain()

        main.controller.setKeepScreenOn(true)
        main.controller.setKeepScreenOn(true)
        main.controller.setKeepScreenOn(false)

        assertEquals(false, main.idleTimerDisabled)
    }

    @Test
    fun `a wake-lock release does not let the screen sleep while a video still holds it`() {
        // Video starts, a recording session starts and ends, the video is still playing.
        val main = FakeMain()
        val video: IdleTimerHold = main.holder.acquire()

        main.controller.setKeepScreenOn(true)
        main.controller.setKeepScreenOn(false)

        assertEquals(true, main.idleTimerDisabled, "the screen went to sleep mid-video")
        video.release()
        assertEquals(false, main.idleTimerDisabled)
    }

    @Test
    fun `a video stopping after the wake lock was released does not leave the timer disabled for good`() {
        // The wake lock is on when the video starts, so a save-and-restore surface would save `true`.
        // The wake lock is then released, then the video stops.
        val main = FakeMain()
        main.controller.setKeepScreenOn(true)
        val video: IdleTimerHold = main.holder.acquire()

        main.controller.setKeepScreenOn(false)
        assertEquals(true, main.idleTimerDisabled, "the screen went to sleep mid-video")
        video.release()

        assertEquals(false, main.idleTimerDisabled, "nobody holds the timer any more; it must be enabled again")
    }
}
