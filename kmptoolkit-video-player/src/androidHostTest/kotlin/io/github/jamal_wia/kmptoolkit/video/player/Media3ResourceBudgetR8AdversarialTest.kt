package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1 round 8, Q4: the resource budget of the real Media3 engine under Robolectric (the r7 round ran it only
 * on the fake engine). 200 prepare/play/release/dispose cycles and 1000 source swaps on one engine: one
 * ExoPlayer per engine and only the one the swaps share, every ExoPlayer released, no playback thread left
 * behind, nothing left queued on the main looper, and no listener call after the engine let go.
 */
@RunWith(AndroidJUnit4::class)
class Media3ResourceBudgetR8AdversarialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/x.mp4")

    private fun exoPlayerThreads(): Int = Thread.getAllStackTraces().keys.count { thread: Thread ->
        thread.isAlive && thread.name.startsWith("ExoPlayer")
    }

    @Test
    fun `two hundred engines each loaded played released and disposed leave no ExoPlayer and no thread behind`() {
        val created: MutableList<ExoPlayer> = mutableListOf()
        val threadsBefore: Int = exoPlayerThreads()
        repeat(CYCLES) { cycle: Int ->
            val engine: Media3VideoEngine = newEngine(context, created = { player: ExoPlayer -> created += player })
            val listener = RecordingEngineListener()
            engine.setListener(listener)
            runSuspending { engine.load(source) }.getOrThrow()
            engine.start()
            ShadowLooper.idleMainLooper()
            engine.release()
            engine.release()
            engine.dispose()
            engine.dispose()
            ShadowLooper.idleMainLooper()

            assertEquals(cycle + 1, created.size, "exactly one ExoPlayer per engine, cycle $cycle")
            assertTrue(created[cycle].isReleased, "the ExoPlayer of cycle $cycle was never released")
            assertTrue(listener.failures.isEmpty(), "cycle $cycle failures ${listener.failures}")
            assertTrue(listener.completions.isEmpty(), "cycle $cycle completions ${listener.completions}")
        }
        assertTrue(created.all { player: ExoPlayer -> player.isReleased })
        assertTrue(
            exoPlayerThreads() <= threadsBefore + THREAD_SLACK,
            "ExoPlayer threads grew from $threadsBefore to ${exoPlayerThreads()} over $CYCLES cycles",
        )
        assertTrue(shadowOf(Looper.getMainLooper()).isIdle, "work is still queued on the main looper")
    }

    @Test
    fun `a thousand source swaps on one engine use one ExoPlayer and end with the last source loaded`() {
        val created: MutableList<ExoPlayer> = mutableListOf()
        val engine: Media3VideoEngine = newEngine(context, created = { player: ExoPlayer -> created += player })
        val listener = RecordingEngineListener()
        engine.setListener(listener)
        val threadsBefore: Int = exoPlayerThreads()
        try {
            repeat(SWAPS) { swap: Int ->
                runSuspending { engine.load(source) }.getOrThrow()
                if (swap % 100 == 0) {
                    engine.start()
                    engine.pause()
                }
            }
            ShadowLooper.idleMainLooper()

            assertEquals(1, created.size, "the swaps must share one ExoPlayer")
            assertFalse(created.single().isReleased, "a swap released the shared ExoPlayer")
            assertEquals(Media3TestSupport.SOURCE_DURATION_MS, engine.durationMs())
            assertTrue(listener.failures.isEmpty(), "failures ${listener.failures}")
            assertTrue(exoPlayerThreads() <= threadsBefore + THREAD_SLACK, "threads grew over $SWAPS swaps")
            assertTrue(shadowOf(Looper.getMainLooper()).isIdle, "work is still queued on the main looper")
        } finally {
            engine.release()
            engine.dispose()
            ShadowLooper.idleMainLooper()
        }
        assertTrue(created.single().isReleased)
    }

    private companion object {
        const val CYCLES: Int = 200
        const val SWAPS: Int = 1_000
        const val THREAD_SLACK: Int = 2
    }
}
