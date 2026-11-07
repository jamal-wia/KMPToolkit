package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * Rotation while the composition-owned player is mid-operation: preparing, just ready and playing,
 * switching source, and cancelled while its load is still unwinding. Each activity is destroyed and
 * rebuilt at that moment, twenty times over. The engines are strict: they note every call after
 * dispose, a release or dispose under a running load, and two loads at once, and every engine must
 * end disposed exactly once with nothing still loading.
 */
@RunWith(RobolectricTestRunner::class)
class ActivityLifecycleChaosAdversarialTest {

    private class StrictEngine : VideoPlaybackEngine {
        val violations: MutableList<String> = mutableListOf()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()

        /** Held shut until the test lets a cancelled load finish unwinding, like a platform handle slow to free. */
        val unwind: CompletableDeferred<Unit> = CompletableDeferred()
        var active: Int = 0
        var disposeCount: Int = 0
        var loads: Int = 0
        var attached: VideoPlaybackEngineListener? = null
        private var disposed: Boolean = false

        private fun note(call: String) {
            if (disposed) violations += "$call after dispose"
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            attached = listener
        }

        override suspend fun load(source: VideoSource) {
            note("load")
            loads++
            if (++active > 1) violations += "two loads at once"
            try {
                gate.await()
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) { unwind.await() }
                throw cancellation
            } finally {
                active--
            }
        }

        override fun start() = note("start")
        override fun pause() = note("pause")
        override fun seekTo(positionMs: Long) = note("seekTo")
        override fun setSpeed(speed: Float) = note("setSpeed")
        override fun setVolume(volume: Float) = note("setVolume")
        override fun setLooping(looping: Boolean) = note("setLooping")
        override fun durationMs(): Long = 60_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L

        override fun release() {
            note("release")
            if (active > 0) violations += "release under a running load"
        }

        override fun dispose() {
            if (disposed) violations += "second dispose"
            if (active > 0) violations += "dispose under a running load"
            disposed = true
            disposeCount++
        }
    }

    private val engines: MutableList<StrictEngine> = mutableListOf()
    private val controllers: MutableList<ActivityController<ComponentActivity>> = mutableListOf()
    private val factory = VideoPlayerFactory { _ ->
        StrictEngine().also { engines += it }.let { engine -> createVideoPlayer(engine) }
    }

    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        pump(20)
    }

    private fun pump(frames: Int = 1) {
        repeat(frames) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PUMP_MS)) }
    }

    @Test
    fun `twenty rotations at preparing, ready, source switch and unwinding moments leave every engine disposed once and idle`() {
        val controller: ActivityController<ComponentActivity> = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controllers += controller
        var source: VideoSource? by mutableStateOf(SOURCE_A)

        repeat(CYCLES) { cycle: Int ->
            source = SOURCE_A
            controller.get().setContentView(
                ComposeView(controller.get()).apply {
                    setContent {
                        CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                            rememberVideoPlayer(source, autoPlay = true)
                        }
                    }
                },
            )
            pump(3)
            val engine: StrictEngine = engines[cycle]
            when (cycle % 4) {
                // Rotate while the load is still pending.
                0 -> assertEquals(1, engine.active, "cycle $cycle: the load should be pending")
                // Let the load finish, so the rotation hits a playing player.
                1 -> {
                    engine.gate.complete(Unit)
                    pump(3)
                }
                // Switch source and rotate in the same frame.
                2 -> source = SOURCE_B
                // Cancel the load (no source) and rotate while it is still unwinding.
                3 -> {
                    source = null
                    pump(2)
                }
            }
            controller.recreate()
            pump(2)
        }
        controller.pause().stop().destroy()
        pump(10)

        // A rotation that hit a cancelled load still unwinding: the engine is not freed from under it.
        engines.forEachIndexed { index: Int, engine: StrictEngine ->
            if (index % 4 == 3) {
                assertEquals(1, engine.active, "engine $index: the cancelled load should still be unwinding")
                assertEquals(0, engine.disposeCount, "engine $index: disposed under its unwinding load")
            }
        }
        engines.forEach { it.unwind.complete(Unit) }
        pump(10)

        assertEquals(CYCLES, engines.size, "one engine per composition")
        val summary: String = engines.mapIndexed { i: Int, e: StrictEngine -> "#$i(loads=${e.loads} active=${e.active} dispose=${e.disposeCount} listener=${e.attached != null} v=${e.violations})" }.joinToString(" ")
        assertTrue(engines.all { it.active == 0 && it.disposeCount == 1 && it.violations.isEmpty() }, summary)
        engines.forEachIndexed { index: Int, engine: StrictEngine ->
            assertEquals(emptyList(), engine.violations, "engine $index")
            assertEquals(0, engine.active, "engine $index: a load is still running")
            assertEquals(1, engine.disposeCount, "engine $index: dispose count")
            assertTrue(engine.attached == null, "engine $index: still has a listener")
        }
    }

    private companion object {
        const val CYCLES: Int = 20
        const val PUMP_MS: Long = 32L
        val SOURCE_A: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
        val SOURCE_B: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    }
}
