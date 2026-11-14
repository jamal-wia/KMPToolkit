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
 * - **[release] frees the loaded source and keeps the engine reusable.** It is idempotent and safe
 *   after a failed [load]; [load] after [release] works.
 * - **[dispose] frees what the engine keeps across sources** — the platform player, a native library
 *   instance, a render thread. The player calls it exactly once, from [VideoPlayer.release], after
 *   the last [release]; no method of the engine is called after it. It defaults to a no-op, for an
 *   engine whose [release] already frees everything.
 * - **[load] honors cancellation**, throws to report failure, and leaves nothing playable behind in
 *   either case. A `CancellationException` thrown from [load] while the player still wants the load is
 *   reported as a failure (`Error`), not as a cancellation. The player never runs two loads at once.
 * - **Never call the listener after [release]**, including callbacks already queued on a platform
 *   thread; a call already running on another thread has returned before [release] returns. The player
 *   drops every report of a source it has told the engine to drop, but cannot see a call that has not
 *   yet reached it.
 * - **Transport and settings calls tolerate the wrong state** — swallow the platform's
 *   "illegal state" rather than throwing. Only [load] reports failure by throwing. If another method throws
 *   anyway, the player still settles: `prepare` ends in `Error` (or, when its caller was cancelled, in `Idle`, with the
 *   engine's exception attached as suppressed to the `CancellationException` the call received, which may be the
 *   cancelled scope's shared cause), `release()` disposes the engine and
 *   resets to `Idle`, `unload()` resets to `Idle`; the last two then rethrow the engine's exception. Any other method
 *   that throws breaks this contract: the exception reaches the caller of the player method (for an engine call the
 *   player makes while applying a queued engine event, whichever call or thread drains that event) and that
 *   transition may be left half applied. A reading that throws during a position poll, which has no caller,
 *   settles the player on `Error` carrying it.
 * - **[durationMs], [positionMs] and [bufferedPositionMs] return `0` when unknown** and are cheap:
 *   they are polled from the player's coroutine context, so an engine whose platform object is
 *   confined to one thread answers from values it caches on that thread.
 * - **[setLooping]** makes the platform restart the source at its end without reporting completion.
 * - **A completion** describes the playhead when it was reported: one reported while a seek away from the
 *   end is being applied is dropped as stale. Do not report it synchronously from inside [seekTo]
 *   unless the seek lands on the end.
 *   When the player drops such a completion while playing, it calls [start] again; an engine whose platform
 *   stops at its end must then resume output from the seek's target (not replay from the start, not ignore it).
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

    /**
     * Moves the playhead to [positionMs], which the caller keeps in `0..Long.MAX_VALUE / 1_000_000`
     * (about 292 years), so that it converts to microseconds or nanoseconds, and adds a platform's own
     * offsets, without overflowing.
     *
     * While the duration is known the caller has also clamped [positionMs] to `0..`[durationMs]. While
     * it is not (a live stream, a header not parsed yet: [durationMs] is `0`) an absolute seek arrives as
     * `0`, but `seekForward` / `seekBackward` arrive as the playhead plus or minus the amount, so a target
     * past the real end can reach the engine. The engine clamps such a target to the end of the source,
     * as its platform defines the end (ExoPlayer stops 1 microsecond short of it), or ignores it; it does
     * not throw.
     */
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

    /** Frees the loaded source, keeping the engine reusable for the next [load]. Idempotent. */
    public fun release()

    /**
     * Frees what the engine keeps across sources. Called exactly once, from [VideoPlayer.release],
     * after the last [release]; nothing is called on the engine afterwards. No-op by default.
     */
    public fun dispose() {}
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
