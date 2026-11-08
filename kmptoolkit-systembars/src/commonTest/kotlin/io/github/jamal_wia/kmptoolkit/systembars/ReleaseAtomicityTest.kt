package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `release()` is one atomic transition like every other: a push that lands while it runs is either
 * kept or wiped as a whole, and the id sequence is never rewound — a handle taken before the
 * teardown must stay dead, or it could later release another layer.
 *
 * The race is landed at an exact point: the layer flow reports a read and then runs a hook, so the
 * competing push happens between `release()`'s read of the state and its write.
 */
class ReleaseAtomicityTest {

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class HookedFlow<T>(private val inner: MutableStateFlow<T>) : MutableStateFlow<T> by inner {
        var afterNextRead: (() -> Unit)? = null

        override var value: T
            get() {
                val read: T = inner.value
                afterNextRead?.let { hook ->
                    afterNextRead = null
                    hook()
                }
                return read
            }
            set(value) {
                inner.value = value
            }
    }

    @Test
    fun `a push landing during release does not make its id available again`() {
        lateinit var flow: HookedFlow<LayeredSystemBarsController.Layers>
        val controller = RecordingSystemBarsController(
            createLayersFlow = { initial: LayeredSystemBarsController.Layers ->
                HookedFlow(MutableStateFlow(initial)).also { flow = it }
            },
        )
        lateinit var raced: SystemBarsOverrideHandle
        flow.afterNextRead = { raced = controller.applyOverride(SystemBarsOverride.None) }

        controller.release()
        // A later layer; the raced handle's id must not be the one it was handed.
        controller.applyOverride(SystemBarsOverride.None)
        raced.release()

        assertEquals(1, controller.activeOverrideCount, "the stale handle took the later layer with it")
    }
}
