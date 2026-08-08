package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.PlaybackEngine
import io.github.jamal_wia.kmptoolkit.audio.player.PlaybackEngineListener
import kotlinx.coroutines.delay

/**
 * An in-memory [PlaybackEngine] that plays nothing and does exactly what you tell it to.
 *
 * Pass it to `createAudioPlayer(engine = FakePlaybackEngine())` and you get a real [
 * io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayer] — the same state machine that ships to
 * production — with no device, no simulator, and no audio file involved. That is what makes it
 * possible to test a screen's reaction to a failed load or a completed track as a plain unit test.
 *
 * There is no wall clock in here: the playhead moves only when [advancePositionTo] or
 * [advancePositionBy] is called, and playback completes only when [completePlayback] is called.
 * A test that says "seek to 5s, then complete" is therefore deterministic and instant.
 *
 * This class is not thread-safe, matching the player it feeds — drive it from the test's thread.
 *
 * ```kotlin
 * val engine = FakePlaybackEngine(durationMs = 30_000)
 * val player: AudioPlayer = createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
 *
 * player.prepare(AudioSource.Remote("https://example.test/track.mp3"))
 * player.play()
 * engine.advancePositionTo(30_000)
 * engine.completePlayback()
 *
 * assertEquals(PlayerState.Completed(30_000), player.stateFlow.value)
 * ```
 *
 * @param durationMs duration reported for any loaded source; see [durationMs].
 */
public class FakePlaybackEngine(
    durationMs: Long = DEFAULT_DURATION_MS,
) : PlaybackEngine {

    private var listener: PlaybackEngineListener? = null

    /**
     * Duration reported for the loaded source, in milliseconds. Writable so a test can model a
     * source whose length the platform never reports (`0`) as well as a normal one.
     */
    public var durationMs: Long = durationMs

    /** Current playhead position, in milliseconds. Moved by seeks and by [advancePositionTo]. */
    public var positionMs: Long = 0L
        private set

    /**
     * When non-`null`, the next [PlaybackEngine.load] throws it instead of succeeding — the way to
     * test the [io.github.jamal_wia.kmptoolkit.audio.player.PlayerState.Error] path. Stays in
     * effect until set back to `null`.
     */
    public var loadFailure: Throwable? = null

    /**
     * Simulated loading time, in milliseconds of virtual time. Leave at `0` for an instant load;
     * raise it to give a test a window in which to cancel the coroutine that called `prepare()`.
     */
    public var loadDelayMs: Long = 0L

    /** Every source passed to [PlaybackEngine.load], in order — including ones that then failed. */
    public val loadedSources: MutableList<AudioSource> = mutableListOf()

    /** Whether output is running: `true` between [PlaybackEngine.start] and a pause or release. */
    public var isPlaying: Boolean = false
        private set

    /** The rate last pushed by the player through [PlaybackEngine.setSpeed]. */
    public var appliedSpeed: Float = NORMAL_SPEED
        private set

    /**
     * How many times [PlaybackEngine.release] has been called.
     *
     * The player's release contract says it releases its engine exactly once no matter how often
     * the consumer calls `release()`/`close()`, so this is the counter that proves it.
     */
    public var releaseCount: Int = 0
        private set

    /** Whether a [PlaybackEngineListener] is currently attached. */
    public val hasListener: Boolean
        get() = listener != null

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: AudioSource) {
        loadedSources += source
        if (loadDelayMs > 0L) delay(loadDelayMs)
        loadFailure?.let { failure: Throwable -> throw failure }
        positionMs = 0L
        isPlaying = false
    }

    override fun start() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        this.positionMs = positionMs
    }

    override fun setSpeed(speed: Float) {
        appliedSpeed = speed
    }

    override fun durationMs(): Long = durationMs

    override fun positionMs(): Long = positionMs

    override fun release() {
        releaseCount++
        isPlaying = false
        positionMs = 0L
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
     * Moves the playhead forward by [amountMs], never past [durationMs].
     *
     * @param amountMs how far to advance, in milliseconds.
     */
    public fun advancePositionBy(amountMs: Long) {
        positionMs = (positionMs + amountMs).coerceAtMost(durationMs)
    }

    /**
     * Reports that the source played to its end: moves the playhead to [durationMs] and calls
     * [PlaybackEngineListener.onCompleted].
     *
     * Does nothing when no listener is attached — which is the case after the player has been
     * released, so a test can use this to check that a late completion is ignored.
     */
    public fun completePlayback() {
        positionMs = durationMs
        isPlaying = false
        listener?.onCompleted()
    }

    /**
     * Reports a failure that happened *after* loading succeeded, via
     * [PlaybackEngineListener.onFailed]. To fail a load instead, set [loadFailure].
     *
     * @param cause the error the player should surface.
     */
    public fun failPlayback(cause: Throwable) {
        isPlaying = false
        listener?.onFailed(cause)
    }

    private companion object {
        const val DEFAULT_DURATION_MS: Long = 10_000L
        const val NORMAL_SPEED: Float = 1.0f
    }
}
