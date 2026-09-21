package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.duration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Everything a controls implementation needs: the player's state as observable values, whether the
 * controls are currently shown, and the actions a control performs. It is the receiver of
 * [VideoPlayer]'s `controls` slot, so `DefaultVideoControls()` and your own controls read and act
 * through exactly the same API.
 *
 * **Reading.** Every property is backed by Compose snapshot state, so reading it in a composable
 * (or in a `drawBehind { }` / `graphicsLayer { }` lambda) recomposes, relayouts or redraws that
 * reader when it changes, and nothing else.
 *
 * **Acting.** The actions forward to [player] and also count as user activity: each one restarts
 * the auto-hide countdown. Call [player] directly for anything not listed here; that works too, it
 * just does not restart the countdown.
 *
 * **Visibility.** [controlsVisible] is a hint the controls implementation honours — the default
 * controls fade in and out with it; custom controls may ignore it (a permanently visible bar under
 * the picture, say). It becomes `true` whenever playback stops running (paused, completed, failed)
 * and `false` after the auto-hide delay while playing without interaction.
 *
 * Implemented by this library only — obtain one from [VideoPlayer]'s `controls` slot or from
 * [rememberVideoControlsScope]. New members may be added in a minor release.
 */
@Stable
public interface VideoControlsScope {

    /** The player these controls drive. */
    public val player: VideoPlayer

    /** [VideoPlayer.stateFlow]'s current value. */
    public val playerState: VideoPlayerState

    /** Whether [playerState] is [VideoPlayerState.Playing]. */
    public val isPlaying: Boolean

    /** [VideoPlayer.playbackPositionFlow]'s current value, in milliseconds. */
    public val positionMs: Long

    /** Total length in milliseconds, or `0` while unknown (nothing loaded, or a live stream). */
    public val durationMs: Long

    /** [VideoPlayer.bufferedPositionFlow]'s current value, in milliseconds. */
    public val bufferedPositionMs: Long

    /** [VideoPlayer.isBufferingFlow]'s current value. */
    public val isBuffering: Boolean

    /** [VideoPlayer.isMutedFlow]'s current value. */
    public val isMuted: Boolean

    /** [VideoPlayer.volumeFlow]'s current value, `0f..1f`. */
    public val volume: Float

    /** [VideoPlayer.playbackSpeedFlow]'s current value. */
    public val playbackSpeed: Float

    /** [VideoPlayer.repeatModeFlow]'s current value. */
    public val repeatMode: RepeatMode

    /** [VideoPlayer.videoSizeFlow]'s current value. */
    public val videoSize: VideoSize?

    /** Whether the controls should currently be shown. See the class description. */
    public val controlsVisible: Boolean

    /** Shows the controls and restarts the auto-hide countdown. */
    public fun showControls()

    /** Hides the controls now. */
    public fun hideControls()

    /** [hideControls] when visible, [showControls] otherwise. What a tap on the picture does. */
    public fun toggleControls()

    /**
     * Suspends auto-hide while [interacting] is `true` — during a seek-bar drag, or while a menu of
     * your own is open — and restarts the countdown when it goes back to `false`.
     */
    public fun setInteracting(interacting: Boolean)

    /** [VideoPlayer.play]. */
    public fun play()

    /** [VideoPlayer.pause]. */
    public fun pause()

    /** Pauses while playing, replays after completion, plays otherwise. */
    public fun togglePlayPause()

    /** [VideoPlayer.seekTo]. */
    public fun seekTo(positionMs: Long)

    /** Seeks [deltaMs] forward, or backward when negative. */
    public fun seekBy(deltaMs: Long)

    /** [VideoPlayer.replay]. */
    public fun replay()

    /** [VideoPlayer.setMuted]. */
    public fun setMuted(muted: Boolean)

    /** Flips [isMuted]. */
    public fun toggleMute()

    /** [VideoPlayer.setVolume]. */
    public fun setVolume(volume: Float)

    /** [VideoPlayer.setPlaybackSpeed]. */
    public fun setPlaybackSpeed(speed: Float)

    /** [VideoPlayer.setRepeatMode]. */
    public fun setRepeatMode(mode: RepeatMode)
}

/**
 * A [VideoControlsScope] for [player], for building a player layout of your own around
 * [VideoPlayerSurface] rather than using [VideoPlayer] — controls under the picture instead of over
 * it, for example. Run a controls composable against it with `with(scope) { DefaultVideoControls() }`.
 *
 * The scope collects [player]'s flows while it is composed and runs the auto-hide countdown.
 *
 * @param autoHideDelayMs how long the controls stay visible while playing without interaction.
 *   Lengthened, up to "never", where the platform's accessibility settings ask for more time to
 *   act on controls (a screen reader, for example). Must be positive.
 * @throws IllegalArgumentException when [autoHideDelayMs] is not positive.
 */
@Composable
public fun rememberVideoControlsScope(
    player: VideoPlayer,
    autoHideDelayMs: Long = VideoControlsDefaults.AutoHideDelayMs,
): VideoControlsScope {
    requireAutoHideDelay(autoHideDelayMs)
    val scope: VideoControlsScopeImpl = remember(player) { VideoControlsScopeImpl(player) }
    val accessibilityManager: AccessibilityManager? = LocalAccessibilityManager.current
    val hideAfterMs: Long = remember(accessibilityManager, autoHideDelayMs) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            originalTimeoutMillis = autoHideDelayMs,
            containsIcons = true,
            containsText = false,
            containsControls = true,
        ) ?: autoHideDelayMs
    }

    LaunchedEffect(scope) { scope.collectPlayer() }
    LaunchedEffect(scope) {
        snapshotFlow { scope.isPlaying }.collect { playing -> if (!playing) scope.showControls() }
    }
    LaunchedEffect(scope, hideAfterMs) {
        snapshotFlow { AutoHideKey(scope.shouldAutoHide, scope.activity) }.collectLatest { key ->
            if (key.shouldHide) {
                delay(hideAfterMs)
                scope.hideControls()
            }
        }
    }
    return scope
}

