package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * Re-initialisation and cancellation: a screen that loads a source, is rotated away while the load is
 * still preparing, and loads again must not leave a `MediaPlayer` behind. Every wait is bounded in
 * real time, because the load runs on `Dispatchers.IO` while the test thread is the main looper.
 */
@RunWith(AndroidJUnit4::class)
class MediaPlayerEngineAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun MediaPlayerEngine.heldPlayer(): MediaPlayer? =
        MediaPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .also { it.isAccessible = true }
            .get(this) as MediaPlayer?

    @Test
    fun `fifty loads cancelled while preparing leave no player held and each one is released`() =
        runTest(timeout = 120.seconds) {
            repeat(CYCLES) { cycle: Int ->
                val url = "https://example.test/clip-$cycle.mp3"
                // Preparation never finishes by itself: the load is always cancelled mid-flight.
                ShadowMediaPlayer.addMediaInfo(
                    DataSource.toDataSource(url),
                    ShadowMediaPlayer.MediaInfo(DURATION_MS, Int.MAX_VALUE),
                )
                val engine = MediaPlayerEngine(context)
                val loading: Job = launch { engine.load(AudioSource.Remote(url)) }

                val deadline = TimeSource.Monotonic.markNow() + 5.seconds
                var player: MediaPlayer? = null
                while (player == null) {
                    shadowOf(Looper.getMainLooper()).idle()
                    player = engine.heldPlayer()
                    if (player == null) {
                        if (deadline.hasPassedNow()) fail("cycle $cycle: the load never created a player")
                        realDelay()
                    }
                }
                loading.cancel()
                while (!loading.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (deadline.hasPassedNow()) fail("cycle $cycle: the cancelled load never unwound")
                    realDelay()
                }
                shadowOf(Looper.getMainLooper()).idle()

                assertNull(engine.heldPlayer(), "cycle $cycle: a player is still held")
                assertEquals(
                    MediaPlayer::class.java.name,
                    assertNotNull(player).javaClass.name,
                )
                assertEquals(
                    org.robolectric.shadows.ShadowMediaPlayer.State.END,
                    shadowOf(player).state,
                    "cycle $cycle: the cancelled load's player was not released",
                )
                engine.release()
            }
        }

    /** A real-time pause: the load runs on Dispatchers.IO, so virtual time would only spin. */
    private suspend fun realDelay() {
        withContext(Dispatchers.Default) { delay(1L) }
    }

    private companion object {
        const val CYCLES: Int = 50
        const val DURATION_MS: Int = 10_000
    }
}
