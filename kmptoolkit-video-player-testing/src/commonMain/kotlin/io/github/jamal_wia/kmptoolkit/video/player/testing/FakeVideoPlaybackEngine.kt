package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * An in-memory [VideoPlaybackEngine] that plays nothing and does exactly what you tell it to.
 *
 * Pass it to `createVideoPlayer(engine = FakeVideoPlaybackEngine())` and you get a real
 * [io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer] — the same state machine that ships to
 * production — with no device, no simulator and no video file. That makes a screen's reaction to a
 * failed load, a stall, a picture size or a finished video a plain unit test.
 *
 * There is no wall clock in here: the playhead moves only when [advancePositionTo] or
 * [advancePositionBy] is called, and events happen only when a `report…`/`complete…`/`fail…`
 * method is called. A load finishes at once, after [loadDelayMs] of virtual time, or — with
 * [suspendLoads] — only when the test calls [finishLoad] or [failLoad].
 *
 * Not thread-safe — drive it from the test's thread. (The player built on it serializes its own
 * transitions, but the fake's recorded properties are plain fields.)
 *
 * ```kotlin
 * val engine = FakeVideoPlaybackEngine(durationMs = 30_000)
 * val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
 *
 * player.prepare(VideoSource.Remote("https://example.test/lesson.mp4"))
 * player.play()
 * engine.advancePositionTo(28_500)
 * engine.completePlayback()
 *
 * assertEquals(VideoPlayerState.Completed(30_000), player.stateFlow.value)
 * ```
 *
 * @param durationMs duration reported for any loaded source; see [durationMs].
 */
