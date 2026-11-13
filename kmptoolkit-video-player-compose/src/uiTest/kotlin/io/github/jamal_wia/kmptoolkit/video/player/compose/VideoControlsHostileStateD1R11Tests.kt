package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 11 (breaker ip), Q3: the controls layer under hostile state. (1) The KDoc promise of `VideoControlsScope`
 * ("reading it ... recomposes that reader when it changes, and nothing else") for EVERY property, not only
 * `isPlaying`/`durationMs`: ten position ticks recompose the position reader and none of the others, and each setter
 * recomposes the reader of its own property and of no other. (2) The player failing in the middle of a scrub. (3) A
 * zero-size host and a large font scale.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoControlsHostileStateD1R11Tests {

    private class Counts {
        val reads: MutableMap<String, Int> = linkedMapOf()
        fun bump(name: String) {
            reads[name] = (reads[name] ?: 0) + 1
        }

        fun snapshot(): Map<String, Int> = reads.toMap()
    }

    @Composable
    private fun Reader(counts: Counts, name: String, read: () -> Any?) {
        val value: Any? = read()
        // `value` may be null (videoSize): the read itself is what subscribes, the SideEffect counts the recomposition.
        @Suppress("UNUSED_VARIABLE") val keep: Any? = value
        SideEffect { counts.bump(name) }
    }

    private fun diff(before: Map<String, Int>, after: Map<String, Int>): Map<String, Int> =
        after.mapValues { (name: String, now: Int) -> now - (before[name] ?: 0) }.filterValues { it != 0 }

    @Test
    fun `position ticks recompose only the position reader and each setter only the reader of its own property`() = runComposeUiTest {
        val tp = TestPlayer().playing(0L)
        val counts = Counts()
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    Reader(counts, "position") { positionMs }
                    Reader(counts, "buffered") { bufferedPositionMs }
                    Reader(counts, "buffering") { isBuffering }
                    Reader(counts, "muted") { isMuted }
                    Reader(counts, "volume") { volume }
                    Reader(counts, "speed") { playbackSpeed }
                    Reader(counts, "repeat") { repeatMode }
                    Reader(counts, "size") { videoSize }
                    Reader(counts, "visible") { controlsVisible }
                    Reader(counts, "isPlaying") { isPlaying }
                    Reader(counts, "duration") { durationMs }
                }
            }
        }
        waitForIdle()

        // 1. Ticks: only the position reader.
        var before: Map<String, Int> = counts.snapshot()
        repeat(TICKS) { index: Int ->
            tp.movePlayhead((index + 1) * 1_000L)
            waitForIdle()
        }
        var changed: Map<String, Int> = diff(before, counts.snapshot())
        assertTrue((changed["position"] ?: 0) >= TICKS, "the harness does not see the ticks: $changed")
        assertEquals(setOf("position"), changed.keys, "KDoc: a reader recomposes only when its own property changes; ticks recomposed $changed")

        // 2. Each setter, one at a time: its own reader recomposes (at least once), nobody else does.
        val steps: List<Triple<String, () -> Unit, Set<String>>> = listOf(
            Triple("muted", { tp.player.setMuted(true) }, setOf("muted")),
            Triple("volume", { tp.player.setVolume(0.4f) }, setOf("volume")),
            Triple("speed", { tp.player.setPlaybackSpeed(2f) }, setOf("speed")),
            Triple("repeat", { tp.player.setRepeatMode(RepeatMode.One) }, setOf("repeat")),
            Triple("size", { tp.fake.reportVideoSize(VideoSize(640, 360)) }, setOf("size")),
            Triple("buffering", { tp.fake.reportBuffering(true) }, setOf("buffering")),
            Triple("buffered", { tp.fake.bufferedPositionMs = 42_000L; tp.movePlayhead(11_000L) }, setOf("buffered", "position")),
        )
        for ((name: String, act: () -> Unit, allowed: Set<String>) in steps) {
            before = counts.snapshot()
            act()
            waitForIdle()
            changed = diff(before, counts.snapshot())
            assertTrue(name in changed.keys, "the reader of '$name' did not recompose: $changed")
            assertTrue(allowed.containsAll(changed.keys), "changing '$name' recomposed readers of other properties: $changed")
        }
        tp.player.release()
    }

    @Test
    fun `the player failing in the middle of a scrub ends it cleanly and the bar neither throws nor seeks the failed player`() = runComposeUiTest {
        val tp = TestPlayer().playing(10_000L)
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
            }
        }
        waitForIdle()
        val bar = onNodeWithContentDescription(checkNotNull(LABELS.seekBar))
        bar.performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x + 40f))
        }
        waitForIdle()
        tp.fake.failPlayback(IllegalStateException("decoder died mid scrub"))
        waitForIdle()
        assertTrue(tp.state is VideoPlayerState.Error, "premise: the player failed, was ${tp.state}")
        bar.performTouchInput {
            moveTo(center.copy(x = center.x + 80f))
            up()
        }
        waitForIdle()
        assertTrue(tp.state is VideoPlayerState.Error, "a failed player stays failed after the scrub ends: ${tp.state}")
        assertTrue(tp.player.playbackPositionFlow.value >= 0L)
        onNodeWithContentDescription(checkNotNull(LABELS.seekBar)).assertExists()
        tp.player.release()
    }

    @Test
    fun `a zero size host and a large font scale compose the default controls without throwing and the buttons still act`() {
        runComposeUiTest {
            val tp = TestPlayer().playing(1_000L)
            setContent {
                Box(Modifier.size(0.dp)) {
                    TestHost {
                        VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                    }
                }
            }
            waitForIdle()
            assertTrue(tp.state is VideoPlayerState.Playing, "composing into a zero size box moved the player: ${tp.state}")
            tp.player.release()
        }
        runComposeUiTest {
            val tp = TestPlayer().playing(1_000L)
            setContent {
                val base: Density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 3f)) {
                    TestHost {
                        VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                    }
                }
            }
            waitForIdle()
            onNodeWithContentDescription(checkNotNull(LABELS.pause)).performClick()
            waitForIdle()
            assertTrue(tp.state is VideoPlayerState.Paused, "the pause button did not act at font scale 3: ${tp.state}")
            tp.player.release()
        }
    }

    private companion object {
        const val TICKS: Int = 10
    }
}
