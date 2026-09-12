package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverrideHandle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract parity with the real controller.
 *
 * The fixture re-implements the layer fold against the public API because the production one is
 * `internal`, so these cases deliberately mirror `SystemBarsControllerTest` in the main module: if
 * the two ever disagree, a consumer's test passes against a controller that behaves differently from
 * the one they ship.
 */
class RecordingSystemBarsControllerTest {

    @Test
    fun `the initial configuration is the one the fixture was created with`() {
        val initial = SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons)

        val controller = RecordingSystemBarsController(initial)

        assertEquals(initial, controller.currentConfig)
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `setting the base config publishes and records it`() {
        val controller = RecordingSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
        assertEquals(listOf(SystemBarsConfig.ForDarkBackground), controller.applied)
    }

    @Test
    fun `setting the same base config again records nothing`() {
        val controller = RecordingSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)

        assertEquals(1, controller.applied.size)
    }

    @Test
    fun `an override claims only the axes it names`() {
        val controller = RecordingSystemBarsController(
            SystemBarsConfig(
                statusBarIcons = SystemBarIconStyle.DarkIcons,
                navigationBarIcons = SystemBarIconStyle.DarkIcons,
            ),
        )

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
    }

    @Test
    fun `an unclaimed axis keeps following the base while an override is live`() {
        val controller = RecordingSystemBarsController()
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        controller.updateBaseConfig { it.copy(navigationBarIcons = SystemBarIconStyle.LightIcons) }

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons)
    }

    @Test
    fun `on a shared axis the override pushed last wins`() {
        val controller = RecordingSystemBarsController()

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `an empty override changes nothing and records nothing`() {
        val controller = RecordingSystemBarsController()

        controller.applyOverride(SystemBarsOverride.None)

        assertEquals(emptyList(), controller.applied)
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `releasing an override restores the base as it is now not as it was when pushed`() {
        val controller = RecordingSystemBarsController()
        val handle: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        handle.release()

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `releasing the lower of two overrides on the same axis leaves the upper one in charge`() {
        val controller = RecordingSystemBarsController()
        val lower: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        lower.release()

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `releasing twice is a no-op`() {
        val controller = RecordingSystemBarsController()
        val handle: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        handle.release()
        val afterFirst: Int = controller.applied.size
        handle.release()

        assertEquals(afterFirst, controller.applied.size)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `updating a layer keeps its position in the stack`() {
        val controller = RecordingSystemBarsController()
        val lower: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        lower.update(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons, visibility = SystemBarsVisibility.Immersive))

        // The upper layer still owns the icons; the lower one only gained an axis nobody else claims.
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
    }

    @Test
    fun `updating a released layer is a no-op`() {
        val controller = RecordingSystemBarsController()
        val handle: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        handle.release()
        val afterRelease: Int = controller.applied.size

        handle.update(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertEquals(afterRelease, controller.applied.size)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `an override that does not change the effective config records nothing`() {
        val controller = RecordingSystemBarsController(
            SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons),
        )

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `releasing the controller drops every override`() {
        val controller = RecordingSystemBarsController()
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        controller.release()

        assertEquals(0, controller.activeOverrideCount)
        assertEquals(SystemBarsConfig(), controller.currentConfig)
    }

    @Test
    fun `config flow reports the effective configuration`() {
        val controller = RecordingSystemBarsController()

        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        assertEquals(SystemBarsVisibility.Immersive, controller.config.value.visibility)
    }

    @Test
    fun `clear forgets recorded applications without touching the live state`() {
        val controller = RecordingSystemBarsController()
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        controller.clear()

        assertEquals(emptyList(), controller.applied)
        assertEquals(1, controller.activeOverrideCount)
        assertTrue(controller.currentConfig.statusBarIcons == SystemBarIconStyle.LightIcons)
    }
}
