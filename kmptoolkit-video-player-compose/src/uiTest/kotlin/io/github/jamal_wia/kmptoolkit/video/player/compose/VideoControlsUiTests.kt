package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The controls contract: which control shows in which state, that every action reaches the player,
 * showing and hiding, the seek bar's drag semantics, and that each part can be replaced. The player
 * is the library's own, over the fake engine (see [TestPlayer]); what the controls did is read back
 * from the engine's recorded calls and the player's state.
 * Run on Android (Robolectric) and desktop through thin subclasses.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoControlsUiTests {

    private fun seekCalls(player: TestPlayer): List<String> = player.calls.filter { it.startsWith("seekTo") }

    // --- The centre button ---

    @Test
    fun `while paused the centre button plays and becomes a pause button`() = runComposeUiTest {
        val player = TestPlayer().paused(positionMs = 5_000L)
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Play").performClick()
        waitForIdle()

        assertEquals(listOf("start"), player.calls)
        assertIs<VideoPlayerState.Playing>(player.state)
        onNodeWithContentDescription("Pause").assertExists()
        onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun `while playing the centre button pauses`() = runComposeUiTest {
        val player = TestPlayer().playing()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Pause").performClick()
        waitForIdle()

        assertEquals(listOf("pause"), player.calls)
        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `after completion the centre button replays from the start`() = runComposeUiTest {
        val player = TestPlayer().playing(positionMs = 40_000L)
        player.fake.completePlayback()
        player.engine.calls.clear()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithContentDescription("Replay").performClick()
        waitForIdle()

        assertEquals(listOf("seekTo(0)", "start"), player.calls)
        assertEquals(VideoPlayerState.Playing(TestPlayer.DEFAULT_DURATION_MS, 0L), player.state)
    }

    @Test
    fun `togglePlayPause decides on the player's current state, not on the last composed one`() =
        runComposeUiTest {
            val player = TestPlayer().playing()
            var scope: VideoControlsScope? = null
            setContent {
                TestHost { VideoPlayer(player.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { scope = this } }
            }
            waitForIdle()

            // Two taps before anything recomposes: the second must see the pause the first made.
            runOnIdle {
                scope!!.togglePlayPause()
                scope!!.togglePlayPause()
            }
            assertEquals(listOf("pause", "start"), player.calls)
            assertIs<VideoPlayerState.Playing>(player.state)

            // And from the end, a toggle replays rather than resuming nothing.
            player.fake.completePlayback()
            player.engine.calls.clear()
            runOnIdle { scope!!.togglePlayPause() }
            assertEquals(listOf("seekTo(0)", "start"), player.calls)
        }

    // --- What can act when ---

    @Test
    fun `with nothing loaded the controls cannot act`() = runComposeUiTest {
        val player = TestPlayer()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Play").assertIsNotEnabled()
        onNodeWithContentDescription("Forward").assertIsNotEnabled()
        onNodeWithContentDescription("Seek").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        // Sound can be muted before anything is loaded; the player keeps the setting.
        onNodeWithContentDescription("Mute").assertIsEnabled()
    }

    @Test
    fun `after a playback failure the transport cannot act`() = runComposeUiTest {
        val player = TestPlayer().playing(positionMs = 10_000L)
        setContent { TestHost { DefaultPlayer(player.player) } }

        player.fake.failPlayback(IllegalStateException("decoder died"))
        waitForIdle()

        assertIs<VideoPlayerState.Error>(player.state)
        onNodeWithContentDescription("Play").assertIsNotEnabled()
        onNodeWithContentDescription("Forward").assertIsNotEnabled()
        onNodeWithContentDescription("Backward").assertIsNotEnabled()
        onNodeWithContentDescription("Speed").assertIsNotEnabled()
        onNodeWithContentDescription("Seek").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        onNodeWithContentDescription("Seek").performTouchInput { click(center) }
        waitForIdle()
        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `while preparing a buffering indicator takes the centre`() = runComposeUiTest {
        val player = TestPlayer()
        val loading: Job = player.startPreparing()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Buffering").assertExists()
        onNodeWithContentDescription("Play").assertDoesNotExist()
        loading.cancel()
    }

    @Test
    fun `buffering shows the indicator even while the controls are hidden`() = runComposeUiTest {
        val player = TestPlayer().playing()
        setContent { TestHost { DefaultPlayer(player.player) } }
        tapPicture()
        onNodeWithContentDescription("Pause").assertDoesNotExist()

        player.fake.reportBuffering(true)
        waitForIdle()

        onNodeWithContentDescription("Buffering").assertExists()
    }

    // --- The other buttons ---

    @Test
    fun `the seek buttons seek by the configured step`() = runComposeUiTest {
        val player = TestPlayer().paused(positionMs = 20_000L)
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    DefaultVideoControls(labels = LABELS, seekStepMs = 5_000L)
                }
            }
        }

        onNodeWithContentDescription("Forward").performClick()
        onNodeWithContentDescription("Backward").performClick()
        onNodeWithContentDescription("Backward").performClick()

        assertEquals(listOf("seekTo(25000)", "seekTo(20000)", "seekTo(15000)"), player.calls)
    }

    @Test
    fun `the mute button mutes, then unmutes`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Mute").performClick()
        waitForIdle()
        assertTrue(player.player.isMutedFlow.value)
        assertEquals(0f, player.fake.appliedVolume)

        onNodeWithContentDescription("Unmute").performClick()
        waitForIdle()
        assertFalse(player.player.isMutedFlow.value)
        assertEquals(1f, player.fake.appliedVolume)
    }

    @Test
    fun `the speed button steps to the next speed`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Speed").performClick()
        waitForIdle()
        assertEquals(1.25f, player.player.playbackSpeedFlow.value)

        onNodeWithContentDescription("Speed").performClick()
        waitForIdle()
        assertEquals(1.5f, player.player.playbackSpeedFlow.value)
    }

    @Test
    fun `there is no fullscreen button without a callback`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
        onNodeWithContentDescription("Exit fullscreen").assertDoesNotExist()
    }

    @Test
    fun `the fullscreen button calls back and follows isFullscreen`() = runComposeUiTest {
        val player = TestPlayer().paused()
        var clicks = 0
        var fullscreen by mutableStateOf(false)
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
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
        assertIs<VideoPlayerState.Paused>(player.state)
    }

    @Test
    fun `hidden parts are not drawn`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
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
        val player = TestPlayer(durationMs = 42_000L).paused()
        var seenDuration = -1L
        var seenPlayer: Any? = null
        setContent {
            TestHost {
                VideoPlayer(player.player) {
                    seenDuration = durationMs
                    seenPlayer = this.player
                    Box(Modifier.size(48.dp).testTag("custom").clickable { togglePlayPause() })
                }
            }
        }

        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithTag("custom").performClick()

        assertEquals(listOf("start"), player.calls)
        assertEquals(42_000L, seenDuration)
        assertSame(player.player, seenPlayer)
    }

    // --- Showing and hiding ---

    @Test
    fun `a tap on the picture hides and shows the controls`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent { TestHost { DefaultPlayer(player.player) } }
        onNodeWithContentDescription("Play").assertExists()

        tapPicture()
        onNodeWithContentDescription("Play").assertDoesNotExist()

        tapPicture()
        onNodeWithContentDescription("Play").assertExists()
        assertTrue(player.calls.isEmpty(), "a tap on the picture must not touch the player: ${player.calls}")
    }

    @Test
    fun `while playing the controls hide after the delay`() = runComposeUiTest {
        val player = TestPlayer().playing()
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
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
        val player = TestPlayer().playing()
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
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
    fun `setInteracting holds the controls open until it is cleared`() = runComposeUiTest {
        val player = TestPlayer().playing()
        var scope: VideoControlsScope? = null
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = 3_000L) {
                    scope = this
                    DefaultVideoControls(labels = LABELS)
                }
            }
        }
        mainClock.advanceTimeByFrame()

        // Outside an input event nothing flushes the write before the paused clock jumps ahead;
        // a real app's snapshot manager does that on the next main-thread turn.
        runOnIdle {
            scope!!.setInteracting(true)
            Snapshot.sendApplyNotifications()
        }
        mainClock.advanceTimeBy(10_000L)
        onNodeWithContentDescription("Pause").assertExists()

        runOnIdle {
            scope!!.setInteracting(false)
            Snapshot.sendApplyNotifications()
        }
        mainClock.advanceTimeBy(2_000L)
        onNodeWithContentDescription("Pause").assertExists("the countdown restarts when the interaction ends")
        mainClock.advanceTimeBy(1_500L + FADE_MS)
        onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `while paused the controls do not hide on their own`() = runComposeUiTest {
        val player = TestPlayer().paused()
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
            }
        }
        mainClock.advanceTimeBy(10_000L)

        onNodeWithContentDescription("Play").assertExists()
    }

    @Test
    fun `controls hidden while playing come back when playback stops`() = runComposeUiTest {
        val player = TestPlayer().playing()
        setContent { TestHost { DefaultPlayer(player.player) } }
        tapPicture()
        onNodeWithContentDescription("Pause").assertDoesNotExist()

        player.player.pause()
        waitForIdle()

        onNodeWithContentDescription("Play").assertExists()
    }

    // --- The seek bar ---

    @Test
    fun `dragging the seek bar seeks once, at the end, and ignores position updates meanwhile`() =
        runComposeUiTest {
            val player = TestPlayer().playing(positionMs = 10_000L)
            setContent { TestHost { DefaultPlayer(player.player) } }

            onNodeWithContentDescription("Seek").performTouchInput {
                down(centerLeft)
                moveTo(Offset(width * 0.25f, centerY))
                moveTo(center)
            }
            player.movePlayhead(90_000L)
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
    fun `while dragging the position label shows the time under the finger`() = runComposeUiTest {
        val player = TestPlayer().paused(positionMs = 10_000L)
        setContent { TestHost { DefaultPlayer(player.player) } }
        onNodeWithText("0:10").assertExists()

        onNodeWithContentDescription("Seek").performTouchInput {
            down(centerLeft)
            moveTo(Offset(width * 0.25f, centerY))
            moveTo(center)
        }
        waitForIdle()

        onNodeWithText("0:10").assertDoesNotExist()
        // The label is the time under the finger, subject to pixel rounding around 0:50.
        val scrubbed: Boolean = listOf("0:49", "0:50", "0:51").any { label ->
            onAllNodesWithTextCount(label) > 0
        }
        assertTrue(scrubbed, "no scrub time shown")

        onNodeWithContentDescription("Seek").performTouchInput { up() }
        waitForIdle()
    }

    @Test
    fun `a drag interrupted by a failure leaves nothing frozen behind`() = runComposeUiTest {
        val player = TestPlayer().playing(positionMs = 10_000L)
        var scope: VideoControlsScope? = null
        setContent {
            TestHost {
                VideoPlayer(player.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    scope = this
                    DefaultVideoControls(labels = LABELS)
                }
            }
        }
        onNodeWithContentDescription("Seek").performTouchInput {
            down(centerLeft)
            moveTo(Offset(width * 0.25f, centerY))
            moveTo(center)
        }
        waitForIdle()

        // The bar stops being draggable under the finger.
        player.fake.failPlayback(IllegalStateException("network lost"))
        waitForIdle()
        onNodeWithContentDescription("Seek").performTouchInput { up() }
        waitForIdle()

        assertEquals(emptyList(), seekCalls(player), "a cancelled drag must not seek")
        onNodeWithContentDescription("Seek").assert(progressIs(0f))
        onNodeWithText("0:10").assertExists() // the position, not the frozen scrub time
        listOf("0:49", "0:50", "0:51").forEach { label -> assertEquals(0, onAllNodesWithTextCount(label), label) }

        // The next source starts from a clean bar, and auto-hide works again.
        player.prepare(SOURCE_B)
        player.player.play()
        waitForIdle()
        onNodeWithContentDescription("Seek").assert(progressIs(0f))
        val impl: VideoControlsScopeImpl = assertIs<VideoControlsScopeImpl>(scope)
        assertTrue(impl.shouldAutoHide, "the interrupted drag still holds the controls open")
    }

    @Test
    fun `a seek bar disabled mid drag cancels the drag without seeking`() = runComposeUiTest {
        var enabled by mutableStateOf(true)
        val seeks: MutableList<Long> = mutableListOf()
        val scrubs: MutableList<Long?> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = 6_000L,
                    durationMs = 60_000L,
                    onSeek = { seeks += it },
                    enabled = enabled,
                    onScrub = { scrubs += it },
                    contentDescription = "Standalone",
                )
            }
        }
        onNodeWithContentDescription("Standalone").performTouchInput {
            down(centerLeft)
            moveTo(center)
        }
        waitForIdle()
        assertTrue(scrubs.isNotEmpty() && scrubs.last() != null, "scrubs: $scrubs")

        enabled = false
        waitForIdle()
        onNodeWithContentDescription("Standalone").performTouchInput { up() }
        waitForIdle()

        assertEquals(null, scrubs.last())
        assertEquals(emptyList(), seeks)
        onNodeWithContentDescription("Standalone").assert(progressIs(0.1f))
    }

    @Test
    fun `a drag taken over by another gesture reports the end of the scrub and does not seek`() =
        runComposeUiTest {
            val seeks: MutableList<Long> = mutableListOf()
            val scrubs: MutableList<Long?> = mutableListOf()
            var stealing = false
            setContent {
                // A parent that claims the pointer mid-drag, as an enclosing pager or sheet does.
                Box(
                    Modifier.size(300.dp, 48.dp).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event: PointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                                if (stealing) event.changes.forEach { it.consume() }
                            }
                        }
                    },
                ) {
                    VideoSeekBar(
                        positionMs = 6_000L,
                        durationMs = 60_000L,
                        onSeek = { seeks += it },
                        onScrub = { scrubs += it },
                        contentDescription = "Standalone",
                    )
                }
            }

            onNodeWithContentDescription("Standalone").performTouchInput {
                down(centerLeft)
                moveTo(center)
            }
            waitForIdle()
            assertTrue(scrubs.isNotEmpty() && scrubs.last() != null, "scrubs: $scrubs")

            stealing = true
            onNodeWithContentDescription("Standalone").performTouchInput {
                moveTo(Offset(width * 0.75f, centerY))
                up()
            }
            waitForIdle()

            assertEquals(null, scrubs.last())
            assertEquals(emptyList(), seeks)
            onNodeWithContentDescription("Standalone").assert(progressIs(0.1f))
        }

    @Test
    fun `after a seek the handle holds the target until the position moves`() = runComposeUiTest {
        // A platform still seeking keeps reporting the old position for a while.
        var position by mutableLongStateOf(10_000L)
        val seeks: MutableList<Long> = mutableListOf()
        setContent {
            Box(Modifier.size(300.dp, 48.dp)) {
                VideoSeekBar(
                    positionMs = position,
                    durationMs = 100_000L,
                    onSeek = { seeks += it },
                    contentDescription = "Standalone",
                )
            }
        }

        onNodeWithContentDescription("Standalone").performTouchInput { click(center) }
        waitForIdle()

        assertEquals(listOf(50_000L), seeks)
        onNodeWithContentDescription("Standalone").assert(progressIs(0.5f))

        position = 52_000L
        waitForIdle()
        onNodeWithContentDescription("Standalone").assert(progressIs(0.52f))
    }

    @Test
    fun `the seek bar can be set through accessibility`() = runComposeUiTest {
        val player = TestPlayer().paused()
        setContent { TestHost { DefaultPlayer(player.player) } }

        onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }

        assertEquals(listOf("seekTo(25000)"), seekCalls(player))
    }

    @Test
    fun `the seek bar cannot seek a source of unknown length`() = runComposeUiTest {
        val player = TestPlayer(durationMs = 0L).playing()
        setContent { TestHost { DefaultPlayer(player.player) } }

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

    private fun ComposeUiTest.tapPicture() {
        // The top-left corner of the 400×300 host: picture only, no control there.
        onRoot().performTouchInput { click(Offset(20f, 20f)) }
        waitForIdle()
    }

    private fun ComposeUiTest.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

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

/** Starts a prepare that the fake engine holds open, leaving the player in Preparing until cancelled. */
fun TestPlayer.startPreparing(): Job {
    fake.suspendLoads = true
    return CoroutineScope(Dispatchers.Unconfined).launch { player.prepare(SOURCE_A) }
}

@Composable
private fun DefaultPlayer(player: VideoPlayer) {
    VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
}
