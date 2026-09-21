package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.frameSourceOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The desktop surface end to end, with nothing stubbed between the player and the pixels: a real
 * player from `createVideoPlayer` over an engine that also renders frames — the shape of the VLCJ
 * and JavaFX engines — drawn by [VideoPlayerSurface] and [VideoPlayer] exactly as an app draws it.
 */
@OptIn(ExperimentalTestApi::class, ToolkitInternalApi::class)
class DesktopPlayerSurfaceTest {

    /** The fake engine plus a frame source: a memory-rendering engine, like the desktop ones. */
    private class FrameEngine(
        delegate: VideoPlaybackEngine,
        firstFrame: VideoFrame?,
    ) : VideoPlaybackEngine by delegate, VideoFrameSource {
        override val frames: MutableStateFlow<VideoFrame?> = MutableStateFlow(firstFrame)
    }

    private fun framePlayer(frame: VideoFrame?, videoSize: VideoSize? = null): TestPlayer =
        TestPlayer(wrap = { recording -> FrameEngine(recording, frame) }).apply {
            fake.videoSizeOnLoad = videoSize
            prepare()
        }

    private fun solidFrame(width: Int, height: Int, argb: Int): VideoFrame =
        VideoFrame(width, height, IntArray(width * height) { argb })

    private fun ComposeUiTest.capture(): PixelMap = onNodeWithTag("host").captureToImage().toPixelMap()

    /** The pixel at ([x], [y]) given as fractions of the captured width and height. */
    private fun PixelMap.at(x: Float, y: Float): Color =
        this[(width * x).toInt().coerceIn(0, width - 1), (height * y).toInt().coerceIn(0, height - 1)]

    @Test
    fun `a player's frames reach the screen through VideoPlayerSurface`() = runComposeUiTest {
        val player: TestPlayer = framePlayer(solidFrame(4, 2, RED))
        assertNotNull(player.player.frameSourceOrNull(), "the engine's frame source is found")
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                VideoPlayerSurface(player.player, Modifier.fillMaxSize())
            }
        }
        waitForIdle()

        assertEquals(Color.Red, capture().at(0.5f, 0.5f))
    }

    @Test
    fun `fit draws an anamorphic frame over the whole box sized from the reported picture size`() =
        runComposeUiTest {
            // Stored as a square, displayed 2:1: the reported size is the display size.
            val player: TestPlayer = framePlayer(solidFrame(100, 100, RED), VideoSize(200, 100))
            setContent {
                Box(Modifier.size(100.dp).background(Color.White).testTag("host")) {
                    VideoPlayerSurface(player.player, Modifier.fillMaxSize(), scaleMode = VideoScaleMode.Fit)
                }
            }
            waitForIdle()

            val pixels: PixelMap = capture()
            assertEquals(Color.Red, pixels.at(0.05f, 0.5f), "the picture spans the full width")
            assertEquals(Color.Red, pixels.at(0.95f, 0.5f), "the picture spans the full width")
            assertEquals(Color.White, pixels.at(0.5f, 0.1f), "the bar above a 2:1 picture")
            assertEquals(Color.White, pixels.at(0.5f, 0.9f), "the bar below a 2:1 picture")
        }

    @Test
    fun `crop draws an anamorphic frame by the reported ratio, not by its stored one`() = runComposeUiTest {
        // A square frame whose top quarter is blue, displayed 2:1 and cropped into a square box:
        // stretched to 2:1 its full height is visible, so the blue band shows at the top. Fitted by
        // its own square ratio instead, the top of the frame would be cropped away.
        val frame = VideoFrame(100, 100, IntArray(100 * 100) { index -> if (index / 100 < 25) BLUE else RED })
        val player: TestPlayer = framePlayer(frame, VideoSize(200, 100))
        setContent {
            Box(Modifier.size(100.dp).background(Color.White).testTag("host")) {
                VideoPlayerSurface(player.player, Modifier.fillMaxSize(), scaleMode = VideoScaleMode.Crop)
            }
        }
        waitForIdle()

        val pixels: PixelMap = capture()
        assertEquals(Color.Blue, pixels.at(0.5f, 0.1f))
        assertEquals(Color.Red, pixels.at(0.5f, 0.6f))
        assertEquals(Color.Red, pixels.at(0.02f, 0.6f), "crop leaves no bar")
    }

    @Test
    fun `swapping the player switches the frame source`() = runComposeUiTest {
        val red: TestPlayer = framePlayer(solidFrame(4, 2, RED))
        val green: TestPlayer = framePlayer(solidFrame(4, 2, GREEN))
        val noPicture: TestPlayer = TestPlayer().prepare()
        var current: TestPlayer by mutableStateOf(red)
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                VideoPlayerSurface(current.player, Modifier.fillMaxSize())
            }
        }
        waitForIdle()
        assertEquals(Color.Red, capture().at(0.5f, 0.5f))

        current = green
        waitForIdle()
        assertEquals(Color.Green, capture().at(0.5f, 0.5f))

        // A player without a picture leaves the surface empty, not showing the last one's frame.
        current = noPicture
        waitForIdle()
        assertEquals(Color.White, capture().at(0.5f, 0.5f))
    }

    @Test
    fun `the source overload draws the frames of the player its factory creates`() = runComposeUiTest {
        val factory = RecordingFactory { TestPlayer(wrap = { recording -> FrameEngine(recording, solidFrame(4, 2, RED)) }) }
        setContent {
            CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                Box(Modifier.size(80.dp, 40.dp).testTag("host")) {
                    VideoPlayer(source = SOURCE_A, modifier = Modifier.fillMaxSize(), controls = {})
                }
            }
        }
        waitForIdle()

        assertEquals(listOf(SOURCE_A), factory.created.single().fake.loadedSources)
        assertEquals(Color.Red, capture().at(0.5f, 0.5f))
    }

    private companion object {
        const val RED: Int = 0xFFFF0000.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val BLUE: Int = 0xFF0000FF.toInt()
    }
}