internal fun requireAutoHideDelay(autoHideDelayMs: Long) {
    require(autoHideDelayMs > 0L) { "autoHideDelayMs must be positive, was $autoHideDelayMs" }
}

private class AutoHideKey(val shouldHide: Boolean, val activity: Int) {
    override fun equals(other: Any?): Boolean =
        other is AutoHideKey && other.shouldHide == shouldHide && other.activity == activity

    override fun hashCode(): Int = 31 * shouldHide.hashCode() + activity
}

@Stable
internal class VideoControlsScopeImpl(override val player: VideoPlayer) : VideoControlsScope {

    // Mirrors of the player's flows. Private fields behind read-only overrides: a `var isMuted`
    // with a private setter would compile to `setMuted(Z)V` and clash with the action of that name.
    private var stateMirror: VideoPlayerState by mutableStateOf(player.stateFlow.value)
    private var positionMirror: Long by mutableLongStateOf(player.playbackPositionFlow.value)
    private var bufferedMirror: Long by mutableLongStateOf(player.bufferedPositionFlow.value)
    private var bufferingMirror: Boolean by mutableStateOf(player.isBufferingFlow.value)
    private var mutedMirror: Boolean by mutableStateOf(player.isMutedFlow.value)
    private var volumeMirror: Float by mutableFloatStateOf(player.volumeFlow.value)
    private var speedMirror: Float by mutableFloatStateOf(player.playbackSpeedFlow.value)
    private var repeatMirror: RepeatMode by mutableStateOf(player.repeatModeFlow.value)
    private var sizeMirror: VideoSize? by mutableStateOf(player.videoSizeFlow.value)
    private var visible: Boolean by mutableStateOf(true)

    override val playerState: VideoPlayerState get() = stateMirror
    override val positionMs: Long get() = positionMirror
    override val bufferedPositionMs: Long get() = bufferedMirror
    override val isBuffering: Boolean get() = bufferingMirror
    override val isMuted: Boolean get() = mutedMirror
    override val volume: Float get() = volumeMirror
    override val playbackSpeed: Float get() = speedMirror
    override val repeatMode: RepeatMode get() = repeatMirror
    override val videoSize: VideoSize? get() = sizeMirror
    override val controlsVisible: Boolean get() = visible

    private var interactingNow: Boolean by mutableStateOf(false)

    /** Bumped by every user action, so the auto-hide countdown starts over. */
    var activity: Int by mutableIntStateOf(0)
        private set

    override val isPlaying: Boolean
        get() = playerState is VideoPlayerState.Playing

    override val durationMs: Long
        get() = playerState.duration ?: 0L

    val shouldAutoHide: Boolean
        get() = visible && isPlaying && !interactingNow

    /** Mirrors the player's flows into snapshot state until cancelled. Runs on the UI dispatcher. */
    suspend fun collectPlayer(): Unit = coroutineScope {
        launch { player.stateFlow.collect { stateMirror = it } }
        launch { player.playbackPositionFlow.collect { positionMirror = it } }
        launch { player.bufferedPositionFlow.collect { bufferedMirror = it } }
        launch { player.isBufferingFlow.collect { bufferingMirror = it } }
        launch { player.isMutedFlow.collect { mutedMirror = it } }
        launch { player.volumeFlow.collect { volumeMirror = it } }
        launch { player.playbackSpeedFlow.collect { speedMirror = it } }
        launch { player.repeatModeFlow.collect { repeatMirror = it } }
        launch { player.videoSizeFlow.collect { sizeMirror = it } }
    }

    override fun showControls() {
        visible = true
        activity++
    }

    override fun hideControls() {
        visible = false
    }

    override fun toggleControls() {
        if (visible) hideControls() else showControls()
    }

    override fun setInteracting(interacting: Boolean) {
        interactingNow = interacting
        activity++
    }

    override fun play(): Unit = act { player.play() }

    override fun pause(): Unit = act { player.pause() }

    override fun togglePlayPause(): Unit = act {
        // The flow, not the mirrored snapshot value: two taps before the mirror catches up must
        // still alternate.
        when (player.stateFlow.value) {
            is VideoPlayerState.Playing -> player.pause()
            is VideoPlayerState.Completed -> player.replay()
            else -> player.play()
        }
    }

    override fun seekTo(positionMs: Long): Unit = act { player.seekTo(positionMs) }

    override fun seekBy(deltaMs: Long): Unit = act {
        when {
            deltaMs >= 0L -> player.seekForward(deltaMs)
            deltaMs == Long.MIN_VALUE -> player.seekBackward(Long.MAX_VALUE)
            else -> player.seekBackward(-deltaMs)
        }
    }

    override fun replay(): Unit = act { player.replay() }

    override fun setMuted(muted: Boolean): Unit = act { player.setMuted(muted) }

    override fun toggleMute(): Unit = act { player.setMuted(!player.isMutedFlow.value) }

    override fun setVolume(volume: Float): Unit = act { player.setVolume(volume) }

    override fun setPlaybackSpeed(speed: Float): Unit = act { player.setPlaybackSpeed(speed) }

    override fun setRepeatMode(mode: RepeatMode): Unit = act { player.setRepeatMode(mode) }

    private inline fun act(block: () -> Unit) {
        block()
        activity++
    }
}
