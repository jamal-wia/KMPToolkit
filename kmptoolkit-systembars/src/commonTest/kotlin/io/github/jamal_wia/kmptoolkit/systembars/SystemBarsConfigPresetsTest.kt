package io.github.jamal_wia.kmptoolkit.systembars

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The presets a theme hands to `setBaseConfig`, pinned by value.
 *
 * The naming is the part worth guarding: a preset is named for the *background* it suits, while the
 * icon style is the opposite brightness — which is exactly the inversion a careless edit gets wrong,
 * and it compiles either way.
 */
class SystemBarsConfigPresetsTest {

    @Test
    fun `the light-background preset puts dark icons on both bars and shows them`() {
        assertEquals(
            SystemBarsConfig(
                statusBarIcons = SystemBarIconStyle.DarkIcons,
                navigationBarIcons = SystemBarIconStyle.DarkIcons,
                visibility = SystemBarsVisibility.Visible,
            ),
            SystemBarsConfig.ForLightBackground,
        )
    }

    @Test
    fun `the dark-background preset puts light icons on both bars and shows them`() {
        assertEquals(
            SystemBarsConfig(
                statusBarIcons = SystemBarIconStyle.LightIcons,
                navigationBarIcons = SystemBarIconStyle.LightIcons,
                visibility = SystemBarsVisibility.Visible,
            ),
            SystemBarsConfig.ForDarkBackground,
        )
    }

    @Test
    fun `the default configuration is the light-background preset`() {
        // A controller created without an initialConfig starts here, before the theme writes its
        // base — so this is what the very first frame shows.
        assertEquals(SystemBarsConfig.ForLightBackground, SystemBarsConfig())
    }

    @Test
    fun `visible is the default visibility`() {
        assertEquals(SystemBarsVisibility.Visible, SystemBarsVisibility())
    }
}
