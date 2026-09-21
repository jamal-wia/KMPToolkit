package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.flow.StateFlow

/**
 * The headless video player: state, transport and settings, with no UI. Render it with
 * `kmptoolkit-video-player-compose` (`VideoPlayerSurface`, or `VideoPlayer` with controls), or drive
 * it from a view model and draw your own.
 *
 * The transport contract matches `kmptoolkit-audio-player`'s `AudioPlayer` — [prepare] suspends and
 * never throws on failure, a newer [prepare] replaces an older one, calls outside a playable state
 * are ignored, [release] is idempotent and final. What a video needs on top is here too: volume and
 * mute, a [RepeatMode], buffering, buffered position and picture size.
 *
 * Obtain one from a platform factory (`createVideoPlayer(context)` on Android, `createVideoPlayer()`
 * on iOS) or from [createVideoPlayer] with your own [VideoPlaybackEngine]; never implement this
 * interface yourself, so the state machine stays shared.
 *
 * **Threading.** Drive one player from one thread (normally the main thread — a screen does). The
 * flows are safe to collect anywhere.
 */
public interface VideoPlayer : AutoCloseable {

    /** Current state. Starts at [VideoPlayerState.Idle], never completes. */
    public val stateFlow: StateFlow<VideoPlayerState>

    /** Playhead in milliseconds, refreshed at [VideoPlayerConfig.positionUpdateIntervalMs] while playing. */
    public val playbackPositionFlow: StateFlow<Long>

    /**
     * How far ahead of the start the platform has buffered, in milliseconds — the second track of a
     * seek bar. `0` when nothing is loaded; equal to the duration for a local file.
     */
    public val bufferedPositionFlow: StateFlow<Long>

    /** Whether playback is stalled waiting for data. Independent of [stateFlow]; `false` when idle. */
    public val isBufferingFlow: StateFlow<Boolean>

    /** Size of the decoded picture, or `null` until the platform reports one (and when idle). */
    public val videoSizeFlow: StateFlow<VideoSize?>

    /** Rate in effect, already clamped to the configured range. `1.0` until [setPlaybackSpeed]. */
    public val playbackSpeedFlow: StateFlow<Float>

    /** Output volume in `0f..1f`, independent of [isMutedFlow]. `1.0` until [setVolume]. */
    public val volumeFlow: StateFlow<Float>

    /** Whether output is muted. Muting keeps [volumeFlow], so unmuting restores it. */
    public val isMutedFlow: StateFlow<Boolean>

    /** What happens at the end of the source. [RepeatMode.Off] until [setRepeatMode]. */
    public val repeatModeFlow: StateFlow<RepeatMode>

    /**
     * Loads [source], suspending until it is playable or has failed. Discards any previous source and
     * resets the playhead to `0`; keeps speed, volume, mute and repeat mode. Settles on
     * [VideoPlayerState.Ready] or [VideoPlayerState.Error] — **does not throw on failure**. Honors
     * cancellation. A newer call replaces this one. After [release]: sets
     * `Error(VideoPlayerReleasedException)` and loads nothing.
     */
    public suspend fun prepare(source: VideoSource)

    /** Ready/Paused/Completed → Playing. From Completed it starts over, like [replay]. */
    public fun play()

    /** Playing → Paused. Ignored in any other state. */
    public fun pause()

    /** Any playable state → Ready with the playhead at `0`; the source stays loaded. */
    public fun stop()

    /**
     * Moves the playhead, clamped to `0..duration`. Playing stays Playing, Paused stays Paused,
     * Completed becomes Paused.
     */
    public fun seekTo(positionMs: Long)

    /** `seekTo(position + amountMs)`. */
    public fun seekForward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)

    /** `seekTo(position - amountMs)`. */
    public fun seekBackward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)

    /** Seek to `0` and play, from any playable state. */
    public fun replay()

    /** Clamps [speed] into the configured range and applies it. Never rejects a value. */
    public fun setPlaybackSpeed(speed: Float)

    /** Clamps [volume] into `0f..1f` and applies it. Does not change [isMutedFlow]. */
    public fun setVolume(volume: Float)

    /** Mutes or unmutes output, keeping the volume. */
    public fun setMuted(muted: Boolean)

    /** Sets what happens at the end of the source; takes effect for the source already loaded. */
    public fun setRepeatMode(mode: RepeatMode)

    /**
     * Abandons a load in flight, frees the loaded source and resets to Idle/`0`, keeping the player
     * usable for the next [prepare] and keeping speed, volume, mute and repeat mode.
     */
    public fun unload()

    /** Frees every native resource and resets to Idle/`0`. Idempotent; not reversible. */
    public fun release()

    /** [AutoCloseable] alias for [release], so a player can be used with `use { }`. */
    override fun close(): Unit = release()
}

/** Default step for [VideoPlayer.seekForward] and [VideoPlayer.seekBackward]: ten seconds. */
public const val DEFAULT_SEEK_AMOUNT_MS: Long = 10_000L
