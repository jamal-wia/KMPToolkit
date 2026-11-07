package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decides the flake the round-3 fixer saw in the control case of the abandoned-composition test: the
 * composition's LaunchedEffect runs the prepare on Dispatchers.Default (the bare Recomposer has no
 * dispatcher), so when the composition is disposed the load may still be unwinding and the engine is
 * freed a moment after dispose() returned. The guarantee is that the player is released, exactly once,
 * and that nothing reaches the engine after its dispose; not that it happened before dispose() returned.
 */
@OptIn(ExperimentalComposeApi::class, InternalComposeApi::class)
@RunWith(RobolectricTestRunner::class)
class OwnedPlayerReleaseTimingAdversarialTest {

    private class OrderedEngine : VideoPlaybackEngine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val disposed: AtomicInteger = AtomicInteger(0)
        val afterDispose: AtomicInteger = AtomicInteger(0)
        val loaded: CountDownLatch = CountDownLatch(1)

        private fun note(call: String) {
            if (disposed.get() > 0) afterDispose.incrementAndGet()
            calls += call
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) = note("setListener")
        override suspend fun load(source: VideoSource) {
            note("load")
            loaded.countDown()
        }
        override fun start() = note("start")
        override fun pause() = note("pause")
        override fun seekTo(positionMs: Long) = note("seekTo")
        override fun setSpeed(speed: Float) = note("setSpeed")
        override fun setVolume(volume: Float) = note("setVolume")
        override fun setLooping(looping: Boolean) = note("setLooping")
        override fun durationMs(): Long = 1_000L
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = note("release")
        override fun dispose() {
            note("dispose")
            disposed.incrementAndGet()
        }
    }

    private class NoApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    @Test
    fun `an applied then disposed composition releases its player exactly once with nothing after dispose`() {
        val engines: MutableList<OrderedEngine> = Collections.synchronizedList(mutableListOf())
        val factory = VideoPlayerFactory { _ -> createVideoPlayer(OrderedEngine().also { engines += it }) }
        var synchronousZero = 0
        repeat(ROUNDS) {
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
            if (engines.last().disposed.get() == 0) synchronousZero++
        }
        // Eventually every engine is disposed exactly once; nothing was called after its dispose.
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && engines.any { it.disposed.get() == 0 }) Thread.sleep(5L)
        assertEquals(ROUNDS, engines.size)
        assertTrue(engines.all { it.disposed.get() == 1 }, "dispose counts: ${engines.map { it.disposed.get() }}")
        val after: Int = engines.sumOf { it.afterDispose.get() }
        assertEquals(0, after, "engine called after dispose: ${engines.firstOrNull { it.afterDispose.get() > 0 }?.calls}")
        println("rounds whose dispose had not happened yet when composition.dispose() returned: $synchronousZero of $ROUNDS")
    }

    private companion object {
        const val ROUNDS: Int = 150
    }
}
