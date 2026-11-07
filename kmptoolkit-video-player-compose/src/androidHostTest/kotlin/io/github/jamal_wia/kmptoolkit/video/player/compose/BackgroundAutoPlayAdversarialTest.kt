package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
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
 * The host goes to the background while the source is still loading, and the load finishes there.
 * The module's documents say the video pauses when the app goes to the background and that
 * background playback is out of scope; a video that begins to play (with sound) after the app was
 * backgrounded breaks both. The stop event has already come and gone, so the pause-on-background
 * observer alone cannot catch it.
 */
@RunWith(RobolectricTestRunner::class)
class BackgroundAutoPlayAdversarialTest {

    private class GatedEngine : VideoPlaybackEngine {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        var starts: Int = 0

        override fun setListener(listener: VideoPlaybackEngineListener?) = Unit
        override suspend fun load(source: VideoSource) = gate.await()
        override fun start() {
            starts++
        }

        override fun pause() = Unit
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
    private val players: MutableList<io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer> = mutableListOf()
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

    private fun showAutoPlayingVideo(): ActivityController<ComponentActivity> {
        val controller: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controllers += controller
        controller.get().setContentView(
            ComposeView(controller.get()).apply {
                setContent {
                    CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                        // No controls: their buffering spinner is an endless animation that a paused Robolectric looper cannot idle.
                        VideoPlayer(source = SOURCE, autoPlay = true, controls = {})
                    }
                }
            },
        )
        pump(3)
        assertEquals(1, engines.size)
        assertEquals(VideoPlayerState.Preparing, players[0].stateFlow.value, "the load should be pending")
        return controller
    }

    @Test
    fun `control - an auto-playing video that finishes loading in the foreground plays`() {
        showAutoPlayingVideo()
        engines[0].gate.complete(Unit)
        pump(3)
        assertTrue(players[0].stateFlow.value is VideoPlayerState.Playing, "state ${players[0].stateFlow.value}")
    }

    @Test
    fun `an auto-playing video whose load finishes after the app was backgrounded does not start playing`() {
        val controller: ActivityController<ComponentActivity> = showAutoPlayingVideo()
        controller.pause().stop()
        pump(2)

        engines[0].gate.complete(Unit)
        pump(3)

        assertEquals(0, engines[0].starts, "the engine was started while the app is in the background")
        assertTrue(
            players[0].stateFlow.value !is VideoPlayerState.Playing,
            "the video plays in the background: ${players[0].stateFlow.value}",
        )
    }

    private companion object {
        val SOURCE: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    }
}
