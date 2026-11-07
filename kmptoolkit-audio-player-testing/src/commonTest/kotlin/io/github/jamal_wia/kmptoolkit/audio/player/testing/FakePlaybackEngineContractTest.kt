package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayer
import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.PlayerState
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Edge inputs for the scripted engine: an amount that overflows, an unknown duration. The fake
 * documents that it moves the playhead forward and never past the duration, so no amount, however
 * large, may carry the playhead backwards or negative.
 */
class FakePlaybackEngineContractTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private fun TestScope.newPlayer(engine: FakePlaybackEngine): AudioPlayer = createAudioPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `advancing the playhead by a huge amount lands on the end and never goes backwards`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        engine.advancePositionTo(5_000L)

        engine.advancePositionBy(Long.MAX_VALUE)

        assertEquals(10_000L, engine.positionMs, "a forward move by Long.MAX_VALUE is a move to the end")
    }

    @Test
    fun `a player never reports a negative position after the fake was advanced by a huge amount`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.advancePositionTo(1L)
            engine.advancePositionBy(Long.MAX_VALUE)

            player.pause()

            val state = player.stateFlow.value
            assertTrue(state is PlayerState.Paused && state.currentPosition >= 0L, "state was $state")
        } finally {
            player.release()
        }
    }

    @Test
    fun `advancing by zero or a small amount from beyond the end never moves the playhead back`() {
        val engine = FakePlaybackEngine(durationMs = 1_000L)
        engine.advancePositionTo(5_000L)

        engine.advancePositionBy(0L)

        assertTrue(engine.positionMs >= 5_000L || engine.positionMs == 1_000L, "position was ${engine.positionMs}")
    }
}
