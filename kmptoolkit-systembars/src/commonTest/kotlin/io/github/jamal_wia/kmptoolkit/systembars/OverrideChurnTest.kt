package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sixty-four workers pushing, updating and releasing overrides while another one releases the whole
 * controller: no exception, no reused id, no layer left behind, and a handle taken before a
 * controller release stays dead.
 */
class OverrideChurnTest {

    @Test
    fun `sixty four workers beside a controller release lose no id and leave no layer`() = runTest {
        repeat(ROUNDS) { round ->
            lateinit var layers: MutableStateFlow<LayeredSystemBarsController.Layers>
            val controller = RecordingSystemBarsController(
                createLayersFlow = { initial: LayeredSystemBarsController.Layers ->
                    MutableStateFlow(initial).also { flow -> layers = flow }
                },
            )
            controller.recordApplications = false

            val perWorker: List<Int> = withContext(Dispatchers.Default) {
                val releaser = async {
                    repeat(RELEASES) { controller.release() }
                    0
                }
                val workers = List(WORKERS) { worker ->
                    async {
                        repeat(ITERATIONS) { i ->
                            val handle: SystemBarsOverrideHandle = controller.applyOverride(
                                SystemBarsOverride(
                                    statusBarIcons = if ((worker + i) % 2 == 0) {
                                        SystemBarIconStyle.DarkIcons
                                    } else {
                                        SystemBarIconStyle.LightIcons
                                    },
                                ),
                            )
                            handle.update(SystemBarsOverride(visibility = SystemBarsVisibility.Immersive))
                            handle.release()
                        }
                        ITERATIONS
                    }
                }
                (workers + releaser).awaitAll()
            }

            assertEquals(0, controller.activeOverrideCount, "round $round: a layer was left behind")
            assertEquals(
                1L + perWorker.sum(),
                layers.value.nextId,
                "round $round: an id was reused or skipped",
            )
            controller.release()
            assertEquals(SystemBarsConfig(), controller.currentConfig)
        }
    }

    @Test
    fun `a handle taken before the controller release can neither update nor resurrect a layer`() {
        val controller = RecordingSystemBarsController()
        val handle: SystemBarsOverrideHandle = controller.applyOverride(
            SystemBarsOverride(visibility = SystemBarsVisibility.Immersive),
        )
        controller.release()
        val appliedBefore: Int = controller.applied.size

        handle.update(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.LightIcons))
        handle.release()

        assertEquals(0, controller.activeOverrideCount)
        assertEquals(SystemBarsConfig(), controller.currentConfig)
        assertEquals(appliedBefore, controller.applied.size, "a dead handle reached the platform")
    }

    private companion object {
        const val ROUNDS = 10
        const val WORKERS = 64
        const val ITERATIONS = 200
        const val RELEASES = 400
    }
}
