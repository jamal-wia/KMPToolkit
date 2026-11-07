package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Edge inputs read against the documented contract: a source whose length is not known, a seek that
 * lands exactly on the end, a length the platform reports late.
 */
class AudioPlayerEdgeInputTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private suspend fun TestScope.withPlayer(
        engine: RecordingPlaybackEngine,
        body: suspend (AudioPlayer) -> Unit,
    ) {
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            body(player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `skipping forward in a source of unknown length never rewinds it`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        withPlayer(engine) { player ->
            player.prepare(source)
            player.play()
            engine.position = 30_000L

            player.seekForward(5_000L)

            assertTrue(engine.position >= 30_000L, "seekForward moved the playhead from 30000 to ${engine.position}")
        }
    }

    @Test
    fun `playing from a completed source after seeking to its end starts over like replay`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        withPlayer(engine) { player ->
            player.prepare(source)
            player.play()
            engine.position = 10_000L
            engine.listener?.onCompleted()
            runCurrent()
            assertEquals(PlayerState.Completed(10_000L), player.stateFlow.value)

            player.seekForward(5_000L)
            player.play()

            assertEquals(0L, engine.position, "the playhead sits at the end and play() resumed from there: ${engine.seekTargets}")
        }
    }
}
