package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals

/** After `release()` the controller never records another platform apply, whichever entry point is used. */
class ReleasedControllerStaysOffPlatformTest {

    private fun released(): RecordingSystemBarsController {
        val controller = RecordingSystemBarsController()
        controller.updateBaseConfig { it.copy(visibility = SystemBarsVisibility.Hidden) }
        controller.release()
        controller.applied.clear()
        return controller
    }

    @Test
    fun `updateBaseConfig after release does not reach the platform`() {
        val controller: RecordingSystemBarsController = released()
        controller.updateBaseConfig { SystemBarsConfig.ForDarkBackground }
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `applyOverride after release does not reach the platform`() {
        val controller: RecordingSystemBarsController = released()
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `a resume style reapply after release does not reach the platform`() {
        val controller: RecordingSystemBarsController = released()
        controller.forceReapply()
        assertEquals(emptyList(), controller.applied)
    }
}
