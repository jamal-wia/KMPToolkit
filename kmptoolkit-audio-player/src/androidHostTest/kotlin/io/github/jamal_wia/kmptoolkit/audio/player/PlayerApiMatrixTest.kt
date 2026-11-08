package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * The factory-built player (real [MediaPlayerEngine] over Robolectric's `MediaPlayer`) through one
 * lifecycle at several API levels, including the oldest the library supports. Every other host test
 * pins one level; the platform classes the engine touches (`PlaybackParams`, the looper delivery of
 * callbacks, `seekTo`) differ between levels, so the lifecycle must hold at each.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 26, 28, 31, 34], shadows = [ParamsShadowMediaPlayer::class])
class PlayerApiMatrixTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private suspend fun pumpUntil(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 15.seconds
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            withContext(Dispatchers.Default) { delay(1L) }
        }
    }

    @Test
    fun `one lifecycle holds at every api level`() = runTest(timeout = 120.seconds) {
        val url = "https://example.test/matrix-${System.nanoTime()}.mp3"
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(url), ShadowMediaPlayer.MediaInfo(10_000, 0))
        val player: AudioPlayer = createAudioPlayer(context, AudioPlayerConfig(), Dispatchers.Default)
        try {
            val loading = launch { player.prepare(AudioSource.Remote(url)) }
            pumpUntil("Ready") { player.stateFlow.value is PlayerState.Ready }
            loading.join()
            assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)

            player.setPlaybackSpeed(1.5f)
            player.play()
            assertTrue(player.stateFlow.value is PlayerState.Playing, "state ${player.stateFlow.value}")
            player.setPlaybackSpeed(2f)
            assertEquals(2f, player.playbackSpeed)

            player.seekForward(Long.MAX_VALUE)
            assertEquals(10_000L, player.playbackPositionFlow.value, "a skip past the end clamps to the length")
            player.seekBackward(Long.MAX_VALUE)
            assertEquals(0L, player.playbackPositionFlow.value, "a skip before the start clamps to 0")
            player.seekTo(Long.MAX_VALUE)
            assertEquals(10_000L, player.playbackPositionFlow.value)

            player.pause()
            assertTrue(player.stateFlow.value is PlayerState.Paused, "state ${player.stateFlow.value}")
            player.stop()
            assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
            player.replay()
            assertTrue(player.stateFlow.value is PlayerState.Playing, "state ${player.stateFlow.value}")

            player.unload()
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
        } finally {
            player.release()
        }
        assertEquals(PlayerState.Idle, player.stateFlow.value)
    }

    @Test
    fun `a failed source and a retry after release report errors instead of throwing`() = runTest(timeout = 120.seconds) {
        val player: AudioPlayer = createAudioPlayer(context, AudioPlayerConfig(), Dispatchers.Default)
        try {
            val loading = launch { player.prepare(AudioSource.File("/definitely/not/here-${System.nanoTime()}.mp3")) }
            pumpUntil("Error") { player.stateFlow.value is PlayerState.Error }
            loading.join()
            player.prepare(AudioSource.File(" "))
            assertTrue(player.stateFlow.value is PlayerState.Error, "state ${player.stateFlow.value}")
        } finally {
            player.release()
        }
        player.prepare(AudioSource.Remote("https://example.test/after-release.mp3"))
        val first: PlayerState = player.stateFlow.value
        player.prepare(AudioSource.Remote("https://example.test/after-release.mp3"))
        assertTrue(first is PlayerState.Error)
        assertTrue(first === player.stateFlow.value, "the released error must stay one stable value")
    }
}
