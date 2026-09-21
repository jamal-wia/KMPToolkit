package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Desktop only: the frame-drawing surface a memory-rendering engine feeds, and the missing-engine
 * error. The real engines (VLCJ, JavaFX) are tested in their own modules.
 */
@OptIn(ExperimentalTestApi::class, ToolkitInternalApi::class)
class DesktopSurfaceTest {

    private class FakeFrameSource : VideoFrameSource {
        override val frames: MutableStateFlow<VideoFrame?> = MutableStateFlow(null)
    }

    private fun solidFrame(width: Int, height: Int, argb: Int): VideoFrame =
        VideoFrame(width, height, IntArray(width * height) { argb })

    private fun androidx.compose.ui.test.ComposeUiTest.capture(): PixelMap =
        onNodeWithTag("host").captureToImage().toPixelMap()

    @Test
    fun `a frame is drawn, and each new frame replaces it`() = runComposeUiTest {
        val source = FakeFrameSource()
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(source, Modifier.fillMaxSize(), VideoScaleMode.Fit, pictureSizeKnown = false)
            }
        }
        waitForIdle()
        var pixels: PixelMap = capture()
        assertEquals(Color.White, pixels[pixels.width / 2, pixels.height / 2], "nothing before the first frame")

        source.frames.value = solidFrame(4, 2, RED)
        waitForIdle()
        pixels = capture()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])

        // Same size: the bitmap is reused. The alpha byte is ignored — decoders leave it undefined.
        source.frames.value = solidFrame(4, 2, BLUE_WITHOUT_ALPHA)
        waitForIdle()
        pixels = capture()
        assertEquals(Color.Blue, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun `a frame of another size is drawn after reallocating`() = runComposeUiTest {
        val source = FakeFrameSource().apply { frames.value = solidFrame(4, 2, RED) }
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(source, Modifier.fillMaxSize(), VideoScaleMode.Fit, pictureSizeKnown = false)
            }
        }
        waitForIdle()

        source.frames.value = solidFrame(16, 8, GREEN)
        waitForIdle()

        val pixels: PixelMap = capture()
        assertEquals(Color.Green, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun `fit letterboxes a wide frame and crop fills the box`() = runComposeUiTest {
        val source = FakeFrameSource().apply { frames.value = solidFrame(4, 2, RED) }
        var mode by mutableStateOf(VideoScaleMode.Fit)
        setContent {
            Box(Modifier.size(60.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(source, Modifier.fillMaxSize(), mode, pictureSizeKnown = false)
            }
        }
        waitForIdle()
        var pixels: PixelMap = capture()
        assertEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
        assertEquals(Color.White, pixels[pixels.width / 2, 1], "the bar above a letterboxed picture")

        mode = VideoScaleMode.Crop
        waitForIdle()
        pixels = capture()
        assertEquals(Color.Red, pixels[pixels.width / 2, 1])
    }

    @Test
    fun `a null frame clears the picture`() = runComposeUiTest {
        val source = FakeFrameSource().apply { frames.value = solidFrame(4, 2, RED) }
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(source, Modifier.fillMaxSize(), VideoScaleMode.Fill, pictureSizeKnown = false)
            }
        }
        waitForIdle()

        source.frames.value = null
        waitForIdle()

        val pixels: PixelMap = capture()
        assertEquals(Color.White, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun `a malformed frame is skipped rather than crashing`() = runComposeUiTest {
        val source = FakeFrameSource()
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(source, Modifier.fillMaxSize(), VideoScaleMode.Fill, pictureSizeKnown = false)
            }
        }
        waitForIdle()

        source.frames.value = VideoFrame(4, 2, IntArray(3) { RED }) // too few pixels
        waitForIdle()
        source.frames.value = VideoFrame(0, 2, IntArray(0))
        waitForIdle()

        val pixels: PixelMap = capture()
        assertNotEquals(Color.Red, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun `no frame source draws nothing`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(80.dp, 40.dp).background(Color.White).testTag("host")) {
                FrameSourceSurface(null, Modifier.fillMaxSize(), VideoScaleMode.Fit, pictureSizeKnown = false)
            }
        }
        waitForIdle()

        val pixels: PixelMap = capture()
        assertEquals(Color.White, pixels[pixels.width / 2, pixels.height / 2])
    }

    @Test
    fun `without a provided factory rememberVideoPlayer names the fix`() {
        val error: IllegalStateException = assertFailsWith<IllegalStateException> {
            runComposeUiTest {
                setContent { rememberVideoPlayer(SOURCE_A) }
                waitForIdle()
            }
        }
        val message: String = error.message.orEmpty()
        assertTrue("LocalVideoPlayerFactory" in message, message)
        assertTrue("kmptoolkit-video-player-vlcj" in message, message)
    }

    private companion object {
        const val RED: Int = 0xFFFF0000.toInt()
        const val GREEN: Int = 0xFF00FF00.toInt()
        const val BLUE_WITHOUT_ALPHA: Int = 0x000000FF
    }
}
