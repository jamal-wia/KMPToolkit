package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals

/** How [AutoSystemBarsIconStyle] hands its decisions to a [SystemBarsOverrideHandle]: real transitions only. */
class PublishStylesTest {

    @Test
    fun `unchanged styles do not touch the controller`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle = controller.applyOverride(SystemBarsOverride.None)

        val published = publishStyles(handle, SystemBarsOverride.None, status = null, nav = null)

        assertEquals(SystemBarsOverride.None, published)
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `a hidden bar leaves its axis alone while the other bar flips`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle = controller.applyOverride(SystemBarsOverride.None)

        val published = publishStyles(
            handle,
            SystemBarsOverride.None,
            status = SystemBarIconStyle.LightIcons,
            nav = null,
        )

        assertEquals(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons), published)
        assertEquals(1, controller.applied.size)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(
            SystemBarsConfig.ForLightBackground.navigationBarIcons,
            controller.currentConfig.navigationBarIcons,
        )
    }

    @Test
    fun `both bars changing is a single combined write`() {
        val controller = RecordingSystemBarsController(
            SystemBarsConfig.ForLightBackground.copy(visibility = SystemBarsVisibility.Immersive),
        )
        val handle = controller.applyOverride(SystemBarsOverride.None)

        val published = publishStyles(
            handle,
            SystemBarsOverride.None,
            status = SystemBarIconStyle.LightIcons,
            nav = SystemBarIconStyle.LightIcons,
        )

        assertEquals(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons, navigationBarIcons = SystemBarIconStyle.LightIcons),
            published,
        )
        assertEquals(1, controller.applied.size)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.navigationBarIcons)
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
    }

    @Test
    fun `no decision for either bar writes nothing`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val handle = controller.applyOverride(SystemBarsOverride.None)

        val published = publishStyles(handle, SystemBarsOverride.None, status = null, nav = null)

        assertEquals(SystemBarsOverride.None, published)
        assertEquals(emptyList(), controller.applied)
    }

    @Test
    fun `repeating the same published override is a no-op`() {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val handle = controller.applyOverride(SystemBarsOverride.None)
        val first = publishStyles(handle, SystemBarsOverride.None, status = SystemBarIconStyle.LightIcons, nav = null)

        publishStyles(handle, first, status = SystemBarIconStyle.LightIcons, nav = null)

        assertEquals(1, controller.applied.size)
    }
}
