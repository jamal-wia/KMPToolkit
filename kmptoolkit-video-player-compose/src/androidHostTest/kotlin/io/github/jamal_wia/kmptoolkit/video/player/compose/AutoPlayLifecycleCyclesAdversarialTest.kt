package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host cycles through stop and start while an auto-playing source loads, and the load finishes
 * at a different point of the cycle each time. The rule: the video starts only when the load
 * finishes while the host is started, exactly once, and the pause-on-background rule then applies to
 * it as to any playing video.
 */
@RunWith(RobolectricTestRunner::class)
class AutoPlayLifecycleCyclesAdversarialTest {

    private class GatedEngine : VideoPlaybackEngine {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        var starts: Int = 0
        var pauses: Int = 0

        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) = gate.await()
        override fun start() {
            starts++
        }

        override fun pause() {
            pauses++
        }

        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 60_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
    }

    private val engines: MutableList<GatedEngine> = mutableListOf()
    private val players: MutableList<VideoPlayer> = mutableListOf()
    private val factory = VideoPlayerFactory { _ ->
        createVideoPlayer(GatedEngine().also { engines += it }).also { players += it }
    }
    private val controllers: MutableList<ActivityController<ComponentActivity>> = mutableListOf()

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        pump(3)
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32L)) }
    }

    private fun show(autoPlay: Boolean = true, pauseOnBackground: Boolean = true): ActivityController<ComponentActivity> {
        val controller: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controllers += controller
        controller.get().setContentView(
            ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                        // No controls: their buffering spinner is an endless animation a paused Robolectric looper cannot idle.
                        VideoPlayer(source = SOURCE, autoPlay = autoPlay, pauseOnBackground = pauseOnBackground, controls = {})
                    }
                }
            },
        )
        pump(3)
        assertEquals(VideoPlayerState.Preparing, players[0].stateFlow.value, "the load should be pending")
        return controller
    }

    private fun background(controller: ActivityController<ComponentActivity>) {
        controller.pause().stop()
        pump(2)
    }

    private fun foreground(controller: ActivityController<ComponentActivity>) {
        controller.restart().start().resume()
        pump(2)
    }

    @Test
    fun `a host that went to the background and came back before the load finished still autoplays, once`() {
        val controller: ActivityController<ComponentActivity> = show()
        repeat(CYCLES) {
            background(controller)
            foreground(controller)
        }
        engines[0].gate.complete(Unit)
        pump(3)
        assertTrue(players[0].stateFlow.value is VideoPlayerState.Playing, "state ${players[0].stateFlow.value}")
        assertEquals(1, engines[0].starts, "the engine should start exactly once")
    }

    @Test
    fun `a load that finishes in the background after many cycles leaves the player Ready and it stays so on return`() {
        val controller: ActivityController<ComponentActivity> = show()
        repeat(CYCLES) {
            background(controller)
            foreground(controller)
        }
        background(controller)
        engines[0].gate.complete(Unit)
        pump(3)
        assertEquals(0, engines[0].starts, "started in the background")
        foreground(controller)
        assertEquals(0, engines[0].starts, "the video started on return, which the documentation rules out")
        assertTrue(players[0].stateFlow.value is VideoPlayerState.Ready, "state ${players[0].stateFlow.value}")
    }

    @Test
    fun `with pauseOnBackground off a load that finishes in the background still autoplays and nothing pauses it`() {
        val controller: ActivityController<ComponentActivity> = show(pauseOnBackground = false)
        background(controller)
        engines[0].gate.complete(Unit)
        pump(3)
        assertEquals(1, engines[0].starts, "autoplay with pauseOnBackground = false should start in the background")
        assertEquals(0, engines[0].pauses, "nothing should pause a player that opted out of pause-on-background")
    }

    @Test
    fun `a video playing after the load is paused once per stop and not resumed by any start`() {
        val controller: ActivityController<ComponentActivity> = show()
        engines[0].gate.complete(Unit)
        pump(3)
        assertTrue(players[0].stateFlow.value is VideoPlayerState.Playing)
        repeat(CYCLES) {
            background(controller)
            foreground(controller)
        }
        assertEquals(1, engines[0].starts, "a start event resumed the video")
        assertTrue(players[0].stateFlow.value is VideoPlayerState.Paused, "state ${players[0].stateFlow.value}")
        assertEquals(1, engines[0].pauses, "only the first stop finds the video playing")
    }

    private companion object {
        const val CYCLES: Int = 10
        val SOURCE: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    }
}
