package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayer
import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.PlayerState
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * The engine contract says a load discards what was loaded before and, when it fails, leaves nothing
 * playable behind. The scripted engine stands in for the real ones, so a consumer asserting that a
 * failed `prepare` stopped the previous track must be able to rely on it.
 */
class FakePlaybackEngineLoadContractTest {

    private fun TestScope.newPlayer(engine: FakePlaybackEngine): AudioPlayer = createAudioPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `a failed load leaves nothing playing and no playhead of the previous source behind`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 30_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(AudioSource.File("/a/first.mp3"))
            player.play()
            engine.advancePositionTo(12_000L)

            engine.loadFailure = IllegalStateException("codec")
            player.prepare(AudioSource.File("/a/second.mp3"))

            assertIs<PlayerState.Error>(player.stateFlow.value)
            assertFalse(engine.isPlaying, "the previous source is still playing after the load that replaced it failed")
        } finally {
            player.release()
        }
    }
}
