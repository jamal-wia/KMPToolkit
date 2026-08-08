package io.github.jamal_wia.kmptoolkit.audio.player

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
 * State-machine behavior of the player returned by `createAudioPlayer`, exercised through a
 * scripted engine.
 *
 * The contract under test is the one written down in [AudioPlayer]'s KDoc and in
 * `docs/kmptoolkit-audio-player/04-api-reference.md`, not whatever the implementation happens to
 * do — every assertion below names the exact expected state rather than "not Idle".
 */
class AudioPlayerTest {

    private val source: AudioSource = AudioSource.Remote("https://example.test/track.mp3")

    /**
     * Runs [body] against a fresh player and always releases it.
     *
     * The release is not politeness: a playing player owns a coroutine that delays forever, and
     * `runTest` drains the shared scheduler when the body returns — leaving that loop alive hangs
     * the test run instead of failing it.
     */
    private fun playerTest(
        engine: RecordingPlaybackEngine = RecordingPlaybackEngine(),
        config: AudioPlayerConfig = AudioPlayerConfig(),
        body: suspend TestScope.(RecordingPlaybackEngine, AudioPlayer) -> Unit,
    ): TestResult = runTest {
        val player: AudioPlayer = createAudioPlayer(
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
    fun `a new player is idle and holds no position`() = playerTest { _, player ->
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(1.0f, player.playbackSpeed)
    }

    @Test
    fun `prepare loads the source and reports its duration`() =
        playerTest(RecordingPlaybackEngine(duration = 42_000L)) { engine, player ->
            player.prepare(source)

            assertEquals(PlayerState.Ready(42_000L), player.stateFlow.value)
            assertEquals(listOf(source), engine.loadedSources)
        }

    @Test
    fun `prepare reports preparing before the load resolves`() =
        playerTest(RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }) { _, player ->
            val states: MutableList<PlayerState> = mutableListOf()

            val loading = launch { player.prepare(source) }
            runCurrent()
            states += player.stateFlow.value
            advanceTimeBy(1_001L)
            loading.join()
            states += player.stateFlow.value

            assertEquals(listOf(PlayerState.Preparing, PlayerState.Ready(10_000L)), states)
        }

    @Test
    fun `a failed load surfaces the engine throwable unchanged`(): TestResult {
        val failure = IllegalStateException("codec missing")
        return playerTest(RecordingPlaybackEngine().apply { loadFailure = failure }) { _, player ->
            player.prepare(source)

            val state: PlayerState = player.stateFlow.value
            assertIs<PlayerState.Error>(state)
            assertSame(failure, state.cause)
        }
    }

    @Test
    fun `preparing a second source replaces the first`() = playerTest { engine, player ->
        val second: AudioSource = AudioSource.File("/tmp/other.mp3")

        player.prepare(source)
        player.play()
        engine.duration = 5_000L
        player.prepare(second)

        assertEquals(PlayerState.Ready(5_000L), player.stateFlow.value)
        assertEquals(listOf(source, second), engine.loadedSources)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `play is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.play()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.started)
    }

    @Test
    fun `play after prepare starts the engine and reports playing`() =
        playerTest(RecordingPlaybackEngine(duration = 8_000L)) { engine, player ->
            player.prepare(source)
            player.play()

            assertEquals(
                PlayerState.Playing(duration = 8_000L, currentPosition = 0L),
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
    fun `the position is polled at the configured interval while playing`() = playerTest(
        engine = RecordingPlaybackEngine(duration = 10_000L),
        config = AudioPlayerConfig(positionUpdateIntervalMs = 250L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 700L
        advanceTimeBy(251L)

        assertEquals(700L, player.playbackPositionFlow.value)
        assertEquals(
            PlayerState.Playing(duration = 10_000L, currentPosition = 700L),
            player.stateFlow.value,
        )
    }

    @Test
    fun `the position is not polled before the first interval elapses`() = playerTest(
        config = AudioPlayerConfig(positionUpdateIntervalMs = 250L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 700L
        advanceTimeBy(249L)

        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `pause keeps the playhead and stops polling`() = playerTest(
        engine = RecordingPlaybackEngine(duration = 10_000L),
        config = AudioPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 3_000L
        player.pause()
        engine.position = 9_999L
        advanceTimeBy(1_000L)

        assertEquals(
            PlayerState.Paused(duration = 10_000L, currentPosition = 3_000L),
            player.stateFlow.value,
        )
        assertEquals(3_000L, player.playbackPositionFlow.value)
        assertEquals(1, engine.paused)
    }

    @Test
    fun `pause is ignored when not playing`() = playerTest { engine, player ->
        player.prepare(source)
        player.pause()

        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(0, engine.paused)
    }

    @Test
    fun `stop rewinds to the beginning and stays loaded`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 4_000L
            player.stop()

            assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(listOf(0L), engine.seekTargets)
        }

    @Test
    fun `seekTo clamps a position past the end down to the duration`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { _, player ->
            player.prepare(source)
            player.play()
            player.seekTo(99_999L)

            assertEquals(10_000L, player.playbackPositionFlow.value)
            assertEquals(
                PlayerState.Playing(duration = 10_000L, currentPosition = 10_000L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `seekTo clamps a negative position up to zero`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 5_000L
            player.seekTo(-1_000L)

            assertEquals(0L, player.playbackPositionFlow.value)
        }

    @Test
    fun `seekTo is ignored when nothing is loaded`() = playerTest { engine, player ->
        player.seekTo(1_000L)

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertTrue(engine.seekTargets.isEmpty())
    }

    @Test
    fun `seekForward and seekBackward move relative to the current position`() =
        playerTest(RecordingPlaybackEngine(duration = 60_000L)) { engine, player ->
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
        playerTest(RecordingPlaybackEngine(duration = 60_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.position = 2_000L
            player.seekBackward()

            assertEquals(0L, player.playbackPositionFlow.value)
        }

    @Test
    fun `completion moves the playhead to the end and reports completed`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.listener?.onCompleted()

            assertEquals(PlayerState.Completed(10_000L), player.stateFlow.value)
            assertEquals(10_000L, player.playbackPositionFlow.value)
            assertEquals(1f, player.stateFlow.value.progress)
        }

    @Test
    fun `seeking out of completed returns to paused`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.listener?.onCompleted()
            player.seekTo(2_000L)

            assertEquals(
                PlayerState.Paused(duration = 10_000L, currentPosition = 2_000L),
                player.stateFlow.value,
            )
        }

    @Test
    fun `replay restarts a completed source from the beginning`() =
        playerTest(RecordingPlaybackEngine(duration = 10_000L)) { engine, player ->
            player.prepare(source)
            player.play()
            engine.listener?.onCompleted()
            player.replay()

            assertEquals(
                PlayerState.Playing(duration = 10_000L, currentPosition = 0L),
                player.stateFlow.value,
            )
            assertEquals(listOf(0L), engine.seekTargets)
            assertEquals(2, engine.started)
        }

    @Test
    fun `a failure during playback surfaces the cause and stops polling`(): TestResult {
        val failure = IllegalStateException("decoder died")
        return playerTest(
            engine = RecordingPlaybackEngine(duration = 10_000L),
            config = AudioPlayerConfig(positionUpdateIntervalMs = 100L),
        ) { engine, player ->
            player.prepare(source)
            player.play()
            engine.listener?.onFailed(failure)
            engine.position = 5_000L
            advanceTimeBy(1_000L)

            val state: PlayerState = player.stateFlow.value
            assertIs<PlayerState.Error>(state)
            assertSame(failure, state.cause)
            assertEquals(0L, player.playbackPositionFlow.value)
        }
    }

    @Test
    fun `setPlaybackSpeed clamps into the configured range`() = playerTest(
        config = AudioPlayerConfig(minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2.0f),
    ) { _, player ->
        player.setPlaybackSpeed(10.0f)
        assertEquals(2.0f, player.playbackSpeed)

        player.setPlaybackSpeed(0.01f)
        assertEquals(0.5f, player.playbackSpeed)
    }

    @Test
    fun `the chosen speed is applied to the engine on play`() = playerTest { engine, player ->
        player.setPlaybackSpeed(1.5f)
        player.prepare(source)
        player.play()

        assertEquals(1.5f, engine.appliedSpeed)
    }

    @Test
    fun `the chosen speed survives preparing another source`() = playerTest { engine, player ->
        player.setPlaybackSpeed(2.0f)
        player.prepare(source)
        player.prepare(AudioSource.Asset("chime.mp3"))

        assertEquals(2.0f, player.playbackSpeed)
        assertEquals(2.0f, engine.appliedSpeed)
    }

    @Test
    fun `a source whose duration is unknown stays at zero progress`() =
        playerTest(RecordingPlaybackEngine(duration = 0L)) { _, player ->
            player.prepare(source)
            player.play()
            player.seekTo(5_000L)

            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(0f, player.stateFlow.value.progress)
        }
}
