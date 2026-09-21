package io.github.jamal_wia.kmptoolkit.video.player

/**
 * The thin platform seam a [VideoPlayer] drives. The library ships one per platform (Media3
 * ExoPlayer on Android, AVPlayer on iOS) and the factories build it for you; it is public so a
 * consumer can back the same [VideoPlayer] contract with another engine, and so
 * `kmptoolkit-video-player-testing` can supply a scriptable fake. The state machine, polling,
 * clamping and the release contract live above this interface, in common code.
 *
 * ### Contract for implementors
 *
 * - **[release] is idempotent** and safe after a failed [load]; [load] after [release] works.
 * - **[load] honors cancellation**, throws to report failure, and leaves nothing playable behind in
 *   either case. The player never runs two loads at once.
 * - **Never call the listener after [release]**, including callbacks already queued on a platform
 *   thread.
 * - **Transport and settings calls tolerate the wrong state** — swallow the platform's
 *   "illegal state" rather than throwing.
 * - **[durationMs], [positionMs] and [bufferedPositionMs] return `0` when unknown** and are cheap:
 *   they are polled from the player's coroutine context, so an engine whose platform object is
 *   confined to one thread answers from values it caches on that thread.
 * - **[setLooping]** makes the platform restart the source at its end without reporting completion.
 * - **Any method may be called from any thread** — the app's, the polling coroutine's, or the thread
 *   an event was reported on — though never two at once: the player serializes its calls. An engine
 *   whose platform object is confined to one thread marshals onto it.
 * - **The listener may be called from any thread**, including while the engine holds a lock of its
 *   own and from inside one of the calls above. The player never blocks in a callback: an event that
 *   arrives while another transition is running is applied right after it.
 */
public interface VideoPlaybackEngine {

    /** Installs the event sink, replacing any previous one; `null` detaches. */
    public fun setListener(listener: VideoPlaybackEngineListener?)

    /** Loads [source] and suspends until it is ready to play; throws any platform error. */
    public suspend fun load(source: VideoSource)

    /** Starts or resumes output at the current playhead. */
    public fun start()

    /** Suspends output, leaving the playhead in place. */
    public fun pause()

    /** Moves the playhead; the caller has already clamped [positionMs] to `0..`[durationMs]. */
    public fun seekTo(positionMs: Long)

    /** Sets the rate; the caller has already clamped [speed]. */
    public fun setSpeed(speed: Float)

    /** Sets the effective output volume in `0f..1f` (the caller folds mute into it as `0f`). */
    public fun setVolume(volume: Float)

    /** Whether the platform restarts the source at its end instead of completing. */
    public fun setLooping(looping: Boolean)

    /** Total length in milliseconds, or `0` if unknown. */
    public fun durationMs(): Long

    /** Playhead in milliseconds, or `0` if unknown. */
    public fun positionMs(): Long

    /** Buffered-ahead position in milliseconds, or `0` if unknown. */
    public fun bufferedPositionMs(): Long

    /** Frees every native resource. Idempotent. */
    public fun release()
}

/**
 * How a [VideoPlaybackEngine] reports what it cannot report by returning. Failures while loading
 * are thrown from [VideoPlaybackEngine.load] instead.
 */
public interface VideoPlaybackEngineListener {

    /** The source played to its end (never called while looping). */
    public fun onCompleted()

    /** Playback failed after loading succeeded; surfaced unchanged as [VideoPlayerState.Error]. */
    public fun onFailed(cause: Throwable)

    /** Playback started or stopped waiting for data. */
    public fun onBufferingChanged(isBuffering: Boolean)

    /** The decoded picture size became known or changed; `null` when there is no picture. */
    public fun onVideoSizeChanged(size: VideoSize?)
}
