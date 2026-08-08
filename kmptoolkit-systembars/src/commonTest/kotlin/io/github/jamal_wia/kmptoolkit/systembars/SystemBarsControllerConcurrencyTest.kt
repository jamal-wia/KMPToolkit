package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The controller's state transitions under real parallelism.
 *
 * System-bar state has several writers by construction — a theme that follows the OS, a screen
 * entering fullscreen, a coroutine reacting to a network event — and they do not agree in advance
 * about which thread they are on. The failure this guards against is not a crash: it is a lost
 * axis, where two writers each read the same state, each write their own half of it, and one half
 * silently disappears. Every test here would pass against a non-atomic implementation *sometimes*,
 * which is why they all run a large number of iterations across
 * [Dispatchers.Default].
 */
class SystemBarsControllerConcurrencyTest {

    @Test
    fun `two writers updating different axes of the base lose neither`() = runTest {
        repeat(REPEATS) {
            val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
            controller.recordApplications = false

            withContext(Dispatchers.Default) {
                // Alternating rather than repeating one value: a repeated write settles after the
                // first iteration and never contends again, which would make this test pass by
                // never exercising anything. Both loops end on LightIcons.
                val status = async {
                    repeat(ITERATIONS) { i ->
                        controller.updateBaseConfig { base -> base.copy(statusBarIcons = alternating(i)) }
                    }
                }
                val navigation = async {
                    repeat(ITERATIONS) { i ->
                        controller.updateBaseConfig { base -> base.copy(navigationBarIcons = alternating(i)) }
                    }
                }
                listOf(status, navigation).awaitAll()
            }

            assertEquals(
                SystemBarsConfig.ForDarkBackground,
                controller.currentConfig,
                "both writers set their axis to LightIcons; a lost update leaves one of them dark",
            )
        }
    }

    @Test
    fun `a base writer and an override writer do not lose each other's axis`() = runTest {
        repeat(REPEATS) {
            val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
            controller.recordApplications = false

            withContext(Dispatchers.Default) {
                val theme = async {
                    repeat(ITERATIONS) { i ->
                        controller.updateBaseConfig { base -> base.copy(navigationBarIcons = alternating(i)) }
                    }
                }
                val screen = async {
                    repeat(ITERATIONS) {
                        controller.applyOverride(
                            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
                        ).release()
                    }
                }
                listOf(theme, screen).awaitAll()
            }

            assertEquals(0, controller.activeOverrideCount, "every override was released")
            assertEquals(
                SystemBarIconStyle.LightIcons,
                controller.currentConfig.navigationBarIcons,
                "the theme's axis must survive a screen pushing and dropping overrides beside it",
            )
            assertEquals(
                SystemBarIconStyle.DarkIcons,
                controller.currentConfig.statusBarIcons,
                "the released overrides must leave the base's status bar showing",
            )
        }
    }

    @Test
    fun `concurrent pushes all land on the stack`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        controller.recordApplications = false

        val handles: List<SystemBarsOverrideHandle> = withContext(Dispatchers.Default) {
            List(CONCURRENT_PUSHES) {
                async {
                    controller.applyOverride(
                        SystemBarsOverride(visibility = SystemBarsVisibility.Immersive),
                    )
                }
            }.awaitAll()
        }

        // A push that read a stale stack and wrote it back would drop whatever landed in between.
        assertEquals(CONCURRENT_PUSHES, controller.activeOverrideCount)
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)

        withContext(Dispatchers.Default) {
            handles.map { handle -> async { handle.release() } }.awaitAll()
        }

        // Every handle addressed a distinct layer — had two shared an id, the count could not
        // reach zero.
        assertEquals(0, controller.activeOverrideCount)
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
    }

    @Test
    fun `concurrent releases of the same handle remove exactly one layer`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        controller.recordApplications = false
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons),
        )
        val other: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(visibility = SystemBarsVisibility.Immersive),
        )

        withContext(Dispatchers.Default) {
            List(CONCURRENT_PUSHES) { async { handle.release() } }.awaitAll()
        }

        assertEquals(1, controller.activeOverrideCount)
        assertEquals(SystemBarsVisibility.Immersive, controller.currentConfig.visibility)
        other.release()
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)
    }

    private companion object {
        const val REPEATS = 20

        /** Even, so that [alternating] ends on [SystemBarIconStyle.LightIcons]. */
        const val ITERATIONS = 500
        const val CONCURRENT_PUSHES = 64

        fun alternating(iteration: Int): SystemBarIconStyle =
            if (iteration % 2 == 0) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons
    }
}
