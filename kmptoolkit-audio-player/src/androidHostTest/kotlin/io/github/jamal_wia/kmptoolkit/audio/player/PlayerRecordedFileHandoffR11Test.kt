package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** What a platform engine does with a path: open it, or fail the way `MediaPlayer.setDataSource(path)` does. */
private class FileOpeningEngine(private val inner: FakePlaybackEngine = FakePlaybackEngine(durationMs = 5_000L)) :
    PlaybackEngine by inner {
    val opened: MutableList<String> = mutableListOf()
    val driver: FakePlaybackEngine get() = inner

    override suspend fun load(source: AudioSource) {
        require(source is AudioSource.File) { "this engine plays recordings only" }
        if (!File(source.path).isFile) throw FileNotFoundException(source.path)
        opened += source.path
        inner.load(source)
    }
}

/**
 * Round 11, Q4 (player half): the way a recorded file reaches the player. A finished recording is handed over as
 * `AudioSource.File(path)`: it plays; the same file deleted by the consumer before play ends in a typed
 * `PlayerState.Error` that carries the platform's own exception (never a throw out of `prepare`); the same player
 * then plays the next recording; a path with a blank name is refused before the engine; a cancelled hand-over
 * (the caller cancels while the load runs) ends in `Idle`, and releasing the player never deletes the file (the
 * library never deletes a file, 04-api-reference). Real media is not involved: the engine is a stand-in that opens the
 * path like the platform does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRecordedFileHandoffR11Test {

    private fun tempRecording(directory: File, name: String): File =
        File(directory, name).also { it.writeText("audio") }

    @Test
    fun `a recording plays then fails typed once deleted and the player plays the next one`() = runTest {
        val directory: File = java.nio.file.Files.createTempDirectory("r11-hand").toFile()
        val engine = FileOpeningEngine()
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val first: File = tempRecording(directory, "recording_1.m4a")
            launch { player.prepare(AudioSource.File(first.path)) }
            runCurrent()
            assertEquals(PlayerState.Ready(5_000L), player.stateFlow.value)
            player.play()
            assertIs<PlayerState.Playing>(player.stateFlow.value)

            assertTrue(first.delete())
            val job = launch { player.prepare(AudioSource.File(first.path)) }
            runCurrent()
            assertTrue(job.isCompleted, "prepare of a deleted file must return, not hang or throw")
            val failed: PlayerState.Error = assertIs<PlayerState.Error>(player.stateFlow.value)
            assertIs<FileNotFoundException>(failed.cause)
            player.play()
            assertIs<PlayerState.Error>(player.stateFlow.value, "a transport call revived a failed player")

            val second: File = tempRecording(directory, "recording_2.m4a")
            launch { player.prepare(AudioSource.File(second.path)) }
            runCurrent()
            assertEquals(PlayerState.Ready(5_000L), player.stateFlow.value)
            assertEquals(listOf(first.path, second.path), engine.opened)

            player.release()
            assertTrue(second.isFile, "releasing the player deleted the file it played")
        } finally {
            player.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a blank hand-over path is refused before the engine and a cancelled hand-over ends idle`() = runTest {
        val engine = FileOpeningEngine().also { it.driver.loadDelayMs = 500L }
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            launch { player.prepare(AudioSource.File("  ")) }
            runCurrent()
            val refused: PlayerState.Error = assertIs<PlayerState.Error>(player.stateFlow.value)
            assertIs<IllegalArgumentException>(refused.cause)
            assertEquals(emptyList(), engine.opened)

            val directory: File = java.nio.file.Files.createTempDirectory("r11-cancel").toFile()
            try {
                val file: File = tempRecording(directory, "recording_3.m4a")
                val loading = launch { player.prepare(AudioSource.File(file.path)) }
                runCurrent()
                assertEquals(PlayerState.Preparing, player.stateFlow.value)
                loading.cancel()
                loading.join()
                assertEquals(PlayerState.Idle, player.stateFlow.value)
                assertTrue(file.isFile, "a cancelled hand-over deleted the file")
            } finally {
                directory.deleteRecursively()
            }
        } finally {
            player.release()
        }
    }
}
