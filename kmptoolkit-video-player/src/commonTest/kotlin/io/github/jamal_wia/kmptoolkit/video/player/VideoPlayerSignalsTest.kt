package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The two signals a video screen needs beside the transport state — buffering and picture size —
 * which the engine reports asynchronously. Both describe a source, so both reset whenever the source
 * goes away, and a report arriving when there is no source is dropped.
 */
class VideoPlayerSignalsTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/live.m3u8")
    private val fullHd = VideoSize(1920, 1080)

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    // --- Buffering ---

    @Test
    fun `buffering reports reach the flow while a source is loaded`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)

        listener.onBufferingChanged(true)
        assertEquals(true, player.isBufferingFlow.value)
        // Buffering is independent of the transport state.
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)

        listener.onBufferingChanged(false)
        assertEquals(false, player.isBufferingFlow.value)
    }

    @Test
    fun `buffering while paused is reported too`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.pause()
        requireNotNull(engine.listener).onBufferingChanged(true)

        assertEquals(true, player.isBufferingFlow.value)
    }

    @Test
    fun `a buffering report while idle is dropped`() = playerTest { engine, player ->
        requireNotNull(engine.listener).onBufferingChanged(true)

        assertEquals(false, player.isBufferingFlow.value)
    }

    @Test
    fun `a buffering report while preparing is dropped`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }) { engine, player ->
            launch { player.prepare(source) }
            runCurrent()
            requireNotNull(engine.listener).onBufferingChanged(true)

            assertEquals(false, player.isBufferingFlow.value)
        }

    @Test
    fun `completion ends buffering`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        listener.onBufferingChanged(true)
        listener.onCompleted()

        assertEquals(false, player.isBufferingFlow.value)
    }

    @Test
    fun `a playback failure ends buffering`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        listener.onBufferingChanged(true)
        listener.onFailed(IllegalStateException("network lost"))

        assertEquals(false, player.isBufferingFlow.value)
    }

    @Test
    fun `unload ends buffering`() = playerTest { engine, player ->
        player.prepare(source)
        requireNotNull(engine.listener).onBufferingChanged(true)
        player.unload()

        assertEquals(false, player.isBufferingFlow.value)
    }

    @Test
    fun `preparing another source ends buffering of the previous one`() = playerTest { engine, player ->
        player.prepare(source)
        requireNotNull(engine.listener).onBufferingChanged(true)
        player.prepare(VideoSource.File("/data/next.mp4"))

        assertEquals(false, player.isBufferingFlow.value)
    }

    // --- Picture size ---

    @Test
    fun `a picture size reported while preparing is kept once ready`() =
        playerTest(RecordingVideoPlaybackEngine().apply { sizeReportedWhileLoading = VideoSize(1280, 720) }) { _, player ->
            player.prepare(source)

            assertEquals(VideoSize(1280, 720), player.videoSizeFlow.value)
        }

    @Test
    fun `a picture size change while playing reaches the flow`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)

        listener.onVideoSizeChanged(fullHd)
        assertEquals(fullHd, player.videoSizeFlow.value)

        // An adaptive stream switching rendition changes the size mid-playback.
        listener.onVideoSizeChanged(VideoSize(640, 360))
        assertEquals(VideoSize(640, 360), player.videoSizeFlow.value)

        listener.onVideoSizeChanged(null)
        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `the picture size stays after completion because the source stays loaded`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        listener.onVideoSizeChanged(fullHd)
        listener.onCompleted()

        assertEquals(fullHd, player.videoSizeFlow.value)
    }

    @Test
    fun `a picture size report while idle is dropped`() = playerTest { engine, player ->
        requireNotNull(engine.listener).onVideoSizeChanged(fullHd)

        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `unload clears the picture size`() = playerTest { engine, player ->
        player.prepare(source)
        requireNotNull(engine.listener).onVideoSizeChanged(fullHd)
        player.unload()

        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `preparing another source clears the previous picture size`() = playerTest { engine, player ->
        player.prepare(source)
        requireNotNull(engine.listener).onVideoSizeChanged(fullHd)
        player.prepare(VideoSource.File("/data/audio-only.mp4"))

        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `a playback failure clears the picture size and the buffered position`() = playerTest { engine, player ->
        engine.buffered = 3_000L
        player.prepare(source)
        val listener: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        listener.onVideoSizeChanged(fullHd)
        listener.onFailed(IllegalStateException("decoder died"))

        assertEquals(null, player.videoSizeFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
    }

    @Test
    fun `unload resets the buffered position`() = playerTest { engine, player ->
        engine.buffered = 3_000L
        player.prepare(source)
        assertEquals(3_000L, player.bufferedPositionFlow.value)
        player.unload()

        assertEquals(0L, player.bufferedPositionFlow.value)
    }
}
