package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A Throwable a media source factory throws reaches the caller as the very instance it threw, also when
 * kotlinx.coroutines stack-trace recovery is on (it is, whenever assertions are enabled, as in JUnit):
 * [VideoPlayerState.Error] promises the platform's own Throwable (docs 04-api-reference and 03-guide).
 */
@RunWith(AndroidJUnit4::class)
class Media3ErrorIdentityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cause: IllegalStateException = IllegalStateException("the source would not build")
    private val engine: Media3VideoEngine = newEngine(context, mediaSources = { _: Context, _: VideoSource -> throw cause })
    private val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)

    @After
    fun tearDown() {
        player.release()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a factory failure arrives as the very Throwable it threw`() {
        runSuspending { player.prepare(VideoSource.Remote("https://example.test/x.mp4")) }.getOrThrow()
        val state: VideoPlayerState = player.stateFlow.value
        assertIs<VideoPlayerState.Error>(state)
        assertSame(cause, state.cause, "got ${state.cause} with cause ${state.cause.cause}, assertions enabled: ${javaClass.desiredAssertionStatus()}")
    }

    @Test
    fun `the engine load throws the very Throwable the factory threw`() {
        val outcome: Result<Unit> = runSuspending { engine.load(VideoSource.Remote("https://example.test/x.mp4")) }
        val thrown: Throwable = assertFailsWith<IllegalStateException> { outcome.getOrThrow() }
        assertSame(cause, thrown, "got $thrown with cause ${thrown.cause}, assertions enabled: ${javaClass.desiredAssertionStatus()}")
    }
}
