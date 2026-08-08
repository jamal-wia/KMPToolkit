package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SystemBarsController.config] as an observable, not just as a snapshot.
 *
 * It is derived from the controller's one atomic state rather than kept beside it, so what a
 * collector sees and what `currentConfig` returns cannot drift — these tests are what says so.
 */
class SystemBarsConfigFlowTest {

    @Test
    fun `a collector receives the current configuration immediately`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)
        val seen: MutableList<SystemBarsConfig> = mutableListOf()

        val job: Job = launch { controller.config.collect { config -> seen += config } }
        yield()

        assertEquals(listOf(SystemBarsConfig.ForDarkBackground), seen)
        job.cancel()
    }

    @Test
    fun `pushing and releasing an override is observed as two changes`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val seen: MutableList<SystemBarsConfig> = mutableListOf()
        val job: Job = launch { controller.config.collect { config -> seen += config } }
        yield()

        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(visibility = SystemBarsVisibility.Immersive),
        )
        yield()
        handle.release()
        yield()

        assertEquals(
            listOf(
                SystemBarsConfig.ForLightBackground,
                SystemBarsConfig.ForLightBackground.copy(visibility = SystemBarsVisibility.Immersive),
                SystemBarsConfig.ForLightBackground,
            ),
            seen,
        )
        job.cancel()
    }

    @Test
    fun `a stack change that leaves the configuration alone is not emitted`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
        val seen: MutableList<SystemBarsConfig> = mutableListOf()
        val job: Job = launch { controller.config.collect { config -> seen += config } }
        yield()

        // A real layer, claiming the value the base already had.
        controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
        yield()

        assertEquals(listOf(SystemBarsConfig.ForLightBackground), seen)
        assertEquals(1, controller.activeOverrideCount)
        job.cancel()
    }

    @Test
    fun `the flow value and currentConfig always agree`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)

        controller.applyOverride(SystemBarsOverride(visibility = SystemBarsVisibility.Hidden))
        assertEquals(controller.currentConfig, controller.config.value)

        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        assertEquals(controller.currentConfig, controller.config.value)
        assertEquals(
            SystemBarsConfig.ForDarkBackground.copy(visibility = SystemBarsVisibility.Hidden),
            controller.config.value,
        )
    }

    @Test
    fun `the replay cache holds exactly the current configuration`() = runTest {
        val controller = RecordingSystemBarsController(SystemBarsConfig.ForDarkBackground)

        assertEquals(listOf(SystemBarsConfig.ForDarkBackground), controller.config.replayCache)
    }
}
