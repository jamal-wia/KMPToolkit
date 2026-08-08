package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.flow.StateFlow

/**
 * A headless audio player: load one source, control the transport, observe typed state.
 *
 * "Headless" means it owns no UI and no notification. It renders nothing, shows no player
 * controls, publishes no media session, and holds no reference to an `Activity` or a `UIViewController`
 * — it exposes state and you render it. Obtain an instance from the platform factory
 * (`createAudioPlayer(context)` on Android, `createAudioPlayer()` on iOS).
 *
 * ### Lifecycle contract
 *
 * The instance owns a native handle (`android.media.MediaPlayer`, `AVFoundation.AVPlayer`) and an
 * internal coroutine that polls the playhead. Neither is reclaimed by garbage collection, so
 * **whoever creates a player releases it**, exactly once, when the screen or component that owns it
 * goes away:
 *
 * 1. [release] frees the native handle, cancels the position-polling coroutine, and detaches the
 *    engine's callbacks. [close] is the same operation under the [AutoCloseable] name, so a player
 *    also works with `use { }`.
 * 2. **[release] is idempotent.** Calling it a second (or tenth) time does nothing at all and never
 *    touches the already-freed handle.
 * 3. **A released player is inert, not fatal.** [play], [pause], [stop], [seekTo], [seekForward],
 *    [seekBackward], [replay] and [setPlaybackSpeed] become no-ops. Nothing throws, so a stray
 *    click arriving after teardown cannot crash the app.
 * 4. **[prepare] after release reports [PlayerState.Error] holding an
 *    [AudioPlayerReleasedException]** rather than silently doing nothing — loading a source is the
 *    one call where a caller genuinely waits for a result, and a silent no-op there is
 *    indistinguishable from a hang.
 * 5. After [release] the state is [PlayerState.Idle] and [playbackPositionFlow] is `0`. The flows
 *    stay readable; they simply stop changing (apart from rule 4).
 * 6. Late callbacks from the platform — a completion or failure that was already in flight when
 *    [release] ran — are dropped instead of resurrecting a dead player's state.
 *
 * A released player cannot be revived. Create another one.
 *
 * ### Threading
 *
 * Transport calls are not synchronized: drive one player from one thread (a UI screen's main
 * thread is the normal choice). [stateFlow] and [playbackPositionFlow] are `StateFlow`s and are
 * safe to collect from anywhere. [release] is safe to call concurrently with the transport calls in
 * the sense that it cannot double-free a native handle, but a transport call racing a release may
 * be either applied or dropped — do not depend on which.
 */
public interface AudioPlayer : AutoCloseable {

    /**
     * The player's current state. Starts at [PlayerState.Idle] and never completes.
     *
     * This is the single source of truth for the UI: [PlayerState.Playing] and [PlayerState.Paused]
     * carry the position, so a screen that renders a progress bar can collect this flow alone.
     */
    public val stateFlow: StateFlow<PlayerState>

    /**
     * The playhead position in milliseconds, refreshed every
     * [AudioPlayerConfig.positionUpdateIntervalMs] while playing.
     *
     * A narrower alternative to [stateFlow] for a component that only draws a scrubber: it changes
     * on position ticks only, so collecting it does not wake on unrelated state changes.
     */
    public val playbackPositionFlow: StateFlow<Long>

    /**
     * The playback rate in effect, already clamped to
     * [AudioPlayerConfig.minPlaybackSpeed]`..`[AudioPlayerConfig.maxPlaybackSpeed]. `1.0` until
     * [setPlaybackSpeed] is called; survives [prepare] so a chosen rate carries over to the next
     * source.
     */
    public val playbackSpeed: Float

    /**
     * Loads [source] and suspends until it is playable or has failed.
     *
     * Moves through [PlayerState.Preparing] and settles on either [PlayerState.Ready] or
     * [PlayerState.Error]. Any previously loaded source is discarded first. The current
     * [playbackSpeed] is re-applied to the new source.
     *
     * **Failure is reported, not thrown.** A missing asset, an unreachable URL or a codec the
     * platform cannot decode ends in [PlayerState.Error] carrying the platform exception; the call
     * itself returns normally. That keeps a load failure — which is expected and recoverable — out
     * of the caller's `try`/`catch`, and in the same flow the UI already collects.
     *
     * **Cancellation is honored.** If the calling coroutine is cancelled mid-load, the partially
     * loaded source is discarded, the state returns to [PlayerState.Idle], and the
     * `CancellationException` propagates — so a `LaunchedEffect` or a scoped `launch` that goes away
     * leaves no half-open native handle behind.
     *
     * @param source what to load; see [AudioSource].
     */
    public suspend fun prepare(source: AudioSource)

    /**
     * Starts or resumes playback.
     *
     * Ignored unless [PlayerState.isPlayable] — there is nothing to play in [PlayerState.Idle],
     * [PlayerState.Preparing] or [PlayerState.Error] — and ignored while already
     * [PlayerState.Playing], so a double tap does not restart the audio.
     *
     * Calling it in [PlayerState.Completed] resumes from the end, which completes again almost
     * immediately. Use [replay] to start over.
     */
    public fun play()

    /**
     * Suspends playback, keeping the playhead where it is.
     *
     * Ignored unless currently [PlayerState.Playing].
     */
    public fun pause()

    /**
     * Stops playback and rewinds to the beginning, leaving the source loaded.
     *
     * Ends in [PlayerState.Ready], so [play] starts from `0` again without another [prepare].
     * Ignored unless [PlayerState.isPlayable].
     */
    public fun stop()

    /**
     * Moves the playhead to [positionMs], clamped to `0..duration`.
     *
     * Ignored unless [PlayerState.isPlayable]. Playing stays playing and paused stays paused;
     * seeking out of [PlayerState.Completed] moves to [PlayerState.Paused], because once the
     * playhead is no longer at the end "completed" is no longer true.
     *
     * @param positionMs target position in milliseconds; out-of-range values are clamped rather
     *   than rejected.
     */
    public fun seekTo(positionMs: Long)

    /**
     * Moves the playhead [amountMs] later, clamped to the end of the source.
     *
     * @param amountMs how far to skip ahead, in milliseconds.
     */
    public fun seekForward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)

    /**
     * Moves the playhead [amountMs] earlier, clamped to `0`.
     *
     * @param amountMs how far to skip back, in milliseconds.
     */
    public fun seekBackward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)

    /**
     * Rewinds to the beginning and plays — the "start over" action, valid from any playable state
     * including [PlayerState.Completed].
     */
    public fun replay()

    /**
     * Sets the playback rate, clamped into the configured range and readable back from
     * [playbackSpeed].
     *
     * Takes effect immediately while playing and is applied on the next [play] otherwise. It is
     * never an error to ask for a rate outside the range — the value is clamped, because a UI
     * offering a fixed set of speed buttons should not have to know the platform's limits.
     *
     * @param speed rate multiplier: `1.0` is normal, `0.5` half, `2.0` double.
     */
    public fun setPlaybackSpeed(speed: Float)

    /**
     * Frees the native handle and stops all internal work. Idempotent; see the lifecycle contract
     * on [AudioPlayer].
     */
    public fun release()

    /** [AutoCloseable] alias for [release], so a player can be used with `use { }`. */
    override fun close(): Unit = release()
}

/** Skip amount used by [AudioPlayer.seekForward] and [AudioPlayer.seekBackward] by default. */
public const val DEFAULT_SEEK_AMOUNT_MS: Long = 10_000L
