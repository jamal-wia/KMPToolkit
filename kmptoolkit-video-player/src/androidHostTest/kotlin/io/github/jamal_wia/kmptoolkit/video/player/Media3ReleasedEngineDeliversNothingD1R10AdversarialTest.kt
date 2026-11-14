package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 10 (breaker ij), Q3: the invariant behind `Media3ResourceBudgetR8AdversarialTest`, without its timing
 * assumption. The budget test asserts that no completion reaches the listener at all in a load, start, release
 * cycle, but the test ExoPlayer's clock advances by itself, so whether the five-second fake source reaches its end
 * during the first pass over the main looper (while the engine is still live, where a completion is correct) depends
 * on how the playback thread and the test thread are scheduled. What the engine owes is narrower and does not depend
 * on timing: once `release()` has returned, nothing more reaches the listener. This test counts what arrived before
 * the release and asserts that the count never moves afterwards, over 200 engines.
 */
@RunWith(AndroidJUnit4::class)
class Media3ReleasedEngineDeliversNothingD1R10AdversarialTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Remote("https://example.test/x.mp4")

    @Test
    fun `nothing reaches the listener after release returned over two hundred engines whatever the clock did`() {
        var endedBeforeRelease = 0
        repeat(CYCLES) { cycle: Int ->
            val created: MutableList<ExoPlayer> = mutableListOf()
            val engine: Media3VideoEngine = newEngine(context, created = { player: ExoPlayer -> created += player })
            val listener = RecordingEngineListener()
            engine.setListener(listener)
            runSuspending { engine.load(source) }.getOrThrow()
            engine.start()
            ShadowLooper.idleMainLooper()
            val completions: Int = listener.completions.size
            val failures: Int = listener.failures.size
            val buffering: Int = listener.buffering.size
            val sizes: Int = listener.sizes.size
            if (completions > 0) endedBeforeRelease++
            engine.release()
            ShadowLooper.idleMainLooper()
            assertEquals(completions, listener.completions.size, "cycle $cycle: a completion reached the listener after release")
            assertEquals(failures, listener.failures.size, "cycle $cycle: a failure reached the listener after release")
            assertEquals(buffering, listener.buffering.size, "cycle $cycle: a buffering change reached the listener after release")
            assertEquals(sizes, listener.sizes.size, "cycle $cycle: a picture size reached the listener after release")
            engine.dispose()
            ShadowLooper.idleMainLooper()
            assertEquals(completions, listener.completions.size, "cycle $cycle: a completion reached the listener after dispose")
            assertTrue(created.single().isReleased, "cycle $cycle: the ExoPlayer was never released")
        }
        println("ENDED_BEFORE_RELEASE $endedBeforeRelease of $CYCLES")
    }

    private companion object {
        const val CYCLES: Int = 200
    }
}
