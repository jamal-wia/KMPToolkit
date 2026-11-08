package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsVisibility
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The real controller, once released, never touches the platform again whatever is written to it
 * (`SystemBarsController.release`: "the controller never touches the window afterwards"). The recording
 * double documents its `applied` list as "every configuration that actually reached the platform" and
 * promises the same layering, so a presenter test that keeps writing after teardown must see an empty
 * list, as it would see no window write in production.
 */
class RecordingReleasedStaysOffPlatformTest {

    private fun released(): RecordingSystemBarsController {
        val controller = RecordingSystemBarsController()
        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))
        controller.release()
        controller.clear()
        return controller
    }

    @Test
    fun `applyOverride after release records nothing`() {
        val controller: RecordingSystemBarsController = released()
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        assertEquals(emptyList(), controller.applied)
        assertEquals(SystemBarIconStyle.LightIcons, controller.config.value.statusBarIcons, "state follows, the platform does not")
    }

    @Test
    fun `updateBaseConfig after release records nothing`() {
        val controller: RecordingSystemBarsController = released()
        controller.updateBaseConfig { SystemBarsConfig.ForDarkBackground }
        assertEquals(emptyList(), controller.applied)
        assertEquals(SystemBarsConfig.ForDarkBackground, controller.config.value, "state follows, the platform does not")
    }
}
