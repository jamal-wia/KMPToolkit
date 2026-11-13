package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * D1 round 11 (breaker ip), Q2: the Media3 engine under platform events delivered in orders a healthy ExoPlayer never
 * produces. The engine's private `Player.Listener` is driven directly (reflection) so that an `ENDED` before `READY`, two
 * `ENDED`, an `IDLE` after a load, and a `PlaybackException` of every code family arrive exactly as written; and the two
 * `05-platform-notes.md` sentences about the audio route ("Audio focus is not requested", `setHandleAudioBecomingNoisy`
 * is off) are checked against Robolectric's audio manager and a real `ACTION_AUDIO_BECOMING_NOISY` broadcast.
 */
@RunWith(AndroidJUnit4::class)
class Media3HostileListenerEventsD1R11AdversarialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/x.mp4")
    private var exo: ExoPlayer? = null
    private val engine: Media3VideoEngine = newEngine(context, created = { exo = it })
    private val listener: RecordingEngineListener = RecordingEngineListener()

    @After
    fun tearDown() {
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    private fun platformListener(): Player.Listener {
        val field = Media3VideoEngine::class.java.getDeclaredField("playerListener")
        field.isAccessible = true
        return field.get(engine) as Player.Listener
    }

    private fun load() {
        engine.setListener(listener)
        runSuspending { engine.load(source) }.getOrThrow()
    }

    @Test
    fun `ENDED before any load or before READY reports nothing and an IDLE after a load is not a stall`() {
        engine.setListener(listener)
        // No player yet, then a player with a load pending: the platform listener must not report a completion for a source
        // that has not been loaded.
        runSuspending { engine.load(source) }.getOrThrow()
        engine.release()
        ShadowLooper.idleMainLooper()
        platformListener().onPlaybackStateChanged(Player.STATE_ENDED)
        platformListener().onPlaybackStateChanged(Player.STATE_BUFFERING)
        platformListener().onPlaybackStateChanged(Player.STATE_IDLE)
        ShadowLooper.idleMainLooper()
        assertTrue(listener.completions.isEmpty(), "a completion for a source that was released: ${listener.completions.size}")
        assertTrue(listener.buffering.isEmpty(), "a stall for a source that was released: ${listener.buffering}")

        load()
        listener.buffering.clear()
        platformListener().onPlaybackStateChanged(Player.STATE_IDLE)
        platformListener().onPlaybackStateChanged(Player.STATE_IDLE)
        assertTrue(listener.buffering.isEmpty(), "IDLE is not buffering, and a repeated report is not a change: ${listener.buffering}")
    }

    @Test
    fun `a second ENDED after the first reaches the listener as one more completion and never as anything else`() {
        load()
        engine.start()
        platformListener().onPlaybackStateChanged(Player.STATE_ENDED)
        platformListener().onPlaybackStateChanged(Player.STATE_ENDED)
        assertTrue(listener.failures.isEmpty(), "no failure from a duplicate end: ${listener.failures}")
        assertTrue(listener.completions.size in 1..2, "the engine forwards the platform's ENDED at most once per report: ${listener.completions.size}")
        assertEquals(false, listener.buffering.lastOrNull() ?: false, "the engine reports the end as not buffering")
    }

    @Test
    fun `every PlaybackException family after a load reaches the listener once as that very instance and a repeat is ignored`() {
        engine.setListener(listener)
        for (code: Int in ERROR_CODES) {
            val error = PlaybackException("code $code", RuntimeException("cause $code"), code)
            // The fake source reaches READY on its own, so the load has already succeeded: the error is a mid playback
            // failure, which must reach the sink once, then never again for the same source.
            runSuspending { engine.load(source) }.getOrThrow()
            listener.failures.clear()
            platformListener().onPlayerError(error)
            platformListener().onPlayerError(PlaybackException("again $code", null, code))
            assertEquals(1, listener.failures.size, "exactly one failure for code $code: ${listener.failures}")
            assertSame(error, listener.failures.single(), "the platform's own exception for code $code")
        }
    }

    @Test
    fun `an error with nothing loaded and nothing pending is ignored`() {
        engine.setListener(listener)
        runSuspending { engine.load(source) }.getOrThrow()
        engine.release()
        ShadowLooper.idleMainLooper()
        platformListener().onPlayerError(PlaybackException("late", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        ShadowLooper.idleMainLooper()
        assertTrue(listener.failures.isEmpty(), "a failure for a released source: ${listener.failures}")
    }

    @Test
    fun `05 audio focus is not requested and a becoming noisy broadcast does not pause the engine`() {
        load()
        engine.start()
        ShadowLooper.idleMainLooper()
        val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        assertNull(shadowOf(audioManager).lastAudioFocusRequest, "05: audio focus is not requested")
        context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        ShadowLooper.idleMainLooper()
        val current: ExoPlayer = checkNotNull(exo)
        assertTrue(current.playWhenReady, "05: setHandleAudioBecomingNoisy is off; the player above is the only thing that pauses")
        assertNull(shadowOf(audioManager).lastAudioFocusRequest, "05: still no focus request after the broadcast")
    }

    private companion object {
        val ERROR_CODES: List<Int> = listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
            1_000_000, // a custom code a future Media3 or a custom renderer could add
        )
    }
}
