package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSThread
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController

/**
 * The `hostViewController` sentences of `IosSystemBarsController` and the iOS platform notes: a configuration
 * change asks the host to refresh its status-bar appearance, a host that is assigned later reads the tracked
 * configuration on its first query, and a released controller forgets its host and never asks it again.
 * The invalidation is counted by a host that overrides `setNeedsStatusBarAppearanceUpdate`.
 */
class IosHostViewControllerTest {

    private class CountingHost : UIViewController(nibName = null, bundle = null) {
        var invalidations: Int = 0
        override fun setNeedsStatusBarAppearanceUpdate() {
            invalidations++
            super.setNeedsStatusBarAppearanceUpdate()
        }
    }

    @Test
    fun `a configuration change asks the host to refresh its status bar`() {
        assertTrue(NSThread.isMainThread(), "the test relies on the main-thread path of the controller")
        val controller: IosSystemBarsController = createSystemBarsController()
        val host = CountingHost()
        controller.hostViewController = host

        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))
        val afterFirst: Int = host.invalidations
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertTrue(afterFirst >= 1, "no invalidation after the first change")
        assertTrue(host.invalidations > afterFirst, "no invalidation after the second change")
        assertEquals(UIStatusBarStyleDarkContent, controller.preferredStatusBarStyle)
    }

    @Test
    fun `a host assigned after the change reads the tracked configuration on its first query`() {
        val controller: IosSystemBarsController = createSystemBarsController()
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))

        controller.hostViewController = CountingHost()

        assertEquals(UIStatusBarStyleLightContent, controller.preferredStatusBarStyle)
    }

    @Test
    fun `a released controller forgets its host and never asks it again`() {
        val controller: IosSystemBarsController = createSystemBarsController()
        val host = CountingHost()
        controller.hostViewController = host
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))
        val beforeRelease: Int = host.invalidations

        controller.release()
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertNull(controller.hostViewController)
        assertEquals(beforeRelease, host.invalidations, "a released controller still invalidated its old host")
    }

    @Test
    fun `replacing the host moves the invalidation to the new one`() {
        val controller: IosSystemBarsController = createSystemBarsController()
        val first = CountingHost()
        val second = CountingHost()
        controller.hostViewController = first
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.LightIcons))
        val firstSeen: Int = first.invalidations

        controller.hostViewController = second
        controller.setBaseConfig(SystemBarsConfig(statusBarIcons = SystemBarIconStyle.DarkIcons))

        assertEquals(firstSeen, first.invalidations)
        assertTrue(second.invalidations >= 1)
    }
}
