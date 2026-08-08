package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The release contract from [AudioPlayer]'s KDoc: release once, release twice, use afterwards, and
 * cancel a load half-way. These are the paths that leak a native handle or crash a screen during
 * teardown when they are wrong, and none of them are observable from a happy-path test.
 */
class AudioPlayerLifecycleTest {

    private val source: AudioSource = AudioSource.Remote("https://example.test/track.mp3")

    private fun TestScope.newPlayer(
        engine: RecordingPlaybackEngine,
        config: AudioPlayerConfig = AudioPlayerConfig(),
    ): AudioPlayer = createAudioPlayer(
        engine = engine,
        config = config,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `release frees the engine and returns to idle`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        player.release()

        assertEquals(1, engine.releaseCount)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `release detaches the engine listener`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()

        assertNull(engine.listener)
    }

    @Test
    fun `releasing twice frees the engine exactly once`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        player.release()
        player.release()

        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `close is release`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.close()
        player.close()

        assertEquals(1, engine.releaseCount)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `release stops the position polling coroutine`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine, AudioPlayerConfig(positionUpdateIntervalMs = 100L))

        player.prepare(source)
        player.play()
        player.release()
        engine.position = 5_000L
        advanceTimeBy(10_000L)

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `transport calls after release are ignored`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()

        player.play()
        player.pause()
        player.stop()
        player.seekTo(1_000L)
        player.seekForward()
        player.seekBackward()
        player.replay()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.started)
        assertTrue(engine.seekTargets.isEmpty())
    }

    @Test
    fun `setPlaybackSpeed after release records the value but does not touch the engine`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.release()
        player.setPlaybackSpeed(2.0f)

        assertEquals(2.0f, player.playbackSpeed)
        assertEquals(1.0f, engine.appliedSpeed)
    }

    @Test
    fun `prepare after release reports a released error`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.release()
        player.prepare(source)

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertIs<AudioPlayerReleasedException>(state.cause)
        assertTrue(engine.loadedSources.isEmpty())
    }

    @Test
    fun `a completion arriving after release does not resurrect the player`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        val listener: PlaybackEngineListener = requireNotNull(engine.listener)
        player.release()
        listener.onCompleted()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `a failure arriving after release does not resurrect the player`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        val listener: PlaybackEngineListener = requireNotNull(engine.listener)
        player.release()
        listener.onFailed(IllegalStateException("too late"))

        assertEquals(PlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `cancelling prepare frees the engine and returns to idle`() = runTest {
        val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: AudioPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        assertEquals(PlayerState.Preparing, player.stateFlow.value)

        loading.cancel()
        loading.join()

        assertTrue(loading.isCancelled)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `a player cancelled mid-load can prepare again`() = runTest {
        val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: AudioPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        loading.cancel()
        loading.join()

        engine.loadDelayMs = 0L
        player.prepare(source)

        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
        assertEquals(2, engine.loadedSources.size)
    }

    @Test
    fun `releasing during a load leaves the player idle and released`() = runTest {
        val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: AudioPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(source) }
        runCurrent()
        player.release()
        advanceTimeBy(1_001L)
        loading.join()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertFalse(player.stateFlow.value.isPlayable)
    }
}
