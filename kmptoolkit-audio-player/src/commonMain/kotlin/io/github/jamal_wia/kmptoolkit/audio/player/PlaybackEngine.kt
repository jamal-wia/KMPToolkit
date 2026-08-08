package io.github.jamal_wia.kmptoolkit.audio.player

/**
 * The thin platform seam an [AudioPlayer] drives — "make a sound", with no opinion about state.
 *
 * The library ships one implementation per platform (`MediaPlayer` on Android, `AVPlayer` on iOS)
 * and you normally never see this type: the platform factories build it for you. It is public for
 * two reasons, both real:
 *
 * - a consumer can back the same [AudioPlayer] contract with a different engine (ExoPlayer/Media3, a
 *   game audio mixer) without reimplementing the state machine;
 * - it is the substitution point that makes [AudioPlayer] testable off-device —
 *   `kmptoolkit-audio-player-testing` ships a scriptable `FakePlaybackEngine`.
 *
 * Everything interesting — state transitions, position polling, the release contract, clamping —
 * lives above this interface, in the [AudioPlayer] implementation, in `commonMain`, tested once. An
 * engine only has to translate the calls below onto its platform API.
 *
 * ### Contract for implementors
 *
 * - **[release] must be idempotent** and safe after a failed [load]. The player above may call it
 *   more than once, and calls it during teardown regardless of what state the engine reached.
 * - **Never call the listener after [release]** — the player detaches it first, but an engine that
 *   posts callbacks from a platform queue must also drop anything already in flight.
 * - **[start], [pause], [seekTo] and [setSpeed] must tolerate being called in the wrong state.**
 *   The player guards them with its own state, but platform state can diverge (a `MediaPlayer` that
 *   errored out); swallow the platform's "illegal state" rather than throwing.
 * - **[durationMs] and [positionMs] return `0` when unknown**, never a negative or `NaN`-derived
 *   value. They are polled frequently and must be cheap.
 * - **[load] throws to report failure** and must leave nothing playable behind when it does.
 */
public interface PlaybackEngine {

    /**
     * Installs the callback sink for asynchronous events, replacing any previous one. Passing
     * `null` detaches — the player does this before releasing.
     *
     * @param listener the sink, or `null` to detach.
     */
    public fun setListener(listener: PlaybackEngineListener?)

    /**
     * Loads [source] and suspends until it is ready to play, discarding anything loaded before.
     *
     * @param source what to load.
     * @throws Throwable any platform error — surfaced by the player as [PlayerState.Error].
     */
    public suspend fun load(source: AudioSource)

    /** Starts or resumes output at the current playhead. */
    public fun start()

    /** Suspends output, leaving the playhead in place. */
    public fun pause()

    /**
     * Moves the playhead. The caller has already clamped [positionMs] to `0..`[durationMs].
     *
     * @param positionMs target position in milliseconds.
     */
    public fun seekTo(positionMs: Long)

    /**
     * Sets the output rate. The caller has already clamped [speed] to the configured range.
     *
     * @param speed rate multiplier.
     */
    public fun setSpeed(speed: Float)

    /** Total length of the loaded source in milliseconds, or `0` if unknown. */
    public fun durationMs(): Long

    /** Current playhead position in milliseconds, or `0` if unknown. */
    public fun positionMs(): Long

    /** Frees every native resource this engine holds. Idempotent. */
    public fun release()
}

/**
 * How a [PlaybackEngine] reports the two things it cannot report by returning: the source ran to its
 * end, and playback broke after it had started.
 *
 * Failures raised *while loading* are thrown from [PlaybackEngine.load] instead — a caller is
 * already waiting there, so a callback would only be a second way to say the same thing.
 */
public interface PlaybackEngineListener {

    /** The loaded source played to its end. */
    public fun onCompleted()

    /**
     * Playback failed after loading succeeded.
     *
     * @param cause the platform error, surfaced unchanged as [PlayerState.Error].
     */
    public fun onFailed(cause: Throwable)
}
