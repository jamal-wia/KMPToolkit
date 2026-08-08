package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of the controller's layer model, as stated in
 * `docs/kmptoolkit-systembars/04-api-reference.md`: per-axis ownership, restore by removal rather
 * than by snapshot, and no platform work for a change that changes nothing.
 */
class SystemBarsControllerTest {

    // --- Base configuration -------------------------------------------------------------------

    @Test
    fun `the initial configuration is the one the controller was created with`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `setting the base config publishes and applies it`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.config.value)
        assertEquals(listOf(SystemBarsConfig.ForDarkBackground), controller.applied)
    }

    @Test
    fun `setting the same base config again applies nothing`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.setBaseConfig(SystemBarsConfig.ForLightBackground)
        controller.setBaseConfig(SystemBarsConfig.ForLightBackground.copy())

        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `updateBaseConfig transforms the current base`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.updateBaseConfig { base -> base.copy(statusBarIcons = SystemBarIconStyle.LightIcons) }

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
    }

    @Test
    fun `a re-apply pushes the current configuration even though nothing changed`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)

        controller.forceReapply()

        assertEquals(listOf(SystemBarsConfig.ForDarkBackground), controller.applied)
    }

    // --- Per-axis ownership -------------------------------------------------------------------

    @Test
    fun `an override claims only the axes it names`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
        assertEquals(SystemBarsVisibility.Visible, controller.currentConfig.visibility)
    }

    @Test
    fun `two overrides on different axes both take effect`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        assertEquals(
            SystemBarsConfig(
                statusBarIcons = SystemBarIconStyle.LightIcons,
                navigationBarIcons = SystemBarIconStyle.DarkIcons,
                visibility = SystemBarsVisibility.Immersive,
            ),
            controller.currentConfig,
        )
    }

    @Test
    fun `on a shared axis the override pushed last wins`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `an empty override changes nothing and applies nothing`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.applyOverride(SystemBarsOverride.None)

        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `an unclaimed axis keeps following the base while an override is live`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )

        // The theme flips to a light one: the navigation bar follows it, the status bar does not,
        // because someone else owns that axis right now.
        controller.setBaseConfig(SystemBarsConfig.ForLightBackground)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        // Once the claim is gone the status bar catches up with the theme it missed.
        handle.release()
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
    }

    // --- Restore on release -------------------------------------------------------------------

    @Test
    fun `releasing the only override restores the base`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )

        handle.release()

        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `releasing an override restores the base as it is now not as it was when pushed`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )

        // The theme flips while the screen holding the override is still on screen.
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        handle.release()

        // A snapshot-and-restore implementation would have written the light theme back here.
        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
    }

    @Test
    fun `releasing the lower of two overrides on the same axis leaves the upper one in charge`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val lower: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))

        lower.release()

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `releasing the upper of two overrides on the same axis hands it back to the lower one`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        val upper: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons),
        )

        upper.release()

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `releasing an override on one axis does not disturb an override on another`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val status: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        status.release()

        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `releasing twice is a no-op`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val first: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        val second: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons),
        )

        first.release()
        first.release()

        assertEquals(1, controller.activeOverrideCount)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        second.release()
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
    }

    // --- Updating a live layer ----------------------------------------------------------------

    @Test
    fun `updating a layer keeps its position in the stack`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val lower: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))

        // If update re-pushed instead of replacing in place, the lower layer would overtake the
        // upper one and the status bar would turn light here.
        lower.update(
            SystemBarsOverride(
                statusBarIcons = SystemBarIconStyle.LightIcons,
                navigationBarIcons = SystemBarIconStyle.LightIcons,
            ),
        )

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons)
        assertEquals(2, controller.activeOverrideCount)
    }

    @Test
    fun `updating a layer can give an axis back`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )

        handle.update(SystemBarsOverride.None)

        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `updating a released layer is a no-op`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        handle.release()

        handle.update(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `updating a layer to the same override applies nothing`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val override = SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons)
        val handle: SystemBarsOverrideHandle = controller.applyOverride(override)
        controller.applied.clear()

        handle.update(override)
        handle.update(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `an override that does not change the effective config applies nothing`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        // Claiming an axis with the value it already had is a real change to the stack and no
        // change at all to the platform.
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertEquals(1, controller.activeOverrideCount)
        assertEquals(emptyList(), controller.applied)
    }

    // --- Release ------------------------------------------------------------------------------

    @Test
    fun `releasing the controller drops every override`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        controller.release()

        assertEquals(0, controller.activeOverrideCount)
        assertEquals(SystemBarsConfig(), controller.currentConfig)
    }

    @Test
    fun `releasing the controller twice is a no-op`() {
        val controller = RecordingSystemBarsController()

        controller.release()
        controller.release()

        assertEquals(SystemBarsConfig(), controller.currentConfig)
    }

    // --- Model --------------------------------------------------------------------------------

    @Test
    fun `an override reports whether it claims anything`() {
        assertTrue(SystemBarsOverride.None.isEmpty)
        assertFalse(SystemBarsOverride(visibility = SystemBarsVisibility.Hidden).isEmpty)
        assertFalse(SystemBarsOverride.icons(SystemBarIconStyle.LightIcons).isEmpty)
    }

    @Test
    fun `the icons factory claims both icon axes and not visibility`() {
        val override = SystemBarsOverride.icons(SystemBarIconStyle.LightIcons)

        assertEquals(SystemBarIconStyle.LightIcons, override.statusBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, override.navigationBarIcons)
        assertEquals(null, override.visibility)
    }

    @Test
    fun `the immersive and hidden presets differ only in what a swipe does`() {
        assertFalse(SystemBarsVisibility.Immersive.isStatusBarVisible)
        assertFalse(SystemBarsVisibility.Immersive.isNavigationBarVisible)
        assertEquals(HiddenBarBehavior.SwipeToReveal, SystemBarsVisibility.Immersive.hiddenBarBehavior)

        assertFalse(SystemBarsVisibility.Hidden.isStatusBarVisible)
        assertFalse(SystemBarsVisibility.Hidden.isNavigationBarVisible)
        assertEquals(HiddenBarBehavior.StayHidden, SystemBarsVisibility.Hidden.hiddenBarBehavior)
    }
}
