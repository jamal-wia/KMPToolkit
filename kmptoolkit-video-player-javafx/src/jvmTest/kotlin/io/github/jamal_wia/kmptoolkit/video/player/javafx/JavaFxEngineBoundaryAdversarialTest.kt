package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The engine boundary against the real toolkit: values the core would never hand over, and the
 * exit heartbeat during a load.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxEngineBoundaryAdversarialTest {

    private val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine.release()
        val done = CountDownLatch(1)
        Platform.runLater { done.countDown() }
        done.await(10L, TimeUnit.SECONDS)
    }

    @Test
    fun `a rate that is not finite and positive never reaches the JavaFX player`() = runBlocking<Unit> {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        val hostile: List<Float> = listOf(
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f,
        )
        val reached: MutableList<String> = mutableListOf()
        for (rate: Float in hostile) {
            engine.setSpeed(2f)
            withTimeout(5_000L) { engine.inspectPlayback() }
            engine.setSpeed(rate)
            val seen: Double = withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()).rate }
            if (seen != 2.0) reached += "setSpeed($rate) -> JavaFX rate $seen"
        }
        assertEquals(emptyList(), reached, "rates that reached the JavaFX player")
    }

    @Test
    fun `a volume that is NaN never reaches the JavaFX player`() = runBlocking<Unit> {
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        engine.setVolume(0.5f)
        engine.setVolume(Float.NaN)
        val seen: Double = withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()).volume }
        assertTrue(!seen.isNaN(), "JavaFX holds a NaN volume")
    }

    @Test
    fun `a NaN rate or volume set before the load is not applied by the load`() = runBlocking<Unit> {
        engine.setSpeed(Float.NaN)
        engine.setVolume(Float.NaN)
        withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        val seen: FxPlayback.Inspection = withTimeout(5_000L) { checkNotNull(engine.inspectPlayback()) }
        assertEquals(1.0, seen.rate, "the rate the load applied")
        assertEquals(1.0, seen.volume, "the volume the load applied")
    }

    private class QuietSink : FxPlayback.Sink {
        override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) = Unit
        override fun onSeekApplied() = Unit
        override fun onVideoSize(size: VideoSize?) = Unit
        override fun onBuffering(isBuffering: Boolean) = Unit
        override fun onCompleted() = Unit
        override fun onFailed(cause: Throwable) = Unit
        override fun onFrame(frame: VideoFrame) = Unit
    }

    @Test
    fun `a stall inside the engine's own construction is not silence even while an implicit exit is possible`() = runBlocking<Unit> {
        val real: () -> Boolean = FxThread.implicitExitPossible
        FxThread.implicitExitPossible = { true }
        try {
            stallDuringLoad()
        } finally {
            FxThread.implicitExitPossible = real
        }
    }

    @Test
    fun `with implicit exit off a silent FX thread is never taken for an exit`() {
        val dropped: MutableList<Runnable> = CopyOnWriteArrayList()
        val realRunLater: (Runnable) -> Unit = FxThread.runLater
        val realImplicit: () -> Boolean = FxThread.implicitExitPossible
        FxThread.runLater = { dropped += it }
        FxThread.implicitExitPossible = { false }
        try {
            val begun: Long = System.nanoTime()
            assertFalse(FxThread.hasExited(), "implicit exit is off: silence is a busy thread")
            val tookMs: Long = (System.nanoTime() - begun) / 1_000_000L
            assertTrue(tookMs < 200L, "no probe may be waited for, took $tookMs ms")
            Thread.sleep(2_200L)
            assertFalse(FxThread.hasExited(), "still not exited after the silence bound")
        } finally {
            FxThread.runLater = realRunLater
            FxThread.implicitExitPossible = realImplicit
            dropped.forEach { Platform.runLater(it) }
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            assertTrue(done.await(5L, TimeUnit.SECONDS), "the FX thread did not drain")
        }
    }

    @Test
    fun `a load whose FX thread stalls for 2_6 s in the middle of it still succeeds well inside its 15 s ready timeout`() = runBlocking<Unit> {
        stallDuringLoad()
    }

    private suspend fun stallDuringLoad() {
        val uri: String = TestClip.copyToTempFile().toURI().toString()
        val startedAt: Long = System.nanoTime()
        val playback: FxPlayback = withTimeout(20_000L) {
            FxPlayback.open(uri, QuietSink(), maxFrameRate = 30, readyTimeoutMs = 15_000L, onCreated = {
                // On the FX thread, right after the player exists and before JavaFX reported it ready.
                Thread.sleep(2_600L)
            })
        }
        try {
            val tookMs: Long = (System.nanoTime() - startedAt) / 1_000_000L
            assertTrue(tookMs < 15_000L, "took $tookMs ms")
        } finally {
            playback.dispose()
        }
    }
}
