package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.testing.FakeVideoPlaybackEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The default speed button cycles `VideoControlsDefaults.Speeds` (0.5 .. 2) and wraps after the last
 * entry. A player configured with a narrower range clamps what the button asks for; the button must
 * still be a cycle (a way back from the top), not a dead end.
 */
@OptIn(ExperimentalTestApi::class)
abstract class SpeedButtonConfiguredRangeAdversarialTests {

    @Test
    fun `a button on a player capped below the default speeds still cycles back from the top`() = runComposeUiTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = FakeVideoPlaybackEngine(),
            config = VideoPlayerConfig(maxPlaybackSpeed = 1.5f),
            coroutineContext = StandardTestDispatcher(),
        )
        try {
            runBlocking { player.prepare(SOURCE_A) }
            setContent {
                TestHost { VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } }
            }
            val seen: MutableList<Float> = mutableListOf(player.playbackSpeedFlow.value)
            repeat(12) {
                onNodeWithContentDescription("Speed").performClick()
                waitForIdle()
                seen += player.playbackSpeedFlow.value
            }
            assertTrue(
                seen.any { it < 1f },
                "twelve taps from 1x on a player capped at 1.5x only ever showed $seen: the button never comes back down",
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `a range that holds no default speed still cycles between its two bounds`() = runComposeUiTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = FakeVideoPlaybackEngine(),
            config = VideoPlayerConfig(minPlaybackSpeed = 1.1f, maxPlaybackSpeed = 1.2f),
            coroutineContext = StandardTestDispatcher(),
        )
        try {
            runBlocking { player.prepare(SOURCE_A) }
            setContent {
                TestHost { VideoPlayer(player, controlsAutoHideDelayMs = NEVER_HIDE_MS) { DefaultVideoControls(labels = LABELS) } }
            }
            val seen: MutableList<Float> = mutableListOf(player.playbackSpeedFlow.value)
            repeat(4) {
                onNodeWithContentDescription("Speed").performClick()
                waitForIdle()
                seen += player.playbackSpeedFlow.value
            }
            assertEquals(listOf(1.1f, 1.2f, 1.1f, 1.2f, 1.1f), seen)
        } finally {
            player.release()
        }
    }
}
