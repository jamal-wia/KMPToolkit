package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ExperimentalComposeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.Recomposer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals

/**
 * A composition can be thrown away after it composed but before its changes were applied: a lazy
 * list drops a prefetched item that scrolled away, a composition is abandoned half-way. The effects
 * of such a composition never run and its `remember`ed values are only told through
 * `RememberObserver.onAbandoned`. A player the composition created must not be left unreleased.
 */
@OptIn(ExperimentalComposeApi::class, InternalComposeApi::class)
@RunWith(RobolectricTestRunner::class)
class AbandonedCompositionAdversarialTest {

    private class CountingEngine : VideoPlaybackEngine {
        // Read from the test thread while the dispose may run on the player's own: an atomic cell.
        val disposed: AtomicInteger = AtomicInteger(0)
        var attached: VideoPlaybackEngineListener? = null

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            attached = listener
        }

        override suspend fun load(source: VideoSource) = Unit
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = 1_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
        override fun dispose() {
            disposed.incrementAndGet()
        }
    }

    /**
     * The dispose count once the engine has been disposed, or `0` after a bounded wait. A release defers
     * the engine's disposal until a load still unwinding on the composition's own dispatcher (here
     * `Dispatchers.Default`, the bare recomposer has none) has finished, so it can land a moment after
     * `dispose()` returned: the guarantee is "released when it leaves", not "before dispose() returns".
     */
    private fun CountingEngine.awaitDisposed(): Int {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (disposed.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5L)
        // Let a wrong second dispose show up too.
        Thread.sleep(50L)
        return disposed.get()
    }

    private class NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun `a player created by a composition that is disposed before its changes were applied is released`() {
        val engines: MutableList<CountingEngine> = mutableListOf()
        val factory = VideoPlayerFactory { _ -> createVideoPlayer(CountingEngine().also { engines += it }) }
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(NoApplier(), recomposer)

        // Composed, never applied: what a prefetched lazy item that is dropped looks like.
        composition.composeContent {
            CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                rememberVideoPlayer(VideoSource.Remote("https://example.test/a.mp4"))
            }
        }
        assertEquals(1, engines.size, "the composition should have created its player")
        composition.dispose()
        recomposer.close()

        assertEquals(1, engines[0].awaitDisposed(), "the abandoned composition's player was never released")
    }

    @Test
    fun `control - the same composition applied and then disposed releases its player`() {
        val engines: MutableList<CountingEngine> = mutableListOf()
        val factory = VideoPlayerFactory { _ -> createVideoPlayer(CountingEngine().also { engines += it }) }
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = ControlledComposition(NoApplier(), recomposer)

        composition.composeContent {
            CompositionLocalProvider(LocalVideoPlayerFactory provides factory) {
                rememberVideoPlayer(VideoSource.Remote("https://example.test/a.mp4"))
            }
        }
        composition.applyChanges()
        composition.dispose()
        recomposer.close()

        assertEquals(1, engines.size)
        assertEquals(1, engines[0].awaitDisposed(), "an applied composition's player must be released when it leaves")
    }
}
