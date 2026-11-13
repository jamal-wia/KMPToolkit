package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.testing.FakeVideoPlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1 round 10 (breaker ij), Q2: the Compose layer leaving the composition at the awkward moments. The one-call
 * `VideoPlayer(source)` leaving while its load is still in flight (the player it owns is released, the load is
 * cancelled and freed, a later load result reaches nothing, a second entry builds a fresh player that works), and a
 * borrowed player's controls leaving in the middle of the auto-hide countdown (nothing reaches the engine, the
 * player is not released, the countdown of the next entry starts afresh).
 */
@OptIn(ExperimentalTestApi::class)
abstract class VideoPlayerLeavesCompositionD1R10Tests {

    @Test
    fun `leaving while the load is in flight releases the owned player frees the load and a later result reaches nothing`() = runComposeUiTest {
        val engines: MutableList<FakeVideoPlaybackEngine> = mutableListOf()
        val players: MutableList<VideoPlayer> = mutableListOf()
        val factory = VideoPlayerFactory { config ->
            val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
            engines += engine
            createVideoPlayer(engine, config).also { player: VideoPlayer -> players += player }
        }
        var shown by mutableStateOf(true)
        try {
            setContent {
                TestHost(factory = factory) {
                    if (shown) VideoPlayer(source = SOURCE_A, autoPlay = true, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) }
                }
            }
            waitUntil(timeoutMillis = WAIT_MS) { engines.singleOrNull()?.isLoadPending == true }
            assertEquals(VideoPlayerState.Preparing, players.single().stateFlow.value)

            shown = false
            waitForIdle() // a write made outside a frame is applied at the next idle (Robolectric)
            waitUntil(timeoutMillis = WAIT_MS) { engines[0].disposeCount == 1 }
            waitForIdle()
            assertFalse(engines[0].isLoadPending, "the cancelled load was left suspended in the engine")
            assertFalse(engines[0].hasListener, "the released player still listens to its engine")
            assertEquals(VideoPlayerState.Idle, players[0].stateFlow.value)
            assertEquals(1, engines[0].releaseCount, "the cancelled load must be freed exactly once")

            engines[0].finishLoad() // a result that comes too late
            waitForIdle()
            assertEquals(VideoPlayerState.Idle, players[0].stateFlow.value, "a late load result revived a released player")
            assertFalse(engines[0].isPlaying)

            // A second entry builds a fresh player over a fresh engine, and it works.
            shown = true
            waitForIdle()
            waitUntil(timeoutMillis = WAIT_MS) { engines.size == 2 && engines[1].isLoadPending }
            engines[1].finishLoad()
            waitUntil(timeoutMillis = WAIT_MS) { players[1].stateFlow.value is VideoPlayerState.Playing }
            assertTrue(engines[1].isPlaying)

            shown = false
            waitForIdle()
            waitUntil(timeoutMillis = WAIT_MS) { engines[1].disposeCount == 1 }
            waitForIdle()
            assertFalse(engines[1].isPlaying, "the engine kept playing after the screen left")
            assertEquals(2, engines.size, "leaving must not build another player")
            assertEquals(1, engines[0].disposeCount, "the first engine was disposed again")
        } finally {
            players.forEach { player: VideoPlayer -> player.release() }
        }
    }

    @Test
    fun `leaving in the middle of the auto hide countdown touches nothing and the next entry starts a fresh countdown`() = runComposeUiTest {
        val tp = TestPlayer().playing()
        var shown by mutableStateOf(true)
        try {
            mainClock.autoAdvance = false
            setContent {
                TestHost {
                    if (shown) VideoPlayer(tp.player, controlsAutoHideDelayMs = 2_000L) { DefaultVideoControls(labels = LABELS) }
                }
            }
            mainClock.advanceTimeBy(1_000L)
            onNodeWithContentDescription(LABELS.pause!!).assertExists()

            shown = false
            waitForIdle()
            mainClock.advanceTimeBy(60_000L) // the countdown that was running must not fire into the void
            assertEquals(emptyList(), tp.calls, "leaving or a dead countdown reached the engine")
            assertTrue(tp.state is VideoPlayerState.Playing, "leaving paused or stopped a borrowed player: ${tp.state}")

            shown = true
            waitForIdle()
            mainClock.advanceTimeBy(1_000L)
            onNodeWithContentDescription(LABELS.pause!!).assertExists() // a countdown of 2 s that began at the entry, not the old one
            mainClock.advanceTimeBy(5_000L)
            assertEquals(emptyList(), tp.calls)
        } finally {
            tp.player.release()
        }
    }

    private companion object {
        const val WAIT_MS: Long = 10_000L
    }
}
