package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Parity with the video player's length rules: a source of unknown length that plays to its end
 * completes where it ended, and a Ready player picks up a length the platform learned after the load.
 */
class AudioPlayerRound5VideoParityTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private fun TestScope.newPlayer(engine: RecordingPlaybackEngine): AudioPlayer =
        createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))

    @Test
    fun `completion of a source of unknown length keeps the end as the playhead and the length`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.position = 45_000L
            engine.listener!!.onCompleted()
            assertEquals(PlayerState.Completed(45_000L), player.stateFlow.value)
            assertEquals(45_000L, player.playbackPositionFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `an unknown length completes with the last published playhead when the engine reads zero`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.position = 30_000L
            player.pause()
            player.play()
            engine.position = 0L
            engine.listener!!.onCompleted()
            assertEquals(PlayerState.Completed(30_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `control - a source of known length completes at its length`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 8_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.position = 8_000L
            engine.listener!!.onCompleted()
            assertEquals(PlayerState.Completed(8_000L), player.stateFlow.value)
            assertEquals(8_000L, player.playbackPositionFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a seek of a Ready player picks up a length the platform reported after the load`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            assertEquals(PlayerState.Ready(0L), player.stateFlow.value)
            engine.duration = 8_000L
            player.seekTo(5_000L)
            assertEquals(PlayerState.Ready(8_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }
}
