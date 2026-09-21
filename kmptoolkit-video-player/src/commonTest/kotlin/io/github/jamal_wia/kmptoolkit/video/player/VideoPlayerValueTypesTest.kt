package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plain value types a consumer builds or reads directly: [VideoPlayerConfig]'s validation,
 * [VideoSize]'s invariants and the [VideoPlayerState] extension properties.
 */
class VideoPlayerValueTypesTest {

    // --- VideoPlayerConfig ---

    @Test
    fun `the config defaults are the documented ones`() {
        val config = VideoPlayerConfig()

        assertEquals(250L, config.positionUpdateIntervalMs)
        assertEquals(0.25f, config.minPlaybackSpeed)
        assertEquals(3.0f, config.maxPlaybackSpeed)
    }

    @Test
    fun `a zero or negative polling interval is rejected`() {
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = 0L) }
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(positionUpdateIntervalMs = -1L) }
    }

    @Test
    fun `a zero minimum speed is rejected`() {
        assertFailsWith<IllegalArgumentException> { VideoPlayerConfig(minPlaybackSpeed = 0f) }
    }

    @Test
    fun `a maximum speed below the minimum is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VideoPlayerConfig(minPlaybackSpeed = 2.0f, maxPlaybackSpeed = 1.0f)
        }
    }

    @Test
    fun `a single-speed range is allowed`() {
        val config = VideoPlayerConfig(minPlaybackSpeed = 1.0f, maxPlaybackSpeed = 1.0f)

        assertEquals(1.0f, config.maxPlaybackSpeed)
    }

    // --- VideoSize ---

    @Test
    fun `a video size reports its aspect ratio`() {
        assertEquals(16f / 9f, VideoSize(1920, 1080).aspectRatio)
        assertEquals(9f / 16f, VideoSize(1080, 1920).aspectRatio)
    }

    @Test
    fun `a video size rejects a zero or negative dimension`() {
        assertFailsWith<IllegalArgumentException> { VideoSize(0, 1080) }
        assertFailsWith<IllegalArgumentException> { VideoSize(1920, 0) }
        assertFailsWith<IllegalArgumentException> { VideoSize(-1, 10) }
    }

    // --- VideoPlayerState extensions ---

    private val boom: Throwable = IllegalStateException("boom")

    @Test
    fun `idle preparing and error are not playable`() {
        assertFalse(VideoPlayerState.Idle.isPlayable)
        assertFalse(VideoPlayerState.Preparing.isPlayable)
        assertFalse(VideoPlayerState.Error(boom).isPlayable)
    }

    @Test
    fun `every state holding a loaded source is playable`() {
        assertTrue(VideoPlayerState.Ready(duration = 1_000L).isPlayable)
        assertTrue(VideoPlayerState.Playing(duration = 1_000L, currentPosition = 0L).isPlayable)
        assertTrue(VideoPlayerState.Paused(duration = 1_000L, currentPosition = 0L).isPlayable)
        assertTrue(VideoPlayerState.Completed(duration = 1_000L).isPlayable)
    }

    @Test
    fun `only playing is playing`() {
        assertTrue(VideoPlayerState.Playing(1_000L, 0L).isPlaying)
        assertFalse(VideoPlayerState.Paused(1_000L, 0L).isPlaying)
        assertFalse(VideoPlayerState.Ready(1_000L).isPlaying)
    }

    @Test
    fun `duration is null where nothing is loaded`() {
        assertNull(VideoPlayerState.Idle.duration)
        assertNull(VideoPlayerState.Preparing.duration)
        assertNull(VideoPlayerState.Error(boom).duration)
        assertEquals(5L, VideoPlayerState.Completed(5L).duration)
    }

    @Test
    fun `ready reports no playback position`() {
        assertNull(VideoPlayerState.Ready(1_000L).playbackPosition)
        assertEquals(1_000L, VideoPlayerState.Completed(1_000L).playbackPosition)
        assertEquals(300L, VideoPlayerState.Paused(1_000L, 300L).playbackPosition)
    }

    @Test
    fun `progress is clamped and never NaN`() {
        assertEquals(0.5f, VideoPlayerState.Playing(1_000L, 500L).progress)
        assertEquals(1f, VideoPlayerState.Playing(1_000L, 5_000L).progress)
        assertEquals(0f, VideoPlayerState.Playing(0L, 500L).progress)
        assertEquals(0f, VideoPlayerState.Idle.progress)
        assertEquals(0f, VideoPlayerState.Ready(1_000L).progress)
    }
}
