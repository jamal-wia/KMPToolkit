package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * Runs [block] as one model test: a fresh [VirtualFxHost] on one thread and virtual time, the shared
 * player's poller parked on [NeverDispatcher], no JavaFX toolkit and no real media. Nothing in the block
 * waits for real time, so the test is deterministic by construction; the only real time is the hang bound
 * of [FxModelScope.load].
 *
 * When the block ends, whether it passed or failed, the checks of [FxModelScope.afterModel] run, and so
 * does the built-in one: the model's [NeverDispatcher] must not have been given anything but the position
 * poller's launch. A failing block keeps its own failure; a failing check is added to it as suppressed.
 *
 * Use as `@Test fun aNameWithoutCommas() = fxModel { ... }`.
 *
 * @param epochNanos the host's clock before any time passes; see [VirtualFxHost].
 * @param seed seeds every random choice of the model; the default is fixed, see [VirtualFxHost].
 * @param jitterMaxMs the most the host adds to an `after` delay; 0 (the default) turns the jitter off.
 */
internal fun fxModel(
    epochNanos: Long = VirtualFxHost.DEFAULT_EPOCH_NANOS,
    seed: Long = VirtualFxHost.defaultSeed(),
    jitterMaxMs: Double = 0.0,
    block: FxModelScope.() -> Unit,
) {
    NeverDispatcher.clear()
    val scope: FxModelScope = FxModelScope(VirtualFxHost(epochNanos, seed, jitterMaxMs))
    var failure: Throwable? = null
    try {
        scope.block()
    } catch (t: Throwable) {
        failure = t
    }
    for (check: Pair<String, () -> Unit> in scope.checksAfter()) {
        try {
            check.second()
        } catch (t: Throwable) {
            if (failure == null) failure = t else failure.addSuppressed(t)
        }
    }
    if (failure != null) throw failure
}

/** What a [fxModel] block can use: the host and the helpers that drive it. */
internal class FxModelScope internal constructor(
    /** The model's FX thread, clock and queue. */
    val host: VirtualFxHost,
) {

    private val afterChecks: MutableList<Pair<String, () -> Unit>> = mutableListOf()

    /** [VirtualFxHost.onFx]. */
    fun <T> onFx(block: () -> T): T = host.onFx(block)

    /** [VirtualFxHost.step]. */
    fun step(): Boolean = host.step()

    /** [VirtualFxHost.advanceBy]. */
    fun advanceBy(millis: Long) = host.advanceBy(millis)

    /** [VirtualFxHost.runUntilIdle]. */
    fun runUntilIdle(limitMs: Long = VirtualFxHost.DEFAULT_IDLE_LIMIT_MS) = host.runUntilIdle(limitMs)

    /** [VirtualFxHost.beforeEachTask]. */
    fun beforeEachTask(hook: ((taskIndex: Int) -> Unit)?) = host.beforeEachTask(hook)

    /**
     * Builds the shared player over [engine] on [NeverDispatcher]: its poller never ticks, so a model test
     * reads the engine and the native model, never `Playing.currentPosition`.
     */
    fun videoPlayer(engine: VideoPlaybackEngine, config: VideoPlayerConfig = VideoPlayerConfig()): VideoPlayer =
        createVideoPlayer(engine, config, NeverDispatcher)

    /**
     * Registers [check] to run when the block ends, after it and in registration order, whether it passed or
     * failed; for the monitors a model part brings (the hazard monitor of the scripted player is wired
     * this way). [name] is only for the reader of the code.
     */
    fun afterModel(name: String, check: () -> Unit) {
        afterChecks += name to check
    }

    /**
     * Loads [source] into [engine] the way a caller on another thread does, driving the host until the
     * load completes: `engine.load` runs on a coroutine of this thread, its blocking part on IO, and every
     * block it hands to the FX thread runs when [runUntilIdle] drains the host.
     *
     * The wait for the load is real time, because the IO hop is: [boundMs] is only a hang bound, and a load
     * that has not finished by then fails with a message that carries the host's state and seed.
     *
     * @throws AssertionError when the load has not finished within [boundMs] of real time.
     */
    fun load(engine: VideoPlaybackEngine, source: VideoSource, boundMs: Long = LOAD_BOUND_MS) {
        require(boundMs > 0L) { "boundMs must be positive, was $boundMs" }
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
        val deadline: Long = System.nanoTime() + boundMs * NANOS_PER_MILLI
        runBlocking {
            val loading = async { engine.load(source) }
            while (!loading.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a load whose IO hop is real
                if (!loading.isCompleted && System.nanoTime() - deadline > 0L) {
                    loading.cancel()
                    throw AssertionError("load did not finish within $boundMs ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            loading.await()
        }
    }

    internal fun checksAfter(): List<Pair<String, () -> Unit>> =
        afterChecks.toList() + ("NeverDispatcher saw only the position poller" to ::assertOnlyThePollerDispatched)

    private fun assertOnlyThePollerDispatched() {
        val foreign: List<NeverDispatcher.Dispatch> = NeverDispatcher.foreignDispatches
        if (foreign.isNotEmpty()) {
            throw AssertionError(
                "NeverDispatcher was given ${foreign.size} dispatch(es) that are not the position poller's launch, " +
                    "so the model test is no longer sound (see its KDoc): $foreign ($host)",
            )
        }
    }

    private companion object {
        const val LOAD_BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
