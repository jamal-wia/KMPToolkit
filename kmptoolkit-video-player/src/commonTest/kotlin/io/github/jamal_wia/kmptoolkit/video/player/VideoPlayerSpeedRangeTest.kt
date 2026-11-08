package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `VideoPlayer.playbackSpeedRange`: the configured range, the bounds every `playbackSpeedFlow` value lies in. */
class VideoPlayerSpeedRangeTest {

    @Test
    fun `a configured range is reported as it is`() = runTest {
        val player: VideoPlayer = createVideoPlayer(
            RecordingVideoPlaybackEngine(),
            VideoPlayerConfig(minPlaybackSpeed = 0.75f, maxPlaybackSpeed = 1.5f),
            StandardTestDispatcher(testScheduler),
        )
        try {
            assertEquals(0.75f..1.5f, player.playbackSpeedRange)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the default config reports its defaults`() = runTest {
        val config = VideoPlayerConfig()
        val player: VideoPlayer = createVideoPlayer(RecordingVideoPlaybackEngine(), config, StandardTestDispatcher(testScheduler))
        try {
            assertEquals(config.minPlaybackSpeed..config.maxPlaybackSpeed, player.playbackSpeedRange)
        } finally {
            player.release()
        }
    }

    @Test
    fun `every speed the player publishes lies in its range`() = runTest {
        val player: VideoPlayer = createVideoPlayer(
            RecordingVideoPlaybackEngine(),
            VideoPlayerConfig(minPlaybackSpeed = 1.1f, maxPlaybackSpeed = 1.2f),
            StandardTestDispatcher(testScheduler),
        )
        try {
            assertTrue(player.playbackSpeedFlow.value in player.playbackSpeedRange)
            for (requested in listOf(0.1f, 5f, 1.15f, -3f)) {
                player.setPlaybackSpeed(requested)
                assertTrue(player.playbackSpeedFlow.value in player.playbackSpeedRange, "requested $requested")
            }
        } finally {
            player.release()
        }
    }
}