public class FakeVideoPlaybackEngine(
    durationMs: Long = DEFAULT_DURATION_MS,
) : VideoPlaybackEngine {

    private var listener: VideoPlaybackEngineListener? = null

    /** Completes with the failure to throw (or `null`) — never exceptionally, so the throwable stays the caller's own. */
    private var pendingLoad: CompletableDeferred<Throwable?>? = null

    /**
     * Duration reported for the loaded source, in milliseconds. Writable so a test can model a source
     * whose length the platform never reports (`0`, a live stream) as well as a normal one.
     */
    public var durationMs: Long = durationMs

    /** Current playhead, in milliseconds. Moved by seeks and by [advancePositionTo]/[advancePositionBy]. */
    public var positionMs: Long = 0L
        private set

    /**
     * How far the fake has "buffered", in milliseconds, reported through
     * [VideoPlaybackEngine.bufferedPositionMs]. Writable at any time; reset to `0` by a load and by
     * [VideoPlaybackEngine.release].
     */
    public var bufferedPositionMs: Long = 0L

    /**
     * When non-`null`, every [VideoPlaybackEngine.load] throws it instead of succeeding — the way to
     * test the `VideoPlayerState.Error` path. Stays in effect until set back to `null`.
     */
    public var loadFailure: Throwable? = null

    /**
     * Simulated loading time in milliseconds of virtual time. Leave at `0` for an instant load; raise
     * it to give a test a window in which to cancel or replace a `prepare()`.
     */
    public var loadDelayMs: Long = 0L

    /**
     * When `true`, a load suspends until the test calls [finishLoad] or [failLoad] — for a test that
     * wants to observe `Preparing`, or to decide the outcome only after doing something else.
     */
    public var suspendLoads: Boolean = false

    /**
     * A picture size to report while loading, before the load finishes — as a platform usually does.
     * `null` (the default) reports nothing.
     */
    public var videoSizeOnLoad: VideoSize? = null

    /** Every source passed to [VideoPlaybackEngine.load], in order — including ones that then failed. */
    public val loadedSources: MutableList<VideoSource> = mutableListOf()

    /** Every position the player sought to, in order, already clamped by the player. */
    public val seekTargets: MutableList<Long> = mutableListOf()

    /** Whether a load is suspended waiting for [finishLoad] or [failLoad]. */
    public val isLoadPending: Boolean
        get() = pendingLoad != null

    /** Whether output is running: `true` between [VideoPlaybackEngine.start] and a pause, end or release. */
    public var isPlaying: Boolean = false
        private set

    /** The rate last pushed by the player through [VideoPlaybackEngine.setSpeed]. */
    public var appliedSpeed: Float = NORMAL_SPEED
        private set

    /** The output volume last pushed by the player — `0f` while the player is muted. */
    public var appliedVolume: Float = FULL_VOLUME
        private set

    /** Whether the player asked for looping (`RepeatMode.One`). */
    public var isLooping: Boolean = false
        private set

    /**
     * How many times [VideoPlaybackEngine.release] has been called. The player releases its engine
     * once per unload, once for a cancelled load and once for its own release — however often a
     * consumer calls `release()`/`close()`.
     */
    public var releaseCount: Int = 0
        private set

    /** Whether a [VideoPlaybackEngineListener] is attached — `false` once the player is released. */
    public val hasListener: Boolean
        get() = listener != null

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: VideoSource) {
        loadedSources += source
        positionMs = 0L
        bufferedPositionMs = 0L
        isPlaying = false
        videoSizeOnLoad?.let { size: VideoSize -> listener?.onVideoSizeChanged(size) }
        if (loadDelayMs > 0L) delay(loadDelayMs)
        if (suspendLoads) {
            val pending = CompletableDeferred<Throwable?>()
            pendingLoad = pending
            val failure: Throwable? = try {
                pending.await()
            } finally {
                if (pendingLoad === pending) pendingLoad = null
            }
            failure?.let { throw it }
        }
        loadFailure?.let { failure: Throwable -> throw failure }
    }

    override fun start() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        seekTargets += positionMs
        this.positionMs = positionMs
    }

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed
    }

    override fun setVolume(volume: Float) {
        appliedVolume = volume
    }

    override fun setLooping(looping: Boolean) {
        isLooping = looping
    }

    override fun durationMs(): Long = durationMs

    override fun positionMs(): Long = positionMs

    override fun bufferedPositionMs(): Long = bufferedPositionMs

    override fun release() {
        releaseCount++
        isPlaying = false
        positionMs = 0L
        bufferedPositionMs = 0L
    }

    /**
     * Lets a load suspended by [suspendLoads] finish — successfully, unless [loadFailure] is set.
     * Does nothing when no load is pending.
     */
    public fun finishLoad() {
        pendingLoad?.complete(null)
    }

    /**
     * Fails a load suspended by [suspendLoads] with [cause]. Does nothing when no load is pending.
     *
     * @param cause the error the player should surface as `VideoPlayerState.Error`.
     */
    public fun failLoad(cause: Throwable) {
        pendingLoad?.complete(cause)
    }

    /**
     * Moves the playhead, as real playback would between two position polls.
     *
     * @param positionMs new position in milliseconds.
     */
    public fun advancePositionTo(positionMs: Long) {
        this.positionMs = positionMs
    }

    /**
     * Moves the playhead forward by [amountMs], never past [durationMs] when a duration is known.
     *
     * @param amountMs how far to advance, in milliseconds.
     */
    public fun advancePositionBy(amountMs: Long) {
        val advanced: Long = positionMs + amountMs
        positionMs = if (durationMs > 0L) advanced.coerceAtMost(durationMs) else advanced
    }

    /**
     * Reports that the source played to its end. Like a real engine, a looping fake starts over from
     * `0` without reporting anything; otherwise the playhead moves to [durationMs] and
     * [VideoPlaybackEngineListener.onCompleted] is called.
     *
     * Does nothing observable when no listener is attached — the case after the player is released,
     * so a test can check that a late completion is ignored.
     */
    public fun completePlayback() {
        if (isLooping) {
            positionMs = 0L
            return
        }
        positionMs = durationMs
        isPlaying = false
        listener?.onCompleted()
    }

    /**
     * Reports a failure after loading succeeded, via [VideoPlaybackEngineListener.onFailed]. To fail
     * a load instead, set [loadFailure] or call [failLoad].
     *
     * @param cause the error the player should surface.
     */
    public fun failPlayback(cause: Throwable) {
        isPlaying = false
        listener?.onFailed(cause)
    }

    /**
     * Reports that playback started (`true`) or stopped (`false`) waiting for data, via
     * [VideoPlaybackEngineListener.onBufferingChanged].
     *
     * @param isBuffering whether the fake is now stalled.
     */
    public fun reportBuffering(isBuffering: Boolean) {
        listener?.onBufferingChanged(isBuffering)
    }

    /**
     * Reports the decoded picture size, via [VideoPlaybackEngineListener.onVideoSizeChanged]; `null`
     * reports that there is no picture.
     *
     * @param size the new size.
     */
    public fun reportVideoSize(size: VideoSize?) {
        listener?.onVideoSizeChanged(size)
    }

    private companion object {
        const val DEFAULT_DURATION_MS: Long = 10_000L
        const val NORMAL_SPEED: Float = 1.0f
        const val FULL_VOLUME: Float = 1.0f
    }
}
