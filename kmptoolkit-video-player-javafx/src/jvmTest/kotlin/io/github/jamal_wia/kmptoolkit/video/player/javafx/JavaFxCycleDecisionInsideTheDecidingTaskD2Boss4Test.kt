package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 4, mutant Q1: `NativeEvents.onCycleDecision` must be delivered INSIDE the FX task in which JavaFX decides what the end of a
 * cycle means (`handleFinish`), before it reads `cycleCount`, not in a later task. `FxPlayback` takes its snapshot of the looping
 * setting there; a delivery one task later (for example a `Platform.runLater` in the adapter's listener) would let a `setLooping`
 * queued from another thread between the two tasks into the snapshot, and the boss-3 finding R1 (a player standing at the end as
 * Playing) would come back. C13 of the conformance class checks the order against `onEndOfMedia` and the thread only, which a delivery
 * one task later also satisfies.
 *
 * The witness: at a plain end JavaFX sets `currentRate` to 0 in that same task, after the decision. So in the decision callback of a
 * playing clip `currentRate` still reads the rate it played at; one task later it reads 0.
 */
class JavaFxCycleDecisionInsideTheDecidingTaskD2Boss4Test {

    private class Decision(val currentRate: Double, val endsBefore: Int)

    private class Recorder(val player: () -> JavaFxNativePlayer) : NativeEvents {
        val ready: CompletableFuture<Unit> = CompletableFuture()
        val ended: CompletableFuture<Unit> = CompletableFuture()
        val decisions: MutableList<Decision> = CopyOnWriteArrayList()
        val offFx: MutableList<String> = CopyOnWriteArrayList()
        private var ends: Int = 0

        override fun onReady() {
            ready.complete(Unit)
        }

        override fun onPlayerError() = Unit
        override fun onMediaError() = Unit

        override fun onCycleDecision() {
            if (!Platform.isFxApplicationThread()) offFx += "onCycleDecision"
            decisions += Decision(player().mediaPlayer.currentRate, ends)
        }

        override fun onEndOfMedia() {
            ends++
            ended.complete(Unit)
        }

        override fun onStatus(status: NativeStatus) = Unit
        override fun onCurrentTime() = Unit
        override fun onBufferProgress() = Unit
        override fun onDuration() = Unit
        override fun onSize() = Unit
        override fun onPulse(nowNanos: Long) = Unit
    }

    private var native: JavaFxNativePlayer? = null
    private var clip: File? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
    }

    @AfterTest
    fun tearDown() {
        native?.let { player: JavaFxNativePlayer -> fx { player.dispose() } }
        clip?.delete()
    }

    @Test
    fun `onCycleDecision runs inside JavaFX's deciding task at a plain end`() {
        val file: File = TestClip.copyToTempFile().also { clip = it }
        val source: NativeSource = JavaFxBackend.probe(file.toURI().toString())
        lateinit var created: JavaFxNativePlayer
        val recorder = Recorder { created }
        fx {
            created = JavaFxBackend.create(source) as JavaFxNativePlayer
            native = created
            created.setEvents(recorder)
        }
        recorder.ready.get(BOUND_MS, TimeUnit.MILLISECONDS)
        fx {
            created.seek(2_300.0)
            created.play()
        }
        recorder.ended.get(BOUND_MS, TimeUnit.MILLISECONDS)
        val decisions: List<Decision> = recorder.decisions.toList()
        assertEquals(1, decisions.size, "one decision for one end of a plain player: ${decisions.map { it.currentRate to it.endsBefore }}")
        assertEquals(0, decisions.single().endsBefore, "the decision came after the end of media")
        assertTrue(
            decisions.single().currentRate > 0.0,
            "currentRate read 0 in the decision callback: JavaFX had already acted on its decision, so the callback ran in a later task than the decision",
        )
        assertEquals(emptyList(), recorder.offFx.toList(), "callbacks that ran off the FX thread")
    }

    /** Runs [block] on the FX thread; fails with the block's own exception or at the hang bound. */
    private fun <T> fx(block: () -> T): T {
        val result = CompletableFuture<T>()
        Platform.runLater {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result.get(BOUND_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val BOUND_MS: Long = 10_000L
    }
}
