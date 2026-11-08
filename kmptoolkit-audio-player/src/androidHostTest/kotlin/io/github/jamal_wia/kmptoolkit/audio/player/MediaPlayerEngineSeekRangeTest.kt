package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/** A `MediaPlayer` that remembers every position it was asked to seek to. */
@Implements(MediaPlayer::class)
class SeekRecordingShadowMediaPlayer : ShadowMediaPlayer() {

    @Implementation
    override fun seekTo(seekTo: Long, mode: Int) {
        requested += seekTo
        super.seekTo(seekTo, mode)
    }

    companion object {
        val requested: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList()
    }
}

/**
 * A relative skip passes its target on unclamped while the length of the source is not known (so a
 * skip from 30 s by 5 s does not land on 0). `MediaPlayer.seekTo` takes an `Int`: a target beyond
 * `Int.MAX_VALUE` milliseconds must saturate rather than wrap to a negative or tiny value that moves
 * the playhead backwards.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [SeekRecordingShadowMediaPlayer::class])
class MediaPlayerEngineSeekRangeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun skipOnUnknownLength(amountMs: Long): List<Long> = seekOnSource(durationMs = 0, seek = { it.seekForward(amountMs) })

    private fun seekOnSource(durationMs: Int, seek: (AudioPlayer) -> Unit): List<Long> {
        SeekRecordingShadowMediaPlayer.requested.clear()
        var recorded: List<Long> = emptyList()
        kotlinx.coroutines.test.runTest(timeout = 120.seconds) {
            ShadowMediaPlayer.addMediaInfo(
                DataSource.toDataSource("https://example.test/live.mp3"),
                ShadowMediaPlayer.MediaInfo(durationMs, 0),
            )
            val player: AudioPlayer = createAudioPlayer(MediaPlayerEngine(context), AudioPlayerConfig(), Dispatchers.Default)
            try {
                val deadline = TimeSource.Monotonic.markNow() + 10.seconds
                val loading = launch { player.prepare(AudioSource.Remote("https://example.test/live.mp3")) }
                while (player.stateFlow.value !is PlayerState.Ready) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (deadline.hasPassedNow()) fail("timed out waiting for Ready, state ${player.stateFlow.value}")
                    withContext(Dispatchers.Default) { delay(1L) }
                }
                loading.join()
                SeekRecordingShadowMediaPlayer.requested.clear()

                seek(player)
                recorded = SeekRecordingShadowMediaPlayer.requested.toList()
            } finally {
                player.release()
            }
        }
        return recorded
    }

    @Test
    fun `skipping forward by the largest amount in a source of unknown length asks the platform for the far end`() {
        val recorded: List<Long> = skipOnUnknownLength(Long.MAX_VALUE)
        assertTrue(recorded.isNotEmpty(), "the platform was never asked to seek")
        assertTrue(recorded.all { it >= Int.MAX_VALUE.toLong() }, "a forward skip by Long.MAX_VALUE asked the platform for $recorded")
    }

    @Test
    fun `skipping forward by more than an int of milliseconds never wraps around to an earlier position`() {
        val amount: Long = 4_294_967_296L + 100L
        val recorded: List<Long> = skipOnUnknownLength(amount)
        assertTrue(recorded.isNotEmpty(), "the platform was never asked to seek")
        assertTrue(recorded.all { it >= 100L }, "a forward skip by $amount asked the platform for $recorded")
        assertTrue(recorded.all { it >= Int.MAX_VALUE.toLong() }, "a forward skip by $amount asked the platform for $recorded")
    }

    @Test
    fun `on a source of known length an absolute seek reaches the platform unchanged`() {
        val recorded: List<Long> = seekOnSource(durationMs = 60_000, seek = { it.seekTo(1_234L) })
        assertTrue(recorded.isNotEmpty(), "the platform was never asked to seek")
        assertTrue(recorded.all { it == 1_234L }, "seekTo(1_234) asked the platform for $recorded")
    }
}
