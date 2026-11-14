package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.fakeSource
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `Media3VideoEngine.load` when the platform's own cleanup misbehaves: a first reset that throws reaches the caller as
 * that very Throwable and leaves the engine usable; a cleanup that throws after a failed load rides along as
 * suppressed and never replaces the failure, not even when it throws the failure itself.
 *
 * The player is Media3's test player behind a proxy that makes the chosen calls of `stop()` throw: `load` resets the
 * player once before the source is set and once more as cleanup when the load fails.
 */
@RunWith(AndroidJUnit4::class)
class Media3LoadCleanupFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/x.mp4")

    /** What the n-th call of `stop()` throws (1-based); a call with no entry passes through. */
    private val stopThrows: MutableMap<Int, Throwable> = mutableMapOf()
    private var stopCalls: Int = 0

    private var sourceFailure: Throwable? = null

    private val engine: Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = Looper.getMainLooper(),
        playerFactory = { playerContext: Context, looper: Looper ->
            val real: ExoPlayer = TestExoPlayerBuilder(playerContext).setLooper(looper).build()
            Proxy.newProxyInstance(
                ExoPlayer::class.java.classLoader,
                arrayOf(ExoPlayer::class.java),
            ) { _, method, args ->
                if (method.name == "stop" && method.parameterCount == 0) {
                    stopCalls += 1
                    stopThrows[stopCalls]?.let { throw it }
                }
                try {
                    method.invoke(real, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as ExoPlayer
        },
        mediaSourceFactory = { _: Context, _: VideoSource -> sourceFailure?.let { throw it } ?: fakeSource() },
    )

    private fun gotMessage(thrown: Throwable): String =
        "got $thrown with cause ${thrown.cause}, assertions: ${javaClass.desiredAssertionStatus()}"

    @After
    fun tearDown() {
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a first reset that throws arrives as the very Throwable and the next load still works`() {
        val firstReset = IllegalStateException("the first reset failed")
        stopThrows[1] = firstReset
        val thrown: Throwable = assertFailsWith<IllegalStateException> {
            runSuspending { engine.load(source) }.getOrThrow()
        }
        assertSame(firstReset, thrown, gotMessage(thrown))
        assertEquals(emptyList(), thrown.suppressed.toList(), "nothing was suppressed onto it")

        runSuspending { engine.load(source) }.getOrThrow()
        assertEquals(2, stopCalls, "the second load reset the player once: it was left assigned and usable")
    }

    @Test
    fun `a cleanup that throws after a failed load rides along as suppressed on the very failure`() {
        val failure = IllegalStateException("the source would not build")
        val cleanup = IllegalStateException("the cleanup failed too")
        sourceFailure = failure
        stopThrows[2] = cleanup
        val thrown: Throwable = assertFailsWith<IllegalStateException> {
            runSuspending { engine.load(source) }.getOrThrow()
        }
        assertSame(failure, thrown, gotMessage(thrown))
        assertEquals(listOf<Throwable>(cleanup), thrown.suppressed.toList(), "the cleanup rides along as suppressed")
    }

    @Test
    fun `a cleanup that throws the failure itself does not replace it`() {
        val failure = IllegalStateException("one instance for both")
        sourceFailure = failure
        stopThrows[2] = failure
        val thrown: Throwable = assertFailsWith<IllegalStateException> {
            runSuspending { engine.load(source) }.getOrThrow()
        }
        assertSame(failure, thrown, gotMessage(thrown))
        assertTrue(
            thrown.suppressed.isEmpty(),
            "an exception is never suppressed onto itself: ${thrown.suppressed.toList()}",
        )
    }
}
