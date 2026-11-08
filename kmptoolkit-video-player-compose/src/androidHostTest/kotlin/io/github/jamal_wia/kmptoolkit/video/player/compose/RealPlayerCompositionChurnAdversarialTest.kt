package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real Media3 player and surface owned by a composition that is swapped over and over, with the
 * swap landing at every phase of the load (not started, in flight, failed). Every player the
 * composition created must have released its ExoPlayer: no playback thread may outlive the screen.
 */
@RunWith(RobolectricTestRunner::class)
class RealPlayerCompositionChurnAdversarialTest {

    private var generation: Int by mutableIntStateOf(0)
    private val controllers: MutableList<ActivityController<ComponentActivity>> = mutableListOf()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        pump(3)
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    private fun playbackThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("ExoPlayer:Playback") }

    @Composable
    private fun Screen() {
        key(generation) {
            // No controls: their buffering spinner is an endless animation a paused Robolectric looper cannot idle.
            VideoPlayer(source = VideoSource.File("/nonexistent/clip-$generation.mp4"), autoPlay = true, controls = {})
        }
    }

    @Test
    fun `swapping a composition that owns a real player forty times leaves no playback thread behind`() {
        val baseline: Int = playbackThreads()
        val controller: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controllers += controller
        controller.get().setContentView(ComposeView(controller.get()).apply { setContent { Screen() } })
        pump(3)
        val random = Random(7)
        var peak: Int = baseline
        repeat(SWAPS) {
            generation++
            repeat(random.nextInt(3)) { pump(1) }
            if (random.nextInt(4) == 0) Thread.sleep(random.nextLong(1L, 4L))
            peak = maxOf(peak, playbackThreads())
        }
        assertTrue(peak > baseline, "the churn never created an ExoPlayer, so it proves nothing (peak $peak, baseline $baseline)")
        pump(3)
        // Leave the screen: the last player goes too.
        controller.get().setContentView(android.view.View(controller.get()))
        pump(5)
        controller.pause().stop().destroy()
        pump(3)

        val deadline: Long = System.nanoTime() + 15_000_000_000L
        while (playbackThreads() > baseline && System.nanoTime() < deadline) {
            pump(1)
            Thread.sleep(5L)
        }
        val left: Int = playbackThreads()
        if (left > baseline) fail("$left ExoPlayer playback threads are alive after the churn, $baseline before it")
        assertTrue(left <= baseline)
    }

    private companion object {
        const val SWAPS: Int = 40
    }
}
