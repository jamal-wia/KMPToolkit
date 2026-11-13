package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 11, Q5: sentences of the player docs that no test pinned (found by mapping every normative sentence of
 * `docs/kmptoolkit-audio-player/` to a test): the interface default of `unload()`, the absence of a built-in load
 * deadline, and that `playbackPositionFlow` is not woken by what does not move the playhead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDocsGapsR11Test {

    /** An implementation written against an older version of the interface: only the abstract members. */
    private class OldPlayer : AudioPlayer {
        var stops: Int = 0
        override val stateFlow: StateFlow<PlayerState> = MutableStateFlow(PlayerState.Idle)
        override val playbackPositionFlow: StateFlow<Long> = MutableStateFlow(0L)
        override val playbackSpeed: Float = 1.0f
        override suspend fun prepare(source: AudioSource) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() { stops++ }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekForward(amountMs: Long) = Unit
        override fun seekBackward(amountMs: Long) = Unit
        override fun replay() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun release() = Unit
    }

    @Test
    fun `the default unload only calls stop for an implementation written against an older interface`() {
        val old = OldPlayer()
        old.unload()
        old.unload()
        assertEquals(2, old.stops, "the documented default of unload() is stop(), nothing more")
    }

    @Test
    fun `there is no built-in load deadline so a load that takes an hour of virtual time still succeeds`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 9_000L).apply { loadDelayMs = 3_600_000L }
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val loading = launch { player.prepare(AudioSource.Remote("https://example.test/slow.mp3")) }
            runCurrent()
            advanceTimeBy(3_599_000L)
            runCurrent()
            assertEquals(PlayerState.Preparing, player.stateFlow.value, "the player gave up on its own")
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue(loading.isCompleted)
            assertEquals(PlayerState.Ready(9_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the position flow stays quiet for a rate change a repeated pause and a tick that moves nothing`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 40_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        val seen: MutableList<Long> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { player.playbackPositionFlow.collect { seen += it } }
        try {
            val loading = launch { player.prepare(AudioSource.Remote("https://example.test/q.mp3")) }
            runCurrent()
            assertTrue(loading.isCompleted)
            player.play()
            runCurrent()
            repeat(5) { // five polls with the playhead where it is
                advanceTimeBy(100L)
                runCurrent()
            }
            player.setPlaybackSpeed(2f)
            player.setPlaybackSpeed(1.5f)
            player.pause()
            player.pause()
            runCurrent()
            assertEquals(listOf(0L), seen, "the flow woke up although the playhead did not move: $seen")
            engine.advancePositionTo(1_234L)
            player.play()
            advanceTimeBy(150L)
            runCurrent()
            player.pause()
            assertEquals(listOf(0L, 1_234L), seen)
        } finally {
            player.release()
        }
    }
}
