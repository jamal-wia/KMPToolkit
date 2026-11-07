package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

/**
 * A source replaced while the platform still has callbacks of the previous one in flight. The
 * platform reads a `MediaPlayer`'s listener on the main looper while the engine clears it on the
 * thread that loads the next source, so a callback can be delivered by a player the engine has
 * already let go of. The listener objects are captured before the engine replaces them and invoked
 * afterwards, which is exactly that interleaving. Every wait is bounded in real time.
 */
@RunWith(AndroidJUnit4::class)
class MediaPlayerEngineChaosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun MediaPlayerEngine.heldPlayer(): MediaPlayer? =
        MediaPlayerEngine::class.java.getDeclaredField("mediaPlayer")
            .also { it.isAccessible = true }
            .get(this)
            .let { holder -> (holder as MutableStateFlow<*>).value as MediaPlayer? }

    private class Listeners(
        val completion: MediaPlayer.OnCompletionListener,
        val error: MediaPlayer.OnErrorListener,
        val player: MediaPlayer,
    ) {
        fun fireCompletion() = completion.onCompletion(player)
        fun fireError() {
            error.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun capture(player: MediaPlayer): Listeners {
        val shadow: ShadowMediaPlayer = shadowOf(player)
        val errorField = ShadowMediaPlayer::class.java.getDeclaredField("errorListener").also { it.isAccessible = true }
        return Listeners(
            completion = assertNotNull(shadow.onCompletionListener, "no steady-state completion listener"),
            error = assertNotNull(errorField.get(shadow) as MediaPlayer.OnErrorListener?, "no error listener"),
            player = player,
        )
    }

    private fun register(url: String, durationMs: Int, preparationDelayMs: Int) {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(url),
            ShadowMediaPlayer.MediaInfo(durationMs, preparationDelayMs),
        )
    }

    private suspend fun pumpUntil(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            withContext(Dispatchers.Default) { delay(1L) }
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `an error the previous source's player delivers late never fails the source now loading`() =
        runTest(timeout = 120.seconds) {
            register(URL_A, 10_000, 0)
            register(URL_B, 10_000, Int.MAX_VALUE)
            val engine = MediaPlayerEngine(context)
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), Dispatchers.Default)
            val loadingA: Job = launch { player.prepare(AudioSource.Remote(URL_A)) }
            pumpUntil("A ready") { player.stateFlow.value is PlayerState.Ready }
            loadingA.join()
            val a: Listeners = capture(assertNotNull(engine.heldPlayer()))

            val loadingB: Job = launch { player.prepare(AudioSource.Remote(URL_B)) }
            pumpUntil("B preparing on a new player") {
                val held: MediaPlayer? = engine.heldPlayer()
                held != null && held !== a.player && player.stateFlow.value == PlayerState.Preparing
            }

            a.fireError()
            idle()
            player.seekTo(0L) // drain the lock
            assertEquals(
                PlayerState.Preparing,
                player.stateFlow.value,
                "an error of the replaced source's player failed the source that is loading",
            )
            loadingB.cancel()
            pumpUntil("B cancelled") { loadingB.isCompleted }
            player.release()
        }

    @Test
    fun `a completion the previous source's player delivers late never completes the source now loaded`() =
        runTest(timeout = 120.seconds) {
            register(URL_A, 10_000, 0)
            register(URL_C, 5_000, 0)
            val engine = MediaPlayerEngine(context)
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), Dispatchers.Default)
            val loadingA: Job = launch { player.prepare(AudioSource.Remote(URL_A)) }
            pumpUntil("A ready") { player.stateFlow.value is PlayerState.Ready }
            loadingA.join()
            val a: Listeners = capture(assertNotNull(engine.heldPlayer()))

            val loadingC: Job = launch { player.prepare(AudioSource.Remote(URL_C)) }
            pumpUntil("C ready on a new player") {
                val held: MediaPlayer? = engine.heldPlayer()
                held != null && held !== a.player && player.stateFlow.value is PlayerState.Ready
            }
            loadingC.join()
            assertNotSame(a.player, engine.heldPlayer())
            // Not played: Robolectric's MediaPlayer has no playback params, which play() reads.
            assertIs<PlayerState.Ready>(player.stateFlow.value)

            a.fireCompletion()
            idle()
            player.seekTo(0L) // drain the lock
            assertIs<PlayerState.Ready>(
                player.stateFlow.value,
                "a completion of the replaced source's player completed the source that is loaded",
            )
            player.release()
        }

    private companion object {
        const val URL_A: String = "https://example.test/chaos-a.mp3"
        const val URL_B: String = "https://example.test/chaos-b.mp3"
        const val URL_C: String = "https://example.test/chaos-c.mp3"
    }
}
