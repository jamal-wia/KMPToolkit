package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.media.PlaybackParams
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** A device whose `MediaPlayer` refuses every playback rate with the exception its documentation names. */
@Implements(MediaPlayer::class)
class RateRefusingShadowMediaPlayer : ShadowMediaPlayer() {

    @Implementation
    fun getPlaybackParams(): PlaybackParams = PlaybackParams()

    @Implementation
    fun setPlaybackParams(params: PlaybackParams) {
        throw IllegalArgumentException("unsupported rate")
    }
}

/**
 * `MediaPlayer.setPlaybackParams` throws `IllegalArgumentException` for a rate the device does not
 * support. The player promises that nothing throws and that its state describes what is audible, so a
 * refused rate must neither escape `play()` nor leave audio playing behind a stale state.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [RateRefusingShadowMediaPlayer::class])
class MediaPlayerEngineSpeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a rate the device refuses neither escapes play nor desynchronises the state`() = runTest(timeout = 120.seconds) {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource("https://example.test/speed.mp3"),
            ShadowMediaPlayer.MediaInfo(10_000, 0),
        )
        val engine = MediaPlayerEngine(context)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), Dispatchers.Default)
        try {
            player.setPlaybackSpeed(2f)
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            val loading = launch { player.prepare(AudioSource.Remote("https://example.test/speed.mp3")) }
            while (player.stateFlow.value !is PlayerState.Ready) {
                shadowOf(Looper.getMainLooper()).idle()
                if (deadline.hasPassedNow()) fail("timed out waiting for Ready, state ${player.stateFlow.value}")
                withContext(Dispatchers.Default) { delay(1L) }
            }
            loading.join()

            player.play()
            player.setPlaybackSpeed(3f)

            assertTrue(player.stateFlow.value is PlayerState.Playing, "state was ${player.stateFlow.value}")
            assertEquals(3f, player.playbackSpeed)
        } finally {
            player.release()
        }
    }
}
