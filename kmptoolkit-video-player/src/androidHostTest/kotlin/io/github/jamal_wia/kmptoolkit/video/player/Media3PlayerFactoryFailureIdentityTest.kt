package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The ExoPlayer factory itself throws (player infrastructure, not the media source): `VideoPlayerState.Error` still
 * carries that very Throwable, not a stack-trace-recovered copy (promoted from the D1 round 7 suspect r7-NIT1).
 */
@RunWith(AndroidJUnit4::class)
class Media3PlayerFactoryFailureIdentityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cause: IllegalStateException = IllegalStateException("the player would not build")

    @Test
    fun `a failing player factory arrives as the very Throwable it threw`() {
        val engine = Media3VideoEngine(
            context = context,
            looper = Looper.getMainLooper(),
            playerFactory = { _: Context, _: Looper -> throw cause },
            mediaSourceFactory = { _: Context, _: VideoSource -> throw AssertionError("not reached") },
        )
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
        runSuspending { player.prepare(VideoSource.Remote("https://example.test/x.mp4")) }.getOrThrow()
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(cause, state.cause, "got ${state.cause} with cause ${state.cause.cause}, assertions: ${javaClass.desiredAssertionStatus()}")
        player.release()
        ShadowLooper.idleMainLooper()
    }
}
