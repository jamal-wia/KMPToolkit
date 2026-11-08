package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** An app whose coroutines run on the JavaFX application thread itself (what `Dispatchers.JavaFx` does). */
@OptIn(ToolkitInternalApi::class)
class JavaFxHostThreadAdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private object FxDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Platform.runLater(block)
        }
    }

    @Test
    fun `a prepare, play, seek and release driven from the FX thread keep that thread responsive and work`() {
        val player = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 15_000L), VideoPlayerConfig(positionUpdateIntervalMs = 20L), Dispatchers.Default)
        val worstGapMs = AtomicLong(0L)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val lastTick = AtomicLong(System.nanoTime())
        fun tick() {
            val now: Long = System.nanoTime()
            worstGapMs.accumulateAndGet((now - lastTick.getAndSet(now)) / 1_000_000L, ::maxOf)
            if (running.get()) Platform.runLater { tick() }
        }
        Platform.runLater { tick() }
        val outcome = CompletableDeferred<Result<VideoPlayerState>>()
        val availableOnFx = AtomicReference<Boolean?>(null)
        val availabilityMs = AtomicLong(-1L)
        CoroutineScope(FxDispatcher).launch {
            outcome.complete(
                runCatching {
                    withTimeout(15_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
                    val started: Long = System.nanoTime()
                    availableOnFx.set(isJavaFxMediaAvailable())
                    availabilityMs.set((System.nanoTime() - started) / 1_000_000L)
                    player.play()
                    player.seekTo(1_500L)
                    player.pause()
                    player.stateFlow.value
                },
            )
        }
        val result: Result<VideoPlayerState> = runBlocking { withTimeout(20_000L) { outcome.await() } }
        try {
            assertTrue(result.isSuccess, "the FX-thread prepare failed: ${result.exceptionOrNull()}")
            assertTrue(result.getOrThrow() is VideoPlayerState.Paused, "state ${result.getOrThrow()}")
            assertEquals(true, availableOnFx.get())
            assertTrue(availabilityMs.get() in 0L..300L, "isJavaFxMediaAvailable() on the FX thread took ${availabilityMs.get()} ms")
            assertTrue(worstGapMs.get() < 1_000L, "the FX thread was blocked for ${worstGapMs.get()} ms")
        } finally {
            running.set(false)
            player.release()
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            done.await(10L, TimeUnit.SECONDS)
        }
    }
}
