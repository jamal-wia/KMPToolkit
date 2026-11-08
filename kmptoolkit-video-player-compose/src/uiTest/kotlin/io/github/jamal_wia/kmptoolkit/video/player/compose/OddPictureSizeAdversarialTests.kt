package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pictures far from any real video's shape (a panorama strip, a one-pixel-wide column, within what a layout
 * constraint can represent): the surface must lay out any of them in every scale mode, in a bounded box and in an
 * unbounded one, without throwing out of measure.
 */
@OptIn(ExperimentalTestApi::class)
abstract class OddPictureSizeAdversarialTests {

    private val oddSizes: List<VideoSize> = listOf(
        VideoSize(100, 1),
        VideoSize(1, 100),
        VideoSize(4_000, 3),
        VideoSize(3, 4_000),
        VideoSize(1, 1),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.layOut(mode: VideoScaleMode, size: VideoSize, bounded: Boolean) {
        val player = TestPlayer().apply { fake.videoSizeOnLoad = size }.prepare()
        setContent {
            TestHost {
                if (bounded) {
                    Box(Modifier.size(100.dp)) {
                        VideoPlayerSurface(player.player, Modifier.fillMaxSize(), scaleMode = mode)
                    }
                } else {
                    Column(Modifier.width(100.dp).verticalScroll(rememberScrollState())) {
                        VideoPlayerSurface(player.player, Modifier.fillMaxWidth().testTag("surface"), scaleMode = mode)
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun failures(bounded: Boolean): List<String> {
        val failed: MutableList<String> = mutableListOf()
        for (mode in VideoScaleMode.entries) {
            for (size in oddSizes) {
                try {
                    runComposeUiTest { layOut(mode, size, bounded) }
                } catch (t: Throwable) {
                    failed += "$mode $size: ${t::class.simpleName}: ${t.message}"
                }
            }
        }
        return failed
    }

    @Test
    fun `every scale mode lays out a picture of any positive size in a bounded box`() {
        val failed: List<String> = failures(bounded = true)
        assertTrue(failed.isEmpty(), "layout threw for: $failed")
    }

    @Test
    fun `every scale mode lays out a picture of any positive size in an unbounded height`() {
        val failed: List<String> = failures(bounded = false)
        assertTrue(failed.isEmpty(), "layout threw for: $failed")
    }
}
