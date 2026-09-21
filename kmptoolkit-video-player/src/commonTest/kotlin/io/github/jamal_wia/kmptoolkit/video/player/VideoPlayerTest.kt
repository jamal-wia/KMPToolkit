package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Transport behavior of the player returned by `createVideoPlayer(engine)`, exercised through a
 * scripted engine. The contract under test is the one in [VideoPlayer]'s KDoc and
 * `docs/kmptoolkit-video-player/04-api-reference.md` — every assertion names the exact state.
 */
class VideoPlayerTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/clip.mp4")

    /**
     * Runs [body] against a fresh player and always releases it: a playing player owns a polling
     * coroutine that would otherwise keep `runTest` advancing virtual time forever.
     */
    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = config,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a new player is idle with every flow at its documented default`() = playerTest { _, player ->
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
        assertEquals(false, player.isBufferingFlow.value)
        assertEquals(null, player.videoSizeFlow.value)
        assertEquals(1.0f, player.playbackSpeedFlow.value)
        assertEquals(1.0f, player.volumeFlow.value)
        assertEquals(false, player.isMutedFlow.value)
        assertEquals(RepeatMode.Off, player.repeatModeFlow.value)
    }

    @Test
    fun `creating a player installs it as the engine listener`() = playerTest { engine, _ ->
        assertTrue(engine.listener != null)
    }

    @Test
    fun `prepare loads the source and reports its duration`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 42_000L)) { engine, player ->
            player.prepare(source)

            assertEquals(VideoPlayerState.Ready(42_000L), player.stateFlow.value)
            assertEquals(listOf(source), engine.loadedSources)
        }

    @Test
    fun `prepare reports preparing before the load resolves`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }) { _, player ->
            val states: MutableList<VideoPlayerState> = mutableListOf()

            val loading = launch { player.prepare(source) }
            runCurrent()
            states += player.stateFlow.value
            advanceTimeBy(1_001L)
            loading.join()
            states += player.stateFlow.value

            assertEquals(listOf(VideoPlayerState.Preparing, VideoPlayerState.Ready(10_000L)), states)
        }

    @Test
    fun `prepare snapshots the buffered position once loaded`() =
        playerTest(RecordingVideoPlaybackEngine().apply { buffered = 2_500L }) { _, player ->
            player.prepare(source)

            assertEquals(2_500L, player.bufferedPositionFlow.value)
        }

    @Test
    fun `a failed load surfaces the engine throwable unchanged and does not throw`(): TestResult {
        val failure = IllegalStateException("codec missing")
        return playerTest(RecordingVideoPlaybackEngine().apply { loadFailure = failure }) { _, player ->
            player.prepare(source)

            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state)
            assertSame(failure, state.cause)
        }
    }

    @Test
    fun `a failed load after a picture size was reported leaves no picture size`() = playerTest(
        RecordingVideoPlaybackEngine().apply {
            sizeReportedWhileLoading = VideoSize(1920, 1080)
            loadFailure = IllegalStateException("broken container")
        },
    ) { _, player ->
        player.prepare(source)

        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `an empty source path is handed to the engine and its failure surfaces as an error`(): TestResult {
        val failure = IllegalArgumentException("empty path")
        return playerTest(RecordingVideoPlaybackEngine().apply { loadFailure = failure }) { engine, player ->
            player.prepare(VideoSource.File(""))

            assertEquals(listOf<VideoSource>(VideoSource.File("")), engine.loadedSources)
            assertEquals(VideoPlayerState.Error(failure), player.stateFlow.value)
        }
    }

    @Test
    fun `preparing a second source replaces the first and resets the playhead`() = playerTest { engine, player ->
        val second: VideoSource = VideoSource.File("/data/other.mp4")

        player.prepare(source)
        player.play()
        engine.position = 4_000L
        player.seekTo(4_000L)
        engine.duration = 5_000L
        player.prepare(second)

        assertEquals(VideoPlayerState.Ready(5_000L), player.stateFlow.value)
        assertEquals(listOf(source, second), engine.loadedSources)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `play is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.play()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.started)
    }

    @Test
    fun `play is ignored while preparing`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }) { engine, player ->
            launch { player.prepare(source) }
            runCurrent()
            player.play()

            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
            assertEquals(0, engine.started)
        }

    @Test
    fun `play after prepare starts the engine and reports playing`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 8_000L)) { engine, player ->
            player.prepare(source)
            player.play()

            assertEquals(
                VideoPlayerState.Playing(duration = 8_000L, currentPosition = 0L),
                player.stateFlow.value,
            )
            assertEquals(1, engine.started)
        }

    @Test
    fun `a second play while already playing is ignored`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.play()

        assertEquals(1, engine.started)
    }

    @Test
    fun `the playhead and the buffered position are polled at the configured interval while playing`() =
        playerTest(
            engine = RecordingVideoPlaybackEngine(duration = 10_000L),
            config = VideoPlayerConfig(positionUpdateIntervalMs = 250L),
        ) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 700L
            engine.buffered = 4_000L
            advanceTimeBy(251L)

            assertEquals(700L, player.playbackPositionFlow.value)
            assertEquals(4_000L, player.bufferedPositionFlow.value)
            assertEquals(
                VideoPlayerState.Playing(duration = 10_000L, currentPosition = 700L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `nothing is polled before the first interval elapses`() = playerTest(
        config = VideoPlayerConfig(positionUpdateIntervalMs = 250L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 700L
        engine.buffered = 4_000L
        advanceTimeBy(249L)

        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
    }

    @Test
    fun `nothing is polled while ready`() = playerTest(
        config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.prepare(source)
        engine.position = 700L
        engine.buffered = 4_000L
        advanceTimeBy(1_000L)

        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(0L, player.bufferedPositionFlow.value)
    }

    @Test
    fun `pause keeps the playhead and stops polling`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 10_000L),
        config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 3_000L
        engine.buffered = 6_000L
        player.pause()
        engine.position = 9_999L
        engine.buffered = 9_999L
        advanceTimeBy(1_000L)

        assertEquals(
            VideoPlayerState.Paused(duration = 10_000L, currentPosition = 3_000L),
            player.stateFlow.value,
        )
        assertEquals(3_000L, player.playbackPositionFlow.value)
        assertEquals(6_000L, player.bufferedPositionFlow.value)
        assertEquals(1, engine.paused)
    }

    @Test
    fun `pause is ignored when not playing`() = playerTest { engine, player ->
        player.prepare(source)
        player.pause()

        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(0, engine.paused)
    }

    @Test
    fun `play from paused resumes at the paused position`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 3_000L
            player.pause()
            player.play()

            assertEquals(
                VideoPlayerState.Playing(duration = 10_000L, currentPosition = 3_000L),
                player.stateFlow.value,
            )
            assertTrue(engine.seekTargets.isEmpty())
        }

    @Test
    fun `stop rewinds to the beginning and stays loaded`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 4_000L
            player.stop()

            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(listOf(0L), engine.seekTargets)
            assertEquals(1, engine.paused)
            assertEquals(0, engine.releaseCount)
        }

    @Test
    fun `stop is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.stop()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.paused)
    }

    @Test
    fun `seekTo clamps a position past the end down to the duration`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            player.seekTo(99_999L)

            assertEquals(listOf(10_000L), engine.seekTargets)
            assertEquals(10_000L, player.playbackPositionFlow.value)
            assertEquals(
                VideoPlayerState.Playing(duration = 10_000L, currentPosition = 10_000L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `seekTo clamps a negative position up to zero`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 5_000L
            player.seekTo(-1_000L)

            assertEquals(listOf(0L), engine.seekTargets)
            assertEquals(0L, player.playbackPositionFlow.value)
        }

    @Test
    fun `seekTo keeps a playing player playing and polling from the new position`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 10_000L),
        config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        player.seekTo(6_000L)

        assertEquals(
            VideoPlayerState.Playing(duration = 10_000L, currentPosition = 6_000L),
            player.stateFlow.value,
        )
        assertEquals(6_000L, player.playbackPositionFlow.value)
        assertEquals(1, engine.started)
        assertEquals(0, engine.paused)

        engine.position = 6_400L
        advanceTimeBy(101L)
        assertEquals(
            VideoPlayerState.Playing(duration = 10_000L, currentPosition = 6_400L),
            player.stateFlow.value,
        )
    }

    @Test
    fun `seekTo keeps a paused player paused`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { _, player ->
            player.prepare(source)
            player.play()
            player.pause()
            player.seekTo(6_000L)

            assertEquals(
                VideoPlayerState.Paused(duration = 10_000L, currentPosition = 6_000L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `seekTo on a ready player moves the playhead and stays ready`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.seekTo(6_000L)

            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(6_000L, player.playbackPositionFlow.value)
            assertEquals(listOf(6_000L), engine.seekTargets)
        }

    @Test
    fun `seekTo is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.seekTo(1_000L)

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertTrue(engine.seekTargets.isEmpty())
    }

    @Test
    fun `seekForward and seekBackward move relative to the current position`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 60_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 20_000L
            player.seekForward()
            player.seekBackward(5_000L)

            assertEquals(listOf(30_000L, 25_000L), engine.seekTargets)
            assertEquals(25_000L, player.playbackPositionFlow.value)
        }

    @Test
    fun `seekBackward clamps to the start of the source`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 60_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 2_000L
            player.seekBackward()

            assertEquals(0L, player.playbackPositionFlow.value)
        }

    @Test
    fun `a huge seek step clamps instead of overflowing`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 60_000L)) { engine, player ->
            player.prepare(source)
            engine.position = 30_000L
            player.seekForward(Long.MAX_VALUE)
            assertEquals(60_000L, player.playbackPositionFlow.value)

            player.seekBackward(Long.MAX_VALUE)
            assertEquals(0L, player.playbackPositionFlow.value)

            player.seekBackward(Long.MIN_VALUE)
            assertEquals(60_000L, player.playbackPositionFlow.value)
        }

    @Test
    fun `completion moves the playhead to the end and reports completed`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            requireNotNull(engine.listener).onCompleted()

            assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
            assertEquals(10_000L, player.playbackPositionFlow.value)
            assertEquals(1f, player.stateFlow.value.progress)
        }

    @Test
    fun `completion stops polling`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 10_000L),
        config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        requireNotNull(engine.listener).onCompleted()
        engine.position = 1_234L
        advanceTimeBy(1_000L)

        assertEquals(10_000L, player.playbackPositionFlow.value)
        assertEquals(VideoPlayerState.Completed(10_000L), player.stateFlow.value)
    }

    @Test
    fun `seeking out of completed returns to paused`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            requireNotNull(engine.listener).onCompleted()
            player.seekTo(2_000L)

            assertEquals(
                VideoPlayerState.Paused(duration = 10_000L, currentPosition = 2_000L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `play from completed starts over from the beginning`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 3_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 3_000L
            requireNotNull(engine.listener).onCompleted()
            player.play()

            assertEquals(listOf(0L), engine.seekTargets)
            assertEquals(VideoPlayerState.Playing(duration = 3_000L, currentPosition = 0L), player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
        }

    @Test
    fun `replay restarts a completed source from the beginning`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            requireNotNull(engine.listener).onCompleted()
            player.replay()

            assertEquals(
                VideoPlayerState.Playing(duration = 10_000L, currentPosition = 0L),
                player.stateFlow.value,
            )
            assertEquals(listOf(0L), engine.seekTargets)
            assertEquals(2, engine.started)
        }

    @Test
    fun `replay from paused rewinds and plays`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 5_000L
            player.pause()
            player.replay()

            assertEquals(
                VideoPlayerState.Playing(duration = 10_000L, currentPosition = 0L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `replay is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.replay()

        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.started)
    }

    @Test
    fun `a failure during playback surfaces the cause and stops polling`(): TestResult {
        val failure = IllegalStateException("decoder died")
        return playerTest(
            engine = RecordingVideoPlaybackEngine(duration = 10_000L),
            config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
        ) { engine, player ->
            player.prepare(source)
            player.play()
            requireNotNull(engine.listener).onFailed(failure)
            engine.position = 5_000L
            advanceTimeBy(1_000L)

            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state)
            assertSame(failure, state.cause)
            assertEquals(0L, player.playbackPositionFlow.value)
        }
    }

    @Test
    fun `transport calls in the error state are ignored`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadFailure = IllegalStateException("x") }) { engine, player ->
            player.prepare(source)
            player.play()
            player.seekTo(1_000L)
            player.stop()
            player.replay()

            assertIs<VideoPlayerState.Error>(player.stateFlow.value)
            assertEquals(0, engine.started)
            assertTrue(engine.seekTargets.isEmpty())
        }

    @Test
    fun `an errored player recovers by preparing again`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadFailure = IllegalStateException("x") }) { engine, player ->
            player.prepare(source)
            engine.loadFailure = null
            player.prepare(source)

            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        }

    @Test
    fun `a source whose duration is unknown stays at zero progress`() =
        playerTest(RecordingVideoPlaybackEngine(duration = 0L)) { _, player ->
            player.prepare(source)
            player.play()
            player.seekTo(5_000L)

            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(0f, player.stateFlow.value.progress)
        }
}
