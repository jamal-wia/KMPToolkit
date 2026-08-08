package io.github.jamal_wia.kmptoolkit.systembars

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The platform-independent half of every [SystemBarsController]: the layer stack, the atomic
 * transitions over it, and the decision of when the platform actually has to be touched.
 *
 * Everything here is pure Kotlin and has no Compose or platform dependency, which is deliberate —
 * the interesting behaviour (per-axis ownership, restore-by-removal, no lost update under
 * concurrency) is exactly the part that is worth testing without a device, and it is tested in
 * `commonTest`.
 *
 * A subclass supplies [applyToPlatform] and nothing else.
 */
internal abstract class LayeredSystemBarsController(
    initialConfig: SystemBarsConfig,
) : SystemBarsController {

    /**
     * One pushed override plus the identity that lets its handle find it again.
     *
     * An identity is needed rather than a positional index because layers are removed out of
     * order: an override released while three later ones are still live must take *its* layer with
     * it and leave theirs where they are.
     */
    private data class Layer(val id: Long, val override: SystemBarsOverride)

    private data class Layers(val base: SystemBarsConfig, val overrides: List<Layer>) {
        /** Base with every live override folded on top, oldest first, so the newest layer wins. */
        val effective: SystemBarsConfig
            get() = overrides.fold(base) { config, layer -> layer.override.applyTo(config) }
    }

    /**
     * The single source of truth. Both the base and the stack live in one atomic value precisely
     * so that a mutation of either is one compare-and-set, and [config] is derived from it rather
     * than maintained alongside it — two state holders updated in sequence can be observed
     * disagreeing, and under two writers can end up permanently stale.
     */
    private val layers: MutableStateFlow<Layers> = MutableStateFlow(
        Layers(base = initialConfig, overrides = emptyList()),
    )

    override val config: StateFlow<SystemBarsConfig> = MappedStateFlow(layers) { it.effective }

    /** The number of live override layers. Exposed for tests, which assert that none leak. */
    internal val activeOverrideCount: Int
        get() = layers.value.overrides.size

    /**
     * Pushes [config] onto the platform. Called on every change of the effective configuration,
     * and never for a change that produced an identical configuration.
     *
     * Implementations must tolerate being called from any thread and must not assume a window
     * exists yet.
     */
    protected abstract fun applyToPlatform(config: SystemBarsConfig)

    /**
     * Pushes the current configuration onto the platform unconditionally, bypassing the
     * "nothing changed" check.
     *
     * The check is an optimisation about *this controller's* state, and a freshly created window
     * has none of it: an Android activity recreated by a rotation starts at platform defaults
     * while the controller still holds the state the old window had. Nothing changed, and yet
     * everything has to be applied again.
     */
    protected fun reapplyToPlatform() {
        applyToPlatform(currentConfig)
    }

    final override fun setBaseConfig(config: SystemBarsConfig) {
        updateBaseConfig { config }
    }

    final override fun updateBaseConfig(transform: (SystemBarsConfig) -> SystemBarsConfig) {
        mutate { current -> current.copy(base = transform(current.base)) }
    }

    final override fun applyOverride(override: SystemBarsOverride): SystemBarsOverrideHandle {
        var assignedId = 0L
        mutate { current ->
            // Derived inside the transform, so a retry against a newer stack picks a fresh id: two
            // threads pushing at once cannot be handed the same one, because only one of them wins
            // the compare-and-set and the other recomputes.
            assignedId = (current.overrides.maxOfOrNull(Layer::id) ?: 0L) + 1L
            current.copy(overrides = current.overrides + Layer(assignedId, override))
        }
        return Handle(assignedId)
    }

    override fun release() {
        layers.value = Layers(base = SystemBarsConfig(), overrides = emptyList())
    }

    private inner class Handle(private val id: Long) : SystemBarsOverrideHandle {

        override fun update(override: SystemBarsOverride) {
            mutate { current ->
                val index: Int = current.overrides.indexOfFirst { layer -> layer.id == id }
                if (index < 0) {
                    current
                } else {
                    // Replaced at its index, not removed and appended: the layer keeps the
                    // precedence it was pushed with.
                    current.copy(
                        overrides = current.overrides.toMutableList().also { list ->
                            list[index] = Layer(id, override)
                        },
                    )
                }
            }
        }

        override fun release() {
            mutate { current ->
                current.copy(overrides = current.overrides.filterNot { layer -> layer.id == id })
            }
        }
    }

    /**
     * Applies [transform] to the layer stack atomically, then pushes the result to the platform if
     * the effective configuration actually changed.
     *
     * The retry loop is what keeps a concurrent writer from losing an axis: a mutation that reads a
     * stack another thread has already replaced simply runs again against the new one. [transform]
     * therefore has to be pure, which every call site in this class is.
     */
    private fun mutate(transform: (Layers) -> Layers) {
        while (true) {
            val current: Layers = layers.value
            val next: Layers = transform(current)
            if (next == current) return
            if (layers.compareAndSet(current, next)) {
                // Read back rather than using `next`: under two writers the freshest value is the
                // one worth applying, and applying a value already superseded only to have the
                // other writer apply it again is wasted platform work.
                val effective: SystemBarsConfig = layers.value.effective
                if (effective != current.effective) applyToPlatform(effective)
                return
            }
        }
    }
}

/**
 * A read-only view of [source] through [transform], as a `StateFlow` rather than a cold `Flow`.
 *
 * It exists so that the controller can keep one atomic state holder and still publish a
 * `StateFlow<SystemBarsConfig>`. The alternative — a second `MutableStateFlow` written after every
 * mutation — is not equivalent: two threads can complete their mutations in one order and their
 * publications in the other, leaving the published value permanently behind the real one.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {

    override val value: R
        get() = transform(source.value)

    override val replayCache: List<R>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        // Unreachable: collecting a StateFlow never completes. Present only because a StateFlow's
        // collect is declared to return Nothing.
        awaitCancellation()
    }
}
