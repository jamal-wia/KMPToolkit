package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The controls contract: which control shows in which state, that every action reaches the player,
 * showing and hiding, the seek bar's drag semantics, and that each part can be replaced.
 * Run on Android (Robolectric) and desktop through thin subclasses.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoControlsUiTests {

    private fun seekCalls(player: FakeVideoPlayer): List<String> = player.calls.filter { it.startsWith("seekTo") }

    @Test
    fun `while paused the centre button plays and becomes a pause button`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused(positionMs = 5_000L) }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()

        assertTrue("play" in player.calls, "calls: ${player.calls}")
        onNodeWithContentDescription("Pause").assertExists()
        onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun `while playing the centre button pauses`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Pause").performClick()
        waitForIdle()

        assertEquals(listOf("pause"), player.calls)
        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `after completion the centre button replays`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { state.value = VideoPlayerState.Completed(100_000L) }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithContentDescription("Replay").performClick()

        assertEquals(listOf("replay"), player.calls)
    }

    @Test
    fun `with nothing loaded the controls cannot act`() = runComposeUiTest {
        val player = FakeVideoPlayer()
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Play").assertIsNotEnabled()
        onNodeWithContentDescription("Forward").assertIsNotEnabled()
        onNodeWithContentDescription("Seek").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        // Sound can be muted before anything is loaded; the player keeps the setting.
        onNodeWithContentDescription("Mute").assertIsEnabled()
    }

    @Test
    fun `while preparing a buffering indicator takes the centre`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { state.value = VideoPlayerState.Preparing }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Buffering").assertExists()
        onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun `buffering shows the indicator even while the controls are hidden`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent { TestHost { DefaultPlayer(player) } }
        tapPicture()
        onNodeWithContentDescription("Pause").assertDoesNotExist()

        player.buffering.value = true
        waitForIdle()

        onNodeWithContentDescription("Buffering").assertExists()
    }

    @Test
    fun `the seek buttons seek by the configured step`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(labels = LABELS, seekStepMs = 5_000L)
                }
            }
        }

        onNodeWithContentDescription("Forward").performClick()
        onNodeWithContentDescription("Backward").performClick()

        assertEquals(listOf("seekForward(5000)", "seekBackward(5000)"), player.calls)
    }

    @Test
    fun `the mute button mutes, then unmutes`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Mute").performClick()
        waitForIdle()
        onNodeWithContentDescription("Unmute").performClick()

        assertEquals(listOf("setMuted(true)", "setMuted(false)"), player.calls)
    }

    @Test
    fun `the speed button steps to the next speed`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Speed").performClick()
        waitForIdle()
        onNodeWithContentDescription("Speed").performClick()

        assertEquals(listOf("setPlaybackSpeed(1.25)", "setPlaybackSpeed(1.5)"), player.calls)
    }

    @Test
    fun `there is no fullscreen button without a callback`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
    }

    @Test
    fun `the fullscreen button calls back and follows isFullscreen`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        var clicks = 0
        var fullscreen by mutableStateOf(false)
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(
                        labels = LABELS,
                        onFullscreenClick = { clicks++ },
                        isFullscreen = fullscreen,
                    )
                }
            }
        }

        onNodeWithContentDescription("Enter fullscreen").performClick()
        fullscreen = true
        waitForIdle()

        assertEquals(1, clicks)
        onNodeWithContentDescription("Exit fullscreen").assertExists()
        assertTrue(player.calls.isEmpty(), "fullscreen must not touch the player: ${player.calls}")
    }

    @Test
    fun `hidden parts are not drawn`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(
                        labels = LABELS,
                        showSeekButtons = false,
                        showMuteButton = false,
                        showSpeedButton = false,
                    )
                }
            }
        }

        onNodeWithContentDescription("Forward").assertDoesNotExist()
        onNodeWithContentDescription("Mute").assertDoesNotExist()
        onNodeWithContentDescription("Speed").assertDoesNotExist()
        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `a custom controls slot replaces the default controls and drives the player`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused(durationMs = 42_000L) }
        var seenDuration = -1L
        var seenPlayer: Any? = null
        setContent {
            TestHost {
                VideoPlayer(player) {
                    seenDuration = durationMs
                    seenPlayer = this.player
                    Box(Modifier.size(48.dp).testTag("custom").clickable { togglePlayPause() })
                }
            }
        }

        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithTag("custom").performClick()

        assertEquals(listOf("play"), player.calls)
        assertEquals(42_000L, seenDuration)
        assertSame(player, seenPlayer)
    }

    @Test
    fun `a tap on the picture hides and shows the controls`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        setContent { TestHost { DefaultPlayer(player) } }
        onNodeWithContentDescription("Play").assertExists()

        tapPicture()
        onNodeWithContentDescription("Play").assertDoesNotExist()

        tapPicture()
        onNodeWithContentDescription("Play").assertExists()
        assertTrue(player.calls.isEmpty(), "a tap on the picture must not touch the player: ${player.calls}")
    }

    @Test
    fun `while playing the controls hide after the delay`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying() }
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Pause").assertExists()

        mainClock.advanceTimeBy(2_000L)
        onNodeWithContentDescription("Pause").assertExists()

        mainClock.advanceTimeBy(1_500L + FADE_MS)
        onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `an action restarts the auto hide countdown`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying() }
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
            }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeBy(2_000L)

        onNodeWithContentDescription("Mute").performClick()
        mainClock.advanceTimeBy(2_000L)
        onNodeWithContentDescription("Pause").assertExists()

        mainClock.advanceTimeBy(1_500L + FADE_MS)
        onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `while paused the controls do not hide on their own`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused() }
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
            }
        }
        mainClock.advanceTimeBy(10_000L)

        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `controls hidden while playing come back when playback stops`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying() }
        setContent { TestHost { DefaultPlayer(player) } }
        tapPicture()
        onNodeWithContentDescription("Pause").assertDoesNotExist()

        player.state.value = VideoPlayerState.Paused(100_000L, 1_000L)
        waitForIdle()

        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `dragging the seek bar seeks once, at the end, and ignores position updates meanwhile`() =
        runComposeUiTest {
            val player = FakeVideoPlayer().apply { startPaused(durationMs = 100_000L, positionMs = 10_000L) }
            setContent { TestHost { DefaultPlayer(player) } }

            onNodeWithContentDescription("Seek").performTouchInput {
                down(centerLeft)
                moveTo(Offset(width * 0.25f, centerY))
                moveTo(center)
            }
            player.position.value = 90_000L
            waitForIdle()

            assertEquals(emptyList(), seekCalls(player), "no seek before the finger lifts")
            onNodeWithContentDescription("Seek").assert(progressIs(0.5f))

            onNodeWithContentDescription("Seek").performTouchInput { up() }
            waitForIdle()

            // Once, near the middle: the finger's final position is subject to pixel rounding.
            val seeks: List<String> = seekCalls(player)
            assertEquals(1, seeks.size, "seeks: $seeks")
            val target: Long = seeks.single().removePrefix("seekTo(").removeSuffix(")").toLong()
            assertTrue(target in 49_000L..51_000L, "seeked to $target")
        }

    @Test
    fun `after a seek the handle holds the target until the position moves`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply {
            startPaused(durationMs = 100_000L, positionMs = 10_000L)
            seekMovesPosition = false
        }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Seek").performTouchInput { click(center) }
        waitForIdle()

        assertEquals(listOf("seekTo(50000)"), seekCalls(player))
        onNodeWithContentDescription("Seek").assert(progressIs(0.5f))

        player.position.value = 52_000L
        waitForIdle()
        onNodeWithContentDescription("Seek").assert(progressIs(0.52f))
    }

    @Test
    fun `the seek bar can be set through accessibility`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPaused(durationMs = 100_000L) }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }

        assertEquals(listOf("seekTo(25000)"), seekCalls(player))
    }

    @Test
    fun `the seek bar cannot seek a source of unknown length`() = runComposeUiTest {
        val player = FakeVideoPlayer().apply { startPlaying(durationMs = 0L) }
        setContent { TestHost { DefaultPlayer(player) } }

        onNodeWithContentDescription("Seek").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))

        onNodeWithContentDescription("Seek").performTouchInput { click(center) }
        waitForIdle()

        assertEquals(emptyList(), seekCalls(player))
    }

    @Test
    fun `a building block works on its own, outside the default controls`() = runComposeUiTest {
        var seeks: List<Long> = emptyList()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 0L,
                    durationMs = 60_000L,
                    onSeek = { seeks = seeks + it },
                    contentDescription = "Standalone",
                )
            }
        }

        onNodeWithContentDescription("Standalone").performTouchInput { click(center) }

        assertEquals(listOf(30_000L), seeks)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.tapPicture() {
        // The top-left corner of the 400×300 host: picture only, no control there.
        onRoot().performTouchInput { click(Offset(20f, 20f)) }
        waitForIdle()
    }

    private fun progressIs(expected: Float): SemanticsMatcher =
        SemanticsMatcher("progress is $expected") { node ->
            val info: ProgressBarRangeInfo = node.config[SemanticsProperties.ProgressBarRangeInfo]
            // Tolerates the pixel rounding of a touch position.
            kotlin.math.abs(info.current - expected) < 0.01f
        }

    private companion object {
        /** Comfortably longer than the default fade-out. */
        const val FADE_MS: Long = 1_000L
    }
}

@androidx.compose.runtime.Composable
private fun DefaultPlayer(player: FakeVideoPlayer) {
    VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
}
