package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Minimal scriptable [VideoPlaybackEngine] for the tests in this module.
 *
 * Intentionally *not* the published `FakeVideoPlaybackEngine` from
 * `kmptoolkit-video-player-testing`: that module depends on this one, so using it here would be a
 * project cycle. This double records what the player asked for and lets a test drive the playhead
 * and the listener.
 */
internal class RecordingVideoPlaybackEngine(
    var duration: Long = DEFAULT_DURATION_MS,
) : VideoPlaybackEngine, DisposableVideoPlaybackEngine {

    var listener: VideoPlaybackEngineListener? = null
        private set

    var position: Long = 0L
    var buffered: Long = 0L
    var loadFailure: Throwable? = null
    var loadDelayMs: Long = 0L

    /** Virtual time a cancelled load takes to unwind, like a platform handle that is slow to free. */
    var unwindDelayMs: Long = 0L

    /** When non-`null`, a cancelled load throws this instead of the cancellation. */
    var failureWhenCancelled: Throwable? = null

    /** Reported to the listener from inside [load], before it returns — as a platform does. */
    var sizeReportedWhileLoading: VideoSize? = null

    var activeLoads: Int = 0
        private set
    var maxConcurrentLoads: Int = 0
        private set

    val loadedSources: MutableList<VideoSource> = mutableListOf()
    val seekTargets: MutableList<Long> = mutableListOf()
    val appliedVolumes: MutableList<Float> = mutableListOf()
    val appliedLooping: MutableList<Boolean> = mutableListOf()
    val appliedSpeeds: MutableList<Float> = mutableListOf()
    var started: Int = 0
    var paused: Int = 0
    var releaseCount: Int = 0
    var disposeCount: Int = 0

    /** Every call in order, by name — for the few tests that care about ordering. */
    val calls: MutableList<String> = mutableListOf()

    val appliedSpeed: Float get() = appliedSpeeds.lastOrNull() ?: 1.0f
    val appliedVolume: Float? get() = appliedVolumes.lastOrNull()
    val looping: Boolean get() = appliedLooping.lastOrNull() ?: false

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: VideoSource) {
        calls += "load"
        loadedSources += source
        activeLoads++
        maxConcurrentLoads = maxOf(maxConcurrentLoads, activeLoads)
        try {
            sizeReportedWhileLoading?.let { size: VideoSize -> listener?.onVideoSizeChanged(size) }
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
        calls += "start"
        started++
    }

    override fun pause() {
        calls += "pause"
        paused++
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo"
        seekTargets += positionMs
        position = positionMs
    }

    override fun setSpeed(speed: Float) {
        calls += "setSpeed"
        appliedSpeeds += speed
    }

    override fun setVolume(volume: Float) {
        calls += "setVolume"
        appliedVolumes += volume
    }

    override fun setLooping(looping: Boolean) {
        calls += "setLooping"
        appliedLooping += looping
    }

    override fun durationMs(): Long = duration

    override fun positionMs(): Long = position

    override fun bufferedPositionMs(): Long = buffered

    override fun release() {
        calls += "release"
        releaseCount++
        position = 0L
        buffered = 0L
    }

    override fun dispose() {
        calls += "dispose"
        disposeCount++
    }

    private companion object {
        const val DEFAULT_DURATION_MS: Long = 10_000L
    }
}
