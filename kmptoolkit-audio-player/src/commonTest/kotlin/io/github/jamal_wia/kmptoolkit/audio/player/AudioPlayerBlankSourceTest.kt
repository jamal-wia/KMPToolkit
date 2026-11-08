package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * `NSURL.fileURLWithPath("")` raises an Objective-C exception that Kotlin/Native cannot catch (the
 * recorder documents exactly this and refuses a blank path before the platform is touched). The iOS
 * engine builds its file URL that way, so a blank file path handed to it would end the process instead of
 * ending in `PlayerState.Error` as it does on Android.
 */
class AudioPlayerBlankSourceTest {

    @Test
    fun `a non-blank file path still reaches the engine`() = runTest {
        val engine = RecordingPlaybackEngine()
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(AudioSource.File("/a/b.mp3"))

            assertEquals(listOf<AudioSource>(AudioSource.File("/a/b.mp3")), engine.loadedSources)
            assertTrue(player.stateFlow.value is PlayerState.Ready, "state was ${player.stateFlow.value}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `a blank file path is refused before the engine is asked to load it`() = runTest {
        for (path in listOf("", " ", "\t\n")) {
            val engine = RecordingPlaybackEngine()
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
            try {
                player.prepare(AudioSource.File(path))

                assertTrue(engine.loadedSources.isEmpty(), "the engine was asked to load the blank file path '$path'")
                assertTrue(player.stateFlow.value is PlayerState.Error, "state was ${player.stateFlow.value} for '$path'")
            } finally {
                player.release()
            }
        }
    }
}
