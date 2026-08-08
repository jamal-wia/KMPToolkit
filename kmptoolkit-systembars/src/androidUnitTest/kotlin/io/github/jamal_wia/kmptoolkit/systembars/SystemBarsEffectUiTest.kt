package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SystemBarsEffect]'s composition contract, run on the JVM through Robolectric: a claim exists for
 * exactly as long as the composable that made it, it survives a recomposition, and leaving hands
 * the axes back to whatever is underneath *at that moment*.
 *
 * These use the real controller with the platform call stubbed out, so what is under test is the
 * effect plus the layer stack it drives — the pair that has to be right for a back-navigation not
 * to leave the wrong status bar behind.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SystemBarsEffectUiTest {

    @Test
    fun `the override is claimed while the composable is in composition`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)

        setContent {
            SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        }
        waitForIdle()

        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `leaving composition releases the claim and restores what was underneath`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
        var onScreen by mutableStateOf(true)

        setContent {
            if (onScreen) {
                SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
            }
        }
        waitForIdle()
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        onScreen = false
        waitForIdle()

        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
        assertEquals(0, controller.activeOverrideCount)
    }

    @Test
    fun `leaving composition restores the base as it is then not as it was on entry`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
        var onScreen by mutableStateOf(true)

        setContent {
            if (onScreen) {
                SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
            }
        }
        waitForIdle()

        // The theme flips while the screen is still up.
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        onScreen = false
        waitForIdle()

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
    }

    @Test
    fun `two screens claiming different axes do not disturb each other`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
        var innerOnScreen by mutableStateOf(true)

        setContent {
            SystemBarsEffect(controller, SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))
            if (innerOnScreen) {
                SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
            }
        }
        waitForIdle()
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        innerOnScreen = false
        waitForIdle()

        // The outer claim is untouched by the inner one going away.
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        assertEquals(1, controller.activeOverrideCount)
    }

    @Test
    fun `the later composed effect wins a shared axis and hands it back on leaving`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
        var innerOnScreen by mutableStateOf(true)

        setContent {
            SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
            if (innerOnScreen) {
                SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
            }
        }
        waitForIdle()
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)

        innerOnScreen = false
        waitForIdle()

        // This is the case a snapshot-and-restore effect gets wrong: it would write back the state
        // it saw on entry, which is the base, and the outer screen's claim would vanish with it.
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `changing the override updates the claim in place without overtaking a later one`() =
        runComposeUiTest {
            val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
            var outerVisibility by mutableStateOf(SystemBarsVisibility.Visible)

            setContent {
                SystemBarsEffect(
                    controller,
                    SystemBarsOverride(
                        statusBarIcons = SystemBarIconStyle.LightIcons,
                        visibility = outerVisibility,
                    ),
                )
                SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
            }
            waitForIdle()
            assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)

            outerVisibility = SystemBarsVisibility.Immersive
            waitForIdle()

            assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
            assertEquals(
                SystemBarIconStyle.DarkIcons,
                controller.currentConfig.statusBarIcons,
                "the outer effect must not jump above the inner one just because it changed",
            )
            assertEquals(2, controller.activeOverrideCount)
        }

    @Test
    fun `a recomposition that changes nothing does not add a second claim`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)
        var tick by mutableStateOf(0)

        setContent {
            // Reading `tick` here is what makes the content recompose; the override it produces is
            // deliberately identical every time.
            val style: SystemBarIconStyle =
                if (tick >= 0) SystemBarIconStyle.LightIcons else SystemBarIconStyle.DarkIcons
            SystemBarsEffect(controller, SystemBarsOverride(statusBarIcons = style))
        }
        waitForIdle()

        repeat(3) {
            tick++
            waitForIdle()
        }

        assertEquals(1, controller.activeOverrideCount)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
    }

    @Test
    fun `the named-axis overload claims the axes it is given and no others`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForLightBackground)

        setContent {
            SystemBarsEffect(controller, visibility = SystemBarsVisibility.Hidden)
        }
        waitForIdle()

        assertEquals(SystemBarsVisibility.Hidden, controller.currentConfig.visibility)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.navigationBarIcons)
    }

    @Test
    fun `the dialog effect is inert outside a dialog window`() = runComposeUiTest {
        val controller = StubSystemBarsController(SystemBarsConfig.ForDarkBackground)

        // The activity's own content has no DialogWindowProvider parent; the effect must find
        // nothing to do rather than throw or touch the activity window a second time.
        setContent { DialogWindowSystemBarsEffect(controller) }
        waitForIdle()

        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
    }
}

/** The production controller with the platform call removed. */
private class StubSystemBarsController(
    initialConfig: SystemBarsConfig,
) : LayeredSystemBarsController(initialConfig) {
    override fun applyToPlatform(config: SystemBarsConfig): Unit = Unit
}
