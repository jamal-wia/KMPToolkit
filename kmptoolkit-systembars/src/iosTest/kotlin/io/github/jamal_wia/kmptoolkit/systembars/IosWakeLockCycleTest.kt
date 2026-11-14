package io.github.jamal_wia.kmptoolkit.systembars

import io.github.jamal_wia.kmptoolkit.core.IdleTimerHold
import io.github.jamal_wia.kmptoolkit.core.IdleTimerHolder
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.core.newIdleTimerHolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `setKeepScreenOn(true)`, `false`, `true` again: the controller documents that `true` suppresses the idle timer for
 * as long as it stays `true` and `false` restores it, so the cycle can repeat for the life of the controller. The
 * existing class pins single holds and releases; this one pins the cycle, which is where a hold that is released but
 * remembered would silently stop the screen from staying on. Same fakes as `IosScreenWakeLockControllerTest`.
 */
@OptIn(ToolkitInternalApi::class)
class IosWakeLockCycleTest {

    private class FakeMain {
        var idleTimerDisabled: Boolean = false
        val holder: IdleTimerHolder = newIdleTimerHolder(
            readFlag = { idleTimerDisabled },
            writeFlag = { disabled -> idleTimerDisabled = disabled },
            isMainThread = { true },
            postToMain = { block -> block() },
        )
        val controller: IOSScreenWakeLockController = IOSScreenWakeLockController(
            acquireHold = { holder.acquire() },
            isMainThread = { true },
            postToMain = { block -> block() },
        )
    }

    @Test
    fun `on then off then on holds the screen again`() {
        val main = FakeMain()

        main.controller.setKeepScreenOn(true)
        main.controller.setKeepScreenOn(false)
        assertEquals(false, main.idleTimerDisabled)
        main.controller.setKeepScreenOn(true)

        assertEquals(true, main.idleTimerDisabled, "a second hold after a release did not take")
    }

    @Test
    fun `five hundred cycles end on the last call and leave nothing held after the last release`() {
        val main = FakeMain()

        repeat(500) { index ->
            main.controller.setKeepScreenOn(true)
            assertEquals(true, main.idleTimerDisabled, "cycle $index: on")
            main.controller.setKeepScreenOn(false)
            assertEquals(false, main.idleTimerDisabled, "cycle $index: off")
        }
        main.controller.setKeepScreenOn(true)
        assertEquals(true, main.idleTimerDisabled)
    }

    @Test
    fun `cycles beside a video that keeps its own hold never let the screen sleep before the video ends`() {
        val main = FakeMain()
        val video: IdleTimerHold = main.holder.acquire()

        repeat(50) {
            main.controller.setKeepScreenOn(true)
            main.controller.setKeepScreenOn(false)
            assertEquals(true, main.idleTimerDisabled, "the video still holds the timer")
        }
        video.release()

        assertEquals(false, main.idleTimerDisabled)
    }
}
