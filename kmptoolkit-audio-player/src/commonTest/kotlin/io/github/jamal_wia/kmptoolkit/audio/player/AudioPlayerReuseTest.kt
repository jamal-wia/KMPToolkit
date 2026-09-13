package io.github.jamal_wia.kmptoolkit.audio.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two ways a long-lived player is reused: [AudioPlayer.unload] between pieces of audio, and a
 * [AudioPlayer.prepare] that replaces one still loading. Both are what a player shared by several
 * screens does all day, and both leave a hung caller or a stale state behind when they are wrong.
 *
 * A test that leaves a player playing releases it at the end: the polling loop shares the test
 * scheduler and would otherwise keep `runTest` advancing virtual time forever.
 */
class AudioPlayerReuseTest {

    private val first: AudioSource = AudioSource.Remote("https://example.test/first.mp3")
    private val second: AudioSource = AudioSource.Remote("https://example.test/second.mp3")
    private val third: AudioSource = AudioSource.Remote("https://example.test/third.mp3")

    private fun TestScope.newPlayer(
        engine: RecordingPlaybackEngine,
        config: AudioPlayerConfig = AudioPlayerConfig(),
    ): AudioPlayer = createAudioPlayer(
        engine = engine,
        config = config,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `unload frees the engine and returns to idle keeping the speed`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.setPlaybackSpeed(1.5f)
        player.prepare(first)
        player.play()
        player.unload()

        assertEquals(1, engine.releaseCount)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        assertEquals(1.5f, player.playbackSpeed)
    }

    @Test
    fun `an unloaded player prepares and plays again`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 7_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(first)
        player.unload()
        player.prepare(second)
        player.play()

        assertEquals(listOf(first, second), engine.loadedSources)
        assertEquals(PlayerState.Playing(duration = 7_000L, currentPosition = 0L), player.stateFlow.value)
        player.release()
    }

    @Test
    fun `unload keeps the listener attached so the next source still completes`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 7_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(first)
        player.unload()
        player.prepare(second)
        player.play()
        requireNotNull(engine.listener).onCompleted()

        assertEquals(PlayerState.Completed(7_000L), player.stateFlow.value)
    }

    @Test
    fun `unload stops the position polling`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine, AudioPlayerConfig(positionUpdateIntervalMs = 100L))

        player.prepare(first)
        player.play()
        player.unload()
        engine.position = 5_000L
        advanceTimeBy(1_000L)

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
    }

    @Test
    fun `a completion arriving after unload is dropped`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(first)
        player.play()
        val listener: PlaybackEngineListener = requireNotNull(engine.listener)
        player.unload()
        listener.onCompleted()
        listener.onFailed(IllegalStateException("too late"))

        assertEquals(PlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `unloading during a load abandons it without failing the caller`() = runTest {
        val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
        val player: AudioPlayer = newPlayer(engine)

        val loading: Job = launch { player.prepare(first) }
        runCurrent()
        player.unload()
        advanceUntilIdle()

        assertTrue(loading.isCompleted)
        assertFalse(loading.isCancelled)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(0, engine.activeLoads)
    }

    @Test
    fun `unload on a released player does nothing`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(first)
        player.release()
        player.unload()
        player.prepare(second)

        assertEquals(1, engine.releaseCount)
        assertEquals(listOf(first), engine.loadedSources)
    }

    @Test
    fun `a prepare that replaces one still loading wins and the replaced caller returns`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 4_000L).apply { loadDelayMs = 1_000L }
        val player: AudioPlayer = newPlayer(engine)

        val replaced: Job = launch { player.prepare(first) }
        runCurrent()
        val replacing: Job = launch { player.prepare(second) }
        advanceUntilIdle()

        assertTrue(replaced.isCompleted)
        assertFalse(replaced.isCancelled)
        assertTrue(replacing.isCompleted)
        assertEquals(PlayerState.Ready(4_000L), player.stateFlow.value)
        assertEquals(listOf(first, second), engine.loadedSources)
    }

    @Test
    fun `a replaced load finishes unwinding before the next one starts`() = runTest {
        val engine = RecordingPlaybackEngine().apply {
            loadDelayMs = 1_000L
            unwindDelayMs = 500L
        }
        val player: AudioPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        launch { player.prepare(second) }
        runCurrent()
        // Replaces the second while it is still waiting for the first to unwind.
        launch { player.prepare(third) }
        advanceUntilIdle()

        assertEquals(1, engine.maxConcurrentLoads)
        assertEquals(listOf(first, third), engine.loadedSources)
        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
    }

    @Test
    fun `a failure in a replaced load does not overwrite the replacing one`() = runTest {
        val engine = RecordingPlaybackEngine().apply {
            loadDelayMs = 1_000L
            failureWhenCancelled = IllegalStateException("released while loading")
        }
        val player: AudioPlayer = newPlayer(engine)

        launch { player.prepare(first) }
        runCurrent()
        launch { player.prepare(second) }
        runCurrent()
        engine.failureWhenCancelled = null
        advanceUntilIdle()

        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
    }

    @Test
    fun `play from completed starts over from the beginning`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 3_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(first)
        player.play()
        engine.position = 3_000L
        requireNotNull(engine.listener).onCompleted()
        player.play()

        assertEquals(0L, engine.seekTargets.last())
        assertEquals(PlayerState.Playing(duration = 3_000L, currentPosition = 0L), player.stateFlow.value)
        assertEquals(0L, player.playbackPositionFlow.value)
        player.release()
    }
}
