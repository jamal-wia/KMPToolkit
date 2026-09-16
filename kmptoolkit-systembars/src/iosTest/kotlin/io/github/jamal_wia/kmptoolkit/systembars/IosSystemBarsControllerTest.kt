package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

class IosSystemBarsControllerTest {

    @Test
    fun `the home indicator is shown by default`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        assertFalse(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `hiding the navigation bar hides the home indicator`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(
            SystemBarsConfig(visibility = SystemBarsVisibility(isNavigationBarVisible = false)),
        )

        assertTrue(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `the immersive preset hides the home indicator`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Immersive))

        assertTrue(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `hiding only the status bar leaves the home indicator alone`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(
            SystemBarsConfig(visibility = SystemBarsVisibility(isStatusBarVisible = false)),
        )

        assertTrue(controller.prefersStatusBarHidden)
        assertFalse(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `an override hides the home indicator and releasing it brings it back`() {
        val controller: IosSystemBarsController = createSystemBarsController()
        val handle: SystemBarsOverrideHandle =
            controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))

        assertTrue(controller.prefersHomeIndicatorAutoHidden)

        handle.release()

        assertFalse(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `the icon style does not affect the home indicator`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))

        assertEquals(UIStatusBarStyleLightContent, controller.preferredStatusBarStyle)
        assertFalse(controller.prefersHomeIndicatorAutoHidden)
    }

    @Test
    fun `the status bar style follows the icon style`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.DarkIcons))
        assertEquals(UIStatusBarStyleDarkContent, controller.preferredStatusBarStyle)

        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))
        assertEquals(UIStatusBarStyleLightContent, controller.preferredStatusBarStyle)
    }

    @Test
    fun `applying a configuration without a host does not throw`() {
        val controller: IosSystemBarsController = createSystemBarsController()

        controller.setBaseConfig(SystemBarsConfig(visibility = SystemBarsVisibility.Immersive))

        assertTrue(controller.prefersHomeIndicatorAutoHidden)
    }
}
