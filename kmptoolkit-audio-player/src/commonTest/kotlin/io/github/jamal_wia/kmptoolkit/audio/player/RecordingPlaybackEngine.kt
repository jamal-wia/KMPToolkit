package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Minimal scriptable [PlaybackEngine] for the tests in this module.
 *
 * Intentionally *not* the published `FakePlaybackEngine` from `kmptoolkit-audio-player-testing`:
 * that module depends on this one, so depending on it from here would be a project cycle. This
 * double stays deliberately small — it records what the player asked for and lets a test drive the
 * playhead and the listener; anything a consumer would want from a fixture belongs in the published
 * one instead.
 */
internal class RecordingPlaybackEngine(
    var duration: Long = DEFAULT_DURATION_MS,
) : PlaybackEngine {

    var listener: PlaybackEngineListener? = null
        private set

    var position: Long = 0L
    var loadFailure: Throwable? = null
    var loadDelayMs: Long = 0L

    /** Virtual time a cancelled load takes to unwind, like a platform handle that is slow to free. */
    var unwindDelayMs: Long = 0L

    /**
     * When non-`null`, a load that is cancelled throws this instead of the cancellation — the way an
     * engine whose platform reports "released while loading" as an ordinary error behaves.
     */
    var failureWhenCancelled: Throwable? = null

    /** Loads currently running, and the most that ever ran at once — the player promises one. */
    var activeLoads: Int = 0
        private set
    var maxConcurrentLoads: Int = 0
        private set

    val loadedSources: MutableList<AudioSource> = mutableListOf()
    val seekTargets: MutableList<Long> = mutableListOf()
    var started: Int = 0
    var paused: Int = 0
    var appliedSpeed: Float = 1.0f
    var releaseCount: Int = 0

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: AudioSource) {
        loadedSources += source
        activeLoads++
        maxConcurrentLoads = maxOf(maxConcurrentLoads, activeLoads)
        try {
            if (loadDelayMs > 0L) {
                try {
                    delay(loadDelayMs)
                } catch (cancellation: CancellationException) {
                    throw failureWhenCancelled ?: cancellation
                }
            }
            loadFailure?.let { failure: Throwable -> throw failure }
            position = 0L
        } finally {
            if (unwindDelayMs > 0L) withContext(NonCancellable) { delay(unwindDelayMs) }
            activeLoads--
        }
    }

    override fun start() {
        started++
    }

    override fun pause() {
        paused++
    }

    override fun seekTo(positionMs: Long) {
        seekTargets += positionMs
        position = positionMs
    }

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed
    }

    override fun durationMs(): Long = duration

    override fun positionMs(): Long = position

    override fun release() {
        releaseCount++
        position = 0L
    }

    private companion object {
        const val DEFAULT_DURATION_MS: Long = 10_000L
    }
}
