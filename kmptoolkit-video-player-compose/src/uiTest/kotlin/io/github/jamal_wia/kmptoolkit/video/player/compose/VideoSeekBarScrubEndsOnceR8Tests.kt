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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 8, Q4: `VideoSeekBar` ends a scrub with `onScrub(null)` EXACTLY ONCE per drag, in every way a drag can
 * end (docs 04-api-reference, VideoSeekBar), and never calls it when no drag is open. Complements
 * [VideoSeekBarCancelledDragTests], which checks that the scrub ends; these check how many times.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoSeekBarScrubEndsOnceR8Tests {

    private fun List<Long?>.endCount(): Int = count { scrub: Long? -> scrub == null }

    @Test
    fun `a drag that ends normally ends the scrub once and seeks once`() = runComposeUiTest {
        val seeks: MutableList<Long> = mutableListOf()
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 6_000L,
                    durationMs = 60_000L,
                    onSeek = { seeks += it },
                    onScrub = { scrubs += it },
                    contentDescription = "Normal",
                )
            }
        }
        onNodeWithContentDescription("Normal").performTouchInput {
            down(centerLeft)
            moveTo(center)
            up()
        }
        waitForIdle()

        assertEquals(1, seeks.size, "seeks: $seeks")
        assertEquals(1, scrubs.endCount(), "scrubs: $scrubs")
        assertEquals(null, scrubs.last(), "the end comes last: $scrubs")
        assertTrue(scrubs.dropLast(1).all { scrub: Long? -> scrub != null }, "scrubs: $scrubs")
    }

    @Test
    fun `a layout direction flip under the finger ends the scrub exactly once`() = runComposeUiTest {
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

        direction = LayoutDirection.Rtl
        waitForIdle()
        onNodeWithContentDescription("Flip").performTouchInput { up() }
        waitForIdle()

        assertEquals(1, scrubs.endCount(), "scrubs: $scrubs")
        assertEquals(emptyList(), seeks, "a cancelled drag seeked")
    }

    @Test
    fun `leaving the composition under the finger ends the scrub exactly once and seeks nothing`() = runComposeUiTest {
        var shown: Boolean by mutableStateOf(true)
        val seeks: MutableList<Long> = mutableListOf()
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            if (shown) {
                Box(Modifier.size(300.dp, 48.dp)) {
                    VideoSeekBar(
                        positionMs = 6_000L,
                        durationMs = 60_000L,
                        onSeek = { seeks += it },
                        onScrub = { scrubs += it },
                        contentDescription = "Leave",
                    )
                }
            }
        }
        onNodeWithContentDescription("Leave").performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        waitForIdle()
        assertTrue(scrubs.isNotEmpty() && scrubs.last() != null, "scrubs: $scrubs")

        shown = false
        waitForIdle()

        assertEquals(1, scrubs.endCount(), "the owner of the scrub was never told it ended: $scrubs")
        assertEquals(emptyList(), seeks, "a drag cut off by leaving composition seeked")
    }

    @Test
    fun `a bar disabled under the finger ends the scrub exactly once`() = runComposeUiTest {
        var enabled: Boolean by mutableStateOf(true)
        val seeks: MutableList<Long> = mutableListOf()
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 6_000L,
                    durationMs = 60_000L,
                    onSeek = { seeks += it },
                    onScrub = { scrubs += it },
                    enabled = enabled,
                    contentDescription = "Disable",
                )
            }
        }
        onNodeWithContentDescription("Disable").performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        waitForIdle()

        enabled = false
        waitForIdle()
        onNodeWithContentDescription("Disable").performTouchInput { up() }
        waitForIdle()

        assertEquals(1, scrubs.endCount(), "scrubs: $scrubs")
        assertEquals(emptyList(), seeks, "a drag on a disabled bar seeked")
    }

    @Test
    fun `a bar that leaves the composition with no drag open never reports a scrub`() = runComposeUiTest {
        var shown: Boolean by mutableStateOf(true)
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            if (shown) {
                Box(Modifier.size(300.dp, 48.dp)) {
                    VideoSeekBar(
                        positionMs = 6_000L,
                        durationMs = 60_000L,
                        onSeek = {},
                        onScrub = { scrubs += it },
                        contentDescription = "Idle",
                    )
                }
            }
        }
        waitForIdle()

        shown = false
        waitForIdle()

        assertEquals(emptyList(), scrubs, "no scrub was open, so none may be reported")
    }

    @Test
    fun `two drags in a row each end once`() = runComposeUiTest {
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 6_000L,
                    durationMs = 60_000L,
                    onSeek = {},
                    onScrub = { scrubs += it },
                    contentDescription = "Twice",
                )
            }
        }
        repeat(2) {
            onNodeWithContentDescription("Twice").performTouchInput {
                down(centerLeft)
                moveTo(center)
                up()
            }
            waitForIdle()
        }

        assertEquals(2, scrubs.endCount(), "scrubs: $scrubs")
    }
}
