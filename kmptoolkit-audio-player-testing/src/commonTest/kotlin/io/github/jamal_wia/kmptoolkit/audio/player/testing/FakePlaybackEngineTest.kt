package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayer
import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.PlayerState
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FakePlaybackEngine] is only worth shipping if a real [AudioPlayer] built on it behaves the way
 * the module documents. So these tests drive it exactly the way a consumer would — through the
 * player — rather than calling the engine's methods and asserting they stored what they were given.
 */
class FakePlaybackEngineTest {

    private val source: AudioSource = AudioSource.Remote("https://example.test/voice.m4a")

    private fun TestScope.newPlayer(engine: FakePlaybackEngine): AudioPlayer = createAudioPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `a player on a fresh fake reaches ready with the configured duration`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 30_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)

        assertEquals(PlayerState.Ready(30_000L), player.stateFlow.value)
        assertEquals(listOf(source), engine.loadedSources)
        assertTrue(engine.hasListener)
    }

    @Test
    fun `loadFailure turns the next prepare into an error`() = runTest {
        val failure = IllegalArgumentException("no such host")
        val engine = FakePlaybackEngine().apply { loadFailure = failure }
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertSame(failure, state.cause)
    }

    @Test
    fun `clearing loadFailure lets the next prepare succeed`() = runTest {
        val engine = FakePlaybackEngine().apply { loadFailure = IllegalStateException("transient") }
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        engine.loadFailure = null
        player.prepare(source)

        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
    }

    @Test
    fun `play and pause are visible on the fake`() = runTest {
        val engine = FakePlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        assertTrue(engine.isPlaying)

        player.pause()
        assertFalse(engine.isPlaying)
    }

    @Test
    fun `advancing the playhead is picked up by the next position poll`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 30_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.advancePositionBy(4_000L)
        advanceTimeBy(101L)

        assertEquals(4_000L, player.playbackPositionFlow.value)
        player.release()
    }

    @Test
    fun `advancePositionBy never runs past the duration`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 1_000L)

        engine.advancePositionBy(5_000L)

        assertEquals(1_000L, engine.positionMs)
    }

    @Test
    fun `completePlayback drives the player to completed`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 30_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.completePlayback()

        assertEquals(PlayerState.Completed(30_000L), player.stateFlow.value)
        assertEquals(30_000L, player.playbackPositionFlow.value)
    }

    @Test
    fun `failPlayback drives the player to error`() = runTest {
        val failure = IllegalStateException("route lost")
        val engine = FakePlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        engine.failPlayback(failure)

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertSame(failure, state.cause)
    }

    @Test
    fun `seeks reach the fake playhead`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 60_000L)
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        player.seekTo(12_000L)

        assertEquals(12_000L, engine.positionMs)
        // A player left playing owns a coroutine that delays forever; runTest drains the shared
        // scheduler when the body returns, so leaving it alive hangs the run instead of failing it.
        player.release()
    }

    @Test
    fun `the fake records exactly one release for a double release`() = runTest {
        val engine = FakePlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.release()
        player.close()

        assertEquals(1, engine.releaseCount)
        assertFalse(engine.hasListener)
    }

    @Test
    fun `a completion signalled after release is dropped`() = runTest {
        val engine = FakePlaybackEngine()
        val player: AudioPlayer = newPlayer(engine)

        player.prepare(source)
        player.play()
        player.release()
        engine.completePlayback()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `loadDelayMs gives a test a window to cancel the load`() = runTest {
        val engine = FakePlaybackEngine().apply { loadDelayMs = 500L }
        val player: AudioPlayer = newPlayer(engine)

        val loading = launch { player.prepare(source) }
        runCurrent()
        assertEquals(PlayerState.Preparing, player.stateFlow.value)
        loading.cancel()
        loading.join()

        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)
    }
}
