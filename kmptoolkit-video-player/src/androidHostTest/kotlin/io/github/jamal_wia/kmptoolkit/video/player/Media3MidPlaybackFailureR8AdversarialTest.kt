package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.fakeSource
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A playback failure AFTER a successful load (a stream that dies mid-video) reaches the player as
 * `VideoPlayerState.Error` with the platform's own `PlaybackException` (docs 03-guide "Handling
 * errors", 04-api-reference). The failure is injected through a renderer that throws once told to,
 * which Media3's test player turns into a real `onPlayerError` on the looper.
 *
 * Derived from the D1 round 8 mutant M25 (the engine's `onPlayerError` no longer forwarded
 * `onFailed`), which survived the whole `testAndroidHostTest` suite: no test reached this path.
 */
@RunWith(AndroidJUnit4::class)
class Media3MidPlaybackFailureR8AdversarialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val failNow: AtomicBoolean = AtomicBoolean(false)
    private val ioFailure: IOException = IOException("the stream died")
    private val engine: Media3VideoEngine = newFailingEngine()
    private val listener: RecordingEngineListener = RecordingEngineListener()
    private var player: VideoPlayer? = null

    private fun newFailingEngine(): Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = Looper.getMainLooper(),
        playerFactory = { playerContext: Context, looper: Looper ->
            TestExoPlayerBuilder(playerContext)
                .setLooper(looper)
                .setRenderers(FailOnDemandRenderer(failNow, ioFailure))
                .build()
        },
        mediaSourceFactory = { _: Context, _: VideoSource -> fakeSource() },
    )

    @After
    fun tearDown() {
        player?.release()
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `the engine reports a mid playback failure once with the PlaybackException`() {
        engine.setListener(listener)
        runSuspending { engine.load(VideoSource.Remote("https://example.test/x.mp4")) }.getOrThrow()
        engine.start()
        failNow.set(true)
        runMainLooperUntil { listener.failures.isNotEmpty() }

        assertEquals(1, listener.failures.size, "exactly one failure: ${listener.failures}")
        val failure: Throwable = listener.failures.single()
        assertIs<PlaybackException>(failure)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, failure.errorCode)
        assertSame(ioFailure, failure.cause, "the platform's own cause, untouched")
        assertTrue(listener.completions.isEmpty(), "a failed playback is not a completion")
    }

    @Test
    fun `a mid playback failure ends the player in Error with the PlaybackException and stays there`() {
        val created: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
        player = created
        runSuspending { created.prepare(VideoSource.Remote("https://example.test/x.mp4")) }.getOrThrow()
        assertIs<VideoPlayerState.Ready>(created.stateFlow.value)
        created.play()
        failNow.set(true)
        runMainLooperUntil { created.stateFlow.value is VideoPlayerState.Error }

        val state: VideoPlayerState = created.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        val cause: Throwable = state.cause
        assertIs<PlaybackException>(cause)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, cause.errorCode)
        assertFalse(created.isBufferingFlow.value, "a failed source is not buffering")

        // Error is not playable: a transport call is ignored and the state stays.
        created.play()
        ShadowLooper.idleMainLooper()
        assertSame(state, created.stateFlow.value, "play() after a failure changes nothing")
    }
}

/**
 * A video renderer that behaves like Media3's fake one until [failNow] is set, then throws a
 * non-recoverable renderer error from `render`, the way a decoder that dies mid-stream does.
 */
private class FailOnDemandRenderer(
    private val failNow: AtomicBoolean,
    private val failure: IOException,
) : FakeRenderer(C.TRACK_TYPE_VIDEO) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (failNow.get()) {
            throw ExoPlaybackException.createForRenderer(
                failure,
                name,
                index,
                null,
                C.FORMAT_HANDLED,
                null,
                false,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        }
        super.render(positionUs, elapsedRealtimeUs)
    }
}
