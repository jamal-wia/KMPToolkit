package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverrideHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [SystemBarsController] that records every configuration it would have pushed to a
 * platform window.
 *
 * Use it to assert what a screen *claims* — that a photo viewer asks for light icons and gives them
 * back on the way out, that a reader's fullscreen toggle leaves no layer behind — without a real
 * activity, window or view controller. The layering is faithful: overrides stack, the newest wins a
 * shared axis, an override claims only the axes it names, and releasing one restores whatever is
 * underneath it *at that moment* rather than whatever was there when it was pushed.
 *
 * ```kotlin
 * val controller = RecordingSystemBarsController()
 * val screen = PhotoViewerPresenter(controller)
 *
 * screen.onEnter()
 * assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)
 *
 * screen.onLeave()
 * assertEquals(0, controller.activeOverrideCount)
 * ```
 *
 * Not thread-safe, deliberately: the real controller's compare-and-set retry loop exists for
 * concurrent writers, and reproducing it here would mean a fixture with its own concurrency bugs.
 * Drive it from one thread, as a unit test does.
 */
public class RecordingSystemBarsController(
    initialConfig: SystemBarsConfig = SystemBarsConfig(),
) : SystemBarsController {

    private data class Layer(val id: Long, val override: SystemBarsOverride)

    private var base: SystemBarsConfig = initialConfig
    private var overrides: List<Layer> = emptyList()
    private var nextId: Long = 1L

    private val state: MutableStateFlow<SystemBarsConfig> = MutableStateFlow(initialConfig)
    override val config: StateFlow<SystemBarsConfig> = state.asStateFlow()

    private val mutableApplied: MutableList<SystemBarsConfig> = mutableListOf()

    /**
     * Every configuration that actually reached the platform, oldest first.
     *
     * A mutation that leaves the effective configuration unchanged records nothing, matching the
     * real controller — which is what makes this list usable for asserting that a screen does not
     * thrash the window.
     */
    public val applied: List<SystemBarsConfig> get() = mutableApplied.toList()

    /** The number of live override layers. Assert it is back to zero after a screen is torn down. */
    public val activeOverrideCount: Int get() = overrides.size

    override fun setBaseConfig(config: SystemBarsConfig) {
        updateBaseConfig { config }
    }

    override fun updateBaseConfig(transform: (SystemBarsConfig) -> SystemBarsConfig) {
        base = transform(base)
        publish()
    }

    override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle {
        val id: Long = nextId++
        overrides = overrides + Layer(id, override)
        publish()
        return Handle(id)
    }

    override fun release() {
        base = SystemBarsConfig()
        overrides = emptyList()
        publish()
    }

    /** Forgets every recorded application. Leaves the base and the live layers alone. */
    public fun clear() {
        mutableApplied.clear()
    }

    private inner class Handle(private val id: Long) : SystemBarsOverrideHandle {

        override fun update(override: SystemBarsOverride) {
            val index: Int = overrides.indexOfFirst { layer -> layer.id == id }
            if (index < 0) return
            // Replaced at its index rather than removed and appended: the layer keeps the
            // precedence it was pushed with.
            overrides = overrides.toMutableList().also { list -> list[index] = Layer(id, override) }
            publish()
        }

        override fun release() {
            val remaining: List<Layer> = overrides.filterNot { layer -> layer.id == id }
            if (remaining.size == overrides.size) return
            overrides = remaining
            publish()
        }
    }

    private fun publish() {
        val effective: SystemBarsConfig = overrides.fold(base) { config, layer ->
            layer.override.foldOnto(config)
        }
        if (effective == state.value) return
        state.value = effective
        mutableApplied += effective
    }
}

/**
 * The fold the real controller performs internally, restated against the public API.
 *
 * The production version is `internal`, so this fixture cannot call it. Keep the two in step: an
 * override claims exactly the axes it names and leaves the rest of [config] as they were.
 */
private fun SystemBarsOverride.foldOnto(config: SystemBarsConfig): SystemBarsConfig = SystemBarsConfig(
    statusBarIcons = statusBarIcons ?: config.statusBarIcons,
    navigationBarIcons = navigationBarIcons ?: config.navigationBarIcons,
    visibility = visibility ?: config.visibility,
)
