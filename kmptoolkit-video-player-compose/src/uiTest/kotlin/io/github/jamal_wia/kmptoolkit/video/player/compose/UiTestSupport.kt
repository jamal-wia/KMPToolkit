package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource

/** Labels the tests find controls by — test fixtures, not library copy. */
val LABELS: VideoControlsLabels = VideoControlsLabels(
    play = "Play",
    pause = "Pause",
    replay = "Replay",
    seekForward = "Forward",
    seekBackward = "Backward",
    mute = "Mute",
    unmute = "Unmute",
    seekBar = "Seek",
    playbackSpeed = "Speed",
    enterFullscreen = "Enter fullscreen",
    exitFullscreen = "Exit fullscreen",
    buffering = "Buffering",
)

const val PICTURE_TAG: String = "picture"

/** Long enough that no test sees the controls hide unless it advances the clock on purpose. */
const val NEVER_HIDE_MS: Long = 3_600_000L

val SOURCE_A: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
val SOURCE_B: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

/**
 * Stands in for the platform surface, which a fake player has nothing to attach to (the real
 * Media3/AVPlayer attachment is not testable on a JVM). Records what the surface was asked for.
 */
class RecordingSurface {
    var lastKeepScreenOn: Boolean? = null
    var lastScaleMode: VideoScaleMode? = null

    val content: PlatformSurfaceContent = { _, modifier, scaleMode, keepScreenOn ->
        lastKeepScreenOn = keepScreenOn
        lastScaleMode = scaleMode
        Box(modifier.testTag(PICTURE_TAG))
    }
}

/** Creates [FakeVideoPlayer]s and remembers them, for [rememberVideoPlayer] tests. */
class RecordingFactory(private val make: () -> FakeVideoPlayer = { FakeVideoPlayer() }) : VideoPlayerFactory {
    val created: MutableList<FakeVideoPlayer> = mutableListOf()
    val configs: MutableList<VideoPlayerConfig> = mutableListOf()

    override fun create(config: VideoPlayerConfig): FakeVideoPlayer {
        configs += config
        return make().also { created += it }
    }
}

/** A 400×300 dp host with the fake surface (and optionally a factory) provided. */
@Composable
fun TestHost(
    surface: RecordingSurface = RecordingSurface(),
    factory: VideoPlayerFactory? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPlatformSurfaceOverride provides surface.content,
        LocalVideoPlayerFactory provides factory,
    ) {
        Box(Modifier.size(400.dp, 300.dp)) { content() }
    }
}
