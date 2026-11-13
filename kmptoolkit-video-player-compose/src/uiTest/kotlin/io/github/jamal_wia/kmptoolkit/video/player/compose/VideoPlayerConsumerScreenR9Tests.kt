package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.testing.FakeVideoPlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1 round 9 (breaker hz), Q1 and Q4: a consumer's screen as docs 02-getting-started, 03-guide and 06-testing of
 * `kmptoolkit-video-player-compose` print it (each snippet compiled and run as shown, with the declarations the
 * snippet leaves out added), and the recomposition counts of a controls slot per position tick.
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoPlayerConsumerScreenR9Tests {

    private val workoutUrl: String = "https://example.test/a.mp4"

    // --- 06-testing "No fixture artifact of its own", as printed ---------------------------------

    @Test
    fun `the printed workout screen test shows the replay button at the end`() = runComposeUiTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine = engine)
        try {
            setContent {
                CompositionLocalProvider(LocalVideoPlayerFactory provides VideoPlayerFactory { player }) {
                    // The snippet's WorkoutScreen(url): the one-call player with the default controls.
                    VideoPlayer(source = VideoSource.Remote(workoutUrl), autoPlay = true) {
                        DefaultVideoControls(labels = LABELS)
                    }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { player.stateFlow.value is VideoPlayerState.Playing }
            engine.completePlayback()
            waitUntil(timeoutMillis = WAIT_MS) { player.stateFlow.value is VideoPlayerState.Completed }
            waitForIdle()

            onNodeWithContentDescription(LABELS.replay!!).assertExists()
            assertEquals(listOf<VideoSource>(VideoSource.Remote(workoutUrl)), engine.loadedSources.toList())
        } finally {
            player.release()
        }
    }

    // --- 06-testing "Finding the controls", as printed -------------------------------------------

    @Test
    fun `the printed play click on a player with nothing loaded is harmless`() = runComposeUiTest {
        val tp = TestPlayer()
        setContent { TestHost { VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } } }
        onNodeWithContentDescription(LABELS.play!!).performClick()
        waitForIdle()

        assertEquals(emptyList(), tp.calls, "a click on play with nothing loaded reached the engine")
        assertTrue(tp.state !is VideoPlayerState.Playing)
        tp.player.release()
    }

    @Test
    fun `the printed SetProgress action seeks the engine to that fraction of the duration`() = runComposeUiTest {
        val tp = TestPlayer(durationMs = 100_000L).paused(0L)
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Seek").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        onNodeWithContentDescription("Seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForIdle()

        assertEquals(listOf("seekTo(50000)"), tp.calls)
        tp.player.release()
    }

    // --- 06-testing "Auto-hide timing", as printed ------------------------------------------------

    @Test
    fun `the printed auto-hide snippet checks nothing because it passes before any time has passed`() =
        runComposeUiTest {
            val tp = TestPlayer().paused(0L)
            mainClock.autoAdvance = false
            // As printed: no labels are passed, so no control carries the description "Pause", and a player that
            // is not playing never hides its controls and would show "Play" anyway.
            setContent { TestHost { VideoPlayer(tp.player, controlsAutoHideDelayMs = 3_000L) } }
            onNodeWithContentDescription("Pause").assertDoesNotExist()
            mainClock.advanceTimeBy(3_000L + 1_000L)
            onNodeWithContentDescription("Pause").assertDoesNotExist()
            tp.player.release()
        }

    @Test
    fun `the auto-hide behaviour the snippet means holds with labels and a playing player`() = runComposeUiTest {
        val tp = TestPlayer().playing(0L)
        mainClock.autoAdvance = false
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = 3_000L) { DefaultVideoControls(labels = LABELS) }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription(LABELS.pause!!).assertExists()
        mainClock.advanceTimeBy(3_000L + 1_000L)
        onNodeWithContentDescription(LABELS.pause!!).assertDoesNotExist()
        tp.player.release()
    }

    // --- 03-guide "Who owns the player": two screens, one player ---------------------------------

    @Test
    fun `a player given to two composables survives one of them leaving and keeps serving the other`() =
        runComposeUiTest {
            val tp = TestPlayer().paused(0L)
            var showFirst: Boolean by mutableStateOf(true)
            setContent {
                TestHost {
                    Box(Modifier.fillMaxSize()) {
                        if (showFirst) VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                        VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                            DefaultVideoControls(labels = LABELS)
                        }
                    }
                }
            }
            waitForIdle()
            showFirst = false
            waitForIdle()

            assertEquals(0, tp.fake.disposeCount, "the player was released by a composable that did not own it")
            onNodeWithContentDescription(LABELS.play!!).performClick()
            waitForIdle()
            assertEquals(listOf("start"), tp.calls)
            tp.player.release()
        }

    // --- 03-guide "Keep screen on, background, lifecycle": the pause comes at ON_STOP, not before -------

    private class HostLifecycleR9 : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `a screen that is paused but still visible keeps playing and only a stop pauses it`() = runComposeUiTest {
        val host = HostLifecycleR9()
        val tp = TestPlayer().playing(0L)
        setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                TestHost { VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) }
            }
        }
        waitForIdle()

        // RESUMED to STARTED sends ON_PAUSE only: a dialog or a second window in front, the picture still shown.
        runOnIdle { host.registry.currentState = Lifecycle.State.STARTED }
        assertTrue(tp.state is VideoPlayerState.Playing, "paused by an ON_PAUSE: ${tp.state} ${tp.calls}")
        assertEquals(emptyList(), tp.calls)

        // STARTED to CREATED sends ON_STOP: now the player pauses.
        runOnIdle { host.registry.currentState = Lifecycle.State.CREATED }
        assertTrue(tp.state is VideoPlayerState.Paused, "not paused by the stop: ${tp.state} ${tp.calls}")
        tp.player.release()
    }

    // --- Q4: recomposition counts of a controls slot per position tick ----------------------------

    private class Counts {
        var position: Int = 0
        var isPlaying: Int = 0
        var duration: Int = 0
        var muted: Int = 0
    }

    @Test
    fun `a slot that reads the position is recomposed by every tick and one that reads the mute flag by none`() = runComposeUiTest {
        val tp = TestPlayer().playing(0L)
        val counts = Counts()
        setContent {
            TestHost {
                VideoPlayer(tp.player, controlsAutoHideDelayMs = NEVER_HIDE_MS) {
                    // One non-inline composable per reader: each read happens in a restart scope of its own.
                    CountedRead({ counts.position++ }) { positionMs }
                    CountedRead({ counts.isPlaying++ }) { isPlaying }
                    CountedRead({ counts.duration++ }) { durationMs }
                    CountedRead({ counts.muted++ }) { isMuted }
                }
            }
        }
        waitForIdle()
        val before = Counts().also {
            it.position = counts.position
            it.isPlaying = counts.isPlaying
            it.duration = counts.duration
            it.muted = counts.muted
        }
        repeat(TICKS) { index: Int ->
            tp.movePlayhead((index + 1) * 1_000L)
            waitForIdle()
        }

        val message = "position ${counts.position - before.position}, isPlaying ${counts.isPlaying - before.isPlaying}, " +
            "duration ${counts.duration - before.duration}, muted ${counts.muted - before.muted} over $TICKS ticks"
        assertTrue(counts.position - before.position >= TICKS, "the harness does not see ticks: $message")
        assertEquals(0, counts.muted - before.muted, message)
        assertEquals(0, counts.isPlaying - before.isPlaying, message)
        assertEquals(0, counts.duration - before.duration, message)
        assertFalse(tp.player.stateFlow.value !is VideoPlayerState.Playing)
        tp.player.release()
    }

    // --- Q4: a thousand-ish lifetimes through the composition ------------------------------------

    @Test
    fun `three hundred compositions of the one-call player release every player once and detach its listener`() =
        runComposeUiTest {
            val factory = RecordingFactory { TestPlayer() }
            var shown: Boolean by mutableStateOf(false)
            setContent {
                TestHost(factory = factory) {
                    if (shown) VideoPlayer(source = SOURCE_A, autoPlay = true, controlsAutoHideDelayMs = NEVER_HIDE_MS)
                }
            }
            repeat(LIFETIMES) {
                shown = true
                waitForIdle()
                shown = false
                waitForIdle()
            }

            assertEquals(LIFETIMES, factory.created.size)
            factory.created.forEachIndexed { index: Int, made: TestPlayer ->
                assertEquals(1, made.fake.disposeCount, "player $index dispose count")
                assertFalse(made.fake.hasListener, "player $index kept its listener")
                assertFalse(made.fake.isPlaying, "player $index still plays")
            }
        }

    private companion object {
        const val TICKS: Int = 10
        const val LIFETIMES: Int = 300
        const val WAIT_MS: Long = 10_000L
    }
}

@Composable
private fun CountedRead(onComposed: () -> Unit, read: () -> Any?) {
    val value: Any? = read()
    SideEffect { if (value == null) error("a read of the scope returned null") else onComposed() }
}
