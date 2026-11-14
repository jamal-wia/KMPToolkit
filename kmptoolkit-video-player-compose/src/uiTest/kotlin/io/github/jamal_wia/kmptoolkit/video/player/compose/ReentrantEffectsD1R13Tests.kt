package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D1 round 13, Q3: the compose layer under consumers whose own effects call the player from state collection, and the
 * recomposition count of every property of the controls scope per kind of change.
 */
@OptIn(ExperimentalTestApi::class)
abstract class ReentrantEffectsD1R13Tests {

    @Test
    fun `an effect that pauses and seeks from the first Playing leaves the controls showing the paused player at the target`() = runComposeUiTest {
        val tp = TestPlayer().prepare()
        var done = false
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                LaunchedEffect(Unit) {
                    tp.player.stateFlow.collect {
                        if (it is VideoPlayerState.Playing && !done) {
                            done = true
                            tp.player.pause()
                            tp.player.seekTo(30_000L)
                        }
                    }
                }
            }
        }
        waitForIdle()
        tp.player.play()
        waitUntil(timeoutMillis = WAIT_MS) { done }
        waitForIdle()

        assertEquals(VideoPlayerState.Paused(TestPlayer.DEFAULT_DURATION_MS, 30_000L), tp.state)
        assertEquals(false, tp.fake.isPlaying, "the engine is paused: ${tp.calls}")
        onNodeWithContentDescription(LABELS.play!!).assertExists()
        onNodeWithContentDescription(LABELS.pause!!).assertDoesNotExist()
        onNodeWithContentDescription(LABELS.seekBar!!).assertRangeInfoEquals(ProgressBarRangeInfo(0.3f, 0f..1f))
        tp.player.release()
    }

    @Test
    fun `an effect that releases the player from Playing leaves the controls on the idle labels and a click on them harmless`() = runComposeUiTest {
        val tp = TestPlayer().prepare()
        var released = false
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                LaunchedEffect(Unit) {
                    tp.player.stateFlow.collect {
                        if (it is VideoPlayerState.Playing && !released) {
                            released = true
                            tp.player.release()
                        }
                    }
                }
            }
        }
        waitForIdle()
        tp.player.play()
        waitUntil(timeoutMillis = WAIT_MS) { released }
        waitForIdle()

        assertEquals(VideoPlayerState.Idle, tp.state)
        assertEquals(1, tp.fake.disposeCount)
        assertEquals(false, tp.fake.isPlaying)
        onNodeWithContentDescription(LABELS.play!!).assertExists()
        val callsBefore: Int = tp.calls.size
        onNodeWithContentDescription(LABELS.play!!).performClick()
        waitForIdle()
        assertEquals(callsBefore, tp.calls.size, "a click on a released player's play reaches the engine: ${tp.calls}")
        assertEquals(VideoPlayerState.Idle, tp.state)
    }

    @Test
    fun `an immediate collector started by an effect that makes two calls from inside one update leaves flows engine and labels in agreement`() = runComposeUiTest {
        val tp = TestPlayer().playing(10_000L)
        var done = false
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.Unconfined) {
                        tp.player.stateFlow.collect {
                            if (it is VideoPlayerState.Paused && !done) {
                                done = true
                                // Two calls of one chain from inside the update that wrote Paused.
                                tp.player.seekTo(2_880L)
                                tp.player.play()
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        tp.player.pause()
        waitUntil(timeoutMillis = WAIT_MS) { done }
        waitForIdle()

        val state: VideoPlayerState = tp.state
        assertTrue(state is VideoPlayerState.Playing, "the chain's play stands: $state, calls ${tp.calls}")
        assertEquals(2_880L, (state as VideoPlayerState.Playing).currentPosition)
        assertEquals(true, tp.fake.isPlaying, "the engine plays: ${tp.calls}")
        onNodeWithContentDescription(LABELS.pause!!).assertExists()
        onNodeWithContentDescription(LABELS.play!!).assertDoesNotExist()
        tp.player.release()
    }

    private class Counts {
        var playerState: Int = 0
        var isPlaying: Int = 0
        var position: Int = 0
        var duration: Int = 0
        var buffered: Int = 0
        var buffering: Int = 0
        var muted: Int = 0
        var volume: Int = 0
        var speed: Int = 0
        var repeat: Int = 0
        var size: Int = 0
        var visible: Int = 0

        fun snapshot(): List<Int> = listOf(playerState, isPlaying, position, duration, buffered, buffering, muted, volume, speed, repeat, size, visible)

        companion object {
            val NAMES: List<String> = listOf("playerState", "isPlaying", "position", "duration", "buffered", "buffering", "muted", "volume", "speed", "repeat", "size", "visible")
        }
    }

    @Test
    fun `every property of the controls scope is recomposed only by a change of its own value`() = runComposeUiTest {
        val tp = TestPlayer().playing(0L)
        val counts = Counts()
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    CountedRead({ counts.playerState++ }) { playerState }
                    CountedRead({ counts.isPlaying++ }) { isPlaying }
                    CountedRead({ counts.position++ }) { positionMs }
                    CountedRead({ counts.duration++ }) { durationMs }
                    CountedRead({ counts.buffered++ }) { bufferedPositionMs }
                    CountedRead({ counts.buffering++ }) { isBuffering }
                    CountedRead({ counts.muted++ }) { isMuted }
                    CountedRead({ counts.volume++ }) { volume }
                    CountedRead({ counts.speed++ }) { playbackSpeed }
                    CountedRead({ counts.repeat++ }) { repeatMode }
                    CountedRead({ counts.size++ }) { videoSize ?: "none" }
                    CountedRead({ counts.visible++ }) { controlsVisible }
                }
            }
        }
        waitForIdle()

        // Only the playhead ticks: the buffered position, buffering, size, settings and visibility readers do not move.
        var before: List<Int> = counts.snapshot()
        repeat(5) { index: Int ->
            tp.movePlayhead((index + 1) * 1_000L)
            waitForIdle()
        }
        var delta: List<Int> = counts.snapshot().zip(before) { now, then -> now - then }
        val onlyTicks: Map<String, Int> = Counts.NAMES.zip(delta).toMap()
        assertTrue(onlyTicks.getValue("position") >= 5 && onlyTicks.getValue("playerState") >= 5, "the harness sees ticks: $onlyTicks")
        for (name: String in listOf("isPlaying", "duration", "buffered", "buffering", "muted", "volume", "speed", "repeat", "size", "visible")) {
            assertEquals(0, onlyTicks.getValue(name), "a tick of the playhead recomposed the $name reader: $onlyTicks")
        }

        // One settings change moves its own reader and no other.
        fun only(name: String, change: () -> Unit) {
            val start: List<Int> = counts.snapshot()
            change()
            waitForIdle()
            val moved: Map<String, Int> = Counts.NAMES.zip(counts.snapshot().zip(start) { now, then -> now - then }).toMap()
            assertTrue(moved.getValue(name) >= 1, "the $name reader did not move: $moved")
            val others: Map<String, Int> = moved.filterKeys { it != name && it != "playerState" && it != "position" }.filterValues { it != 0 }
            assertEquals(emptyMap(), others, "changing $name recomposed other readers: $moved")
        }
        only("muted") { tp.player.setMuted(true) }
        only("volume") { tp.player.setVolume(0.5f) }
        only("speed") { tp.player.setPlaybackSpeed(2f) }
        only("repeat") { tp.player.setRepeatMode(RepeatMode.One) }
        only("size") { tp.fake.reportVideoSize(VideoSize(640, 360)) }
        only("buffering") { tp.fake.reportBuffering(true) }
        tp.player.release()
    }

    private companion object {
        const val WAIT_MS: Long = 10_000L
    }
}

@Composable
private fun CountedRead(onComposed: () -> Unit, read: () -> Any?) {
    val value: Any? = read()
    SideEffect { if (value == null) error("a read of the scope returned null") else onComposed() }
}
