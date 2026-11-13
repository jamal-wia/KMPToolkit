package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `VideoSeekBar` promises `onScrub(null)` when a drag is cancelled, and that nothing stays frozen at the finger's
 * last position (docs 04-api-reference, VideoSeekBar KDoc). A change of the bar's layout direction or thumb size
 * restarts its gesture detector under the finger: that cancels the drag, and it must end the scrub without a seek.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoSeekBarCancelledDragTests {

    @Test
    fun `flipping the layout direction under the finger ends the scrub without a seek and freezes nothing`() =
        runComposeUiTest {
            var direction: LayoutDirection by mutableStateOf(LayoutDirection.Ltr)
            val seeks: MutableList<Long> = mutableListOf()
            val scrubs: MutableList<Long?> = mutableListOf()
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.size(300.dp, 48.dp)) {
                        VideoSeekBar(
                            positionMs = 6_000L,
                            durationMs = 60_000L,
                            onSeek = { seeks += it },
                            onScrub = { scrubs += it },
                            contentDescription = "Flip",
                        )
                    }
                }
            }
            onNodeWithContentDescription("Flip").performTouchInput {
                down(centerLeft)
                moveTo(center)
            }
            waitForIdle()
            assertTrue(scrubs.isNotEmpty() && scrubs.last() != null, "scrubs: $scrubs")

            direction = LayoutDirection.Rtl
            waitForIdle()
            onNodeWithContentDescription("Flip").performTouchInput { up() }
            waitForIdle()

            assertEquals(null, scrubs.last(), "the scrub never ended: $scrubs")
            assertEquals(emptyList(), seeks, "a cancelled drag seeked")
        }

    @Test
    fun `changing the thumb size under the finger ends the scrub without a seek and freezes nothing`() =
        runComposeUiTest {
            var thumbRadius: Dp by mutableStateOf(8.dp)
            val seeks: MutableList<Long> = mutableListOf()
            val scrubs: MutableList<Long?> = mutableListOf()
            setContent {
                Box(Modifier.size(300.dp, 48.dp)) {
                    VideoSeekBar(
                        positionMs = 6_000L,
                        durationMs = 60_000L,
                        onSeek = { seeks += it },
                        onScrub = { scrubs += it },
                        contentDescription = "Thumb",
                        thumbRadius = thumbRadius,
                    )
                }
            }
            onNodeWithContentDescription("Thumb").performTouchInput {
                down(centerLeft)
                moveTo(center)
            }
            waitForIdle()
            assertTrue(scrubs.isNotEmpty() && scrubs.last() != null, "scrubs: $scrubs")

            thumbRadius = 12.dp
            waitForIdle()
            onNodeWithContentDescription("Thumb").performTouchInput { up() }
            waitForIdle()

            assertEquals(null, scrubs.last(), "the scrub never ended: $scrubs")
            assertEquals(emptyList(), seeks, "a cancelled drag seeked")
        }
}
