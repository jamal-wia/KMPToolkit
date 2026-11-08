package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * A consumer that retries `prepare` whenever the state becomes `Error` (a common reaction) meets a
 * player that was released while a retry was in flight. `prepare` after `release` is documented to set
 * `Error(AudioPlayerReleasedException)` and load nothing; that must be a terminal answer, not a new
 * value every time, or the retry and the answer feed each other forever. The loop is capped so the test
 * cannot hang.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlayerReleasedRetryTest {

    @Test
    fun `retrying prepare on every error does not spin forever on a released player`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        var attempts = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            player.stateFlow.collect { state: PlayerState ->
                if (state is PlayerState.Error && attempts < CAP) {
                    attempts++
                    player.prepare(AudioSource.File("/a/b.mp3"))
                }
            }
        }
        player.release()

        player.prepare(AudioSource.File("/a/b.mp3"))

        assertTrue(attempts < 10, "the retry ran $attempts times (cap $CAP) on a released player")
    }

    @Test
    fun `two prepare calls after release report the very same error instance`() = runTest {
        val player: AudioPlayer = createAudioPlayer(RecordingPlaybackEngine(), AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        player.release()

        player.prepare(AudioSource.File("/a/b.mp3"))
        val first: PlayerState = player.stateFlow.value
        player.prepare(AudioSource.File("/a/b.mp3"))
        val second: PlayerState = player.stateFlow.value

        assertTrue(first is PlayerState.Error && second is PlayerState.Error, "states were $first and $second")
        assertSame(first.cause, second.cause)
    }

    private companion object {
        const val CAP = 500
    }
}
