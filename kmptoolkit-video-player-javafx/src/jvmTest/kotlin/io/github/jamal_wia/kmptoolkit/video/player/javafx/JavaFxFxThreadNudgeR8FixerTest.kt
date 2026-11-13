package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import javafx.scene.media.MediaPlayer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 8, suspect S13 (the round 7 test `a speed changed after completion at another speed then replay, called from
 * the FX thread` stood at 0 in about 3 of 8 trials). The native rate changes seen from the FX thread: `replay()` on
 * the FX thread runs `seekTo(0)`, `start()` and `setSpeed(new)` in one task. `start()` runs inline and queues its
 * nudge; `setSpeed` then queues behind the nudge (the FX thread has something queued), so the native player got
 * the old speed 1.0 then 4.0 then 2.0 right after `play()`: three rate changes next to a play(), which freezes the
 * playhead on macOS. The nudge is now given two tasks later, so the speed replaces it and the native player gets
 * one away-and-back, to the new speed. The recorder is a listener on the native rate property.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxFxThreadNudgeR8FixerTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private fun nativePlayer(e: JavaFxVideoEngine): MediaPlayer {
        val activePlayback = JavaFxVideoEngine::class.java.getDeclaredMethod("activePlayback").apply { isAccessible = true }
        val pb: Any = checkNotNull(activePlayback.invoke(e)) { "no playback" }
        return (pb.javaClass.getDeclaredField("native").apply { isAccessible = true }.get(pb) as JavaFxNativePlayer).mediaPlayer
    }

    private fun onFxThread(block: () -> Unit) {
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        Platform.runLater {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        assertTrue(done.await(10L, TimeUnit.SECONDS), "the FX thread did not run the block")
        failure?.let { throw it }
    }

    private fun completedAt(speed: Float, listener: RecordingListener): JavaFxVideoEngine {
        val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }
        e.setListener(listener)
        runBlocking { withTimeout(20_000L) { e.load(VideoSource.Asset(TestClip.ASSET)) } }
        e.setSpeed(speed)
        e.start()
        awaitTrue(timeoutMs = 10_000L, message = { "completed at ${speed}x" }) { listener.events.any { it is EngineEvent.Completed } }
        return e
    }

    @Test
    fun `a replay called from the FX thread at a new speed gives the native player one away and back to the new speed`() {
        repeat(4) { trial: Int ->
            val listener = RecordingListener()
            val e: JavaFxVideoEngine = completedAt(4f, listener)
            val events: MutableList<Double> = CopyOnWriteArrayList()
            val player: MediaPlayer = nativePlayer(e)
            runBlocking { FxThread.call { player.rateProperty().addListener { _, _, now -> events += now.toDouble() } } }
            // What the shared player's replay() does after a speed was changed while completed.
            onFxThread {
                e.seekTo(0L)
                e.start()
                e.setSpeed(2f)
            }
            awaitTrue(timeoutMs = 5_000L, message = { "trial $trial: rate 2.0 given, events $events" }) { events.contains(2.0) }
            Thread.sleep(400L)
            assertEquals(listOf(1.0, 2.0), events.toList(), "trial $trial: away and back to the NEW speed, the replaced 4.0 never given")
            e.release()
            engine = null
        }
    }

    @Test
    fun `a speed given on the FX thread right after a resume of a paused player replaces the pending nudge`() {
        val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }
        runBlocking { withTimeout(20_000L) { e.load(VideoSource.Asset(TestClip.ASSET)) } }
        e.setSpeed(2f)
        runBlocking { withTimeout(5_000L) { e.inspectPlayback() } }
        val events: MutableList<Double> = CopyOnWriteArrayList()
        val player: MediaPlayer = nativePlayer(e)
        runBlocking { FxThread.call { player.rateProperty().addListener { _, _, now -> events += now.toDouble() } } }
        onFxThread {
            e.start()
            e.setSpeed(3f)
        }
        awaitTrue(timeoutMs = 5_000L, message = { "rate 3.0 given, events $events" }) { events.contains(3.0) }
        Thread.sleep(400L)
        assertEquals(listOf(1.0, 3.0), events.toList(), "away and back to the NEW speed, no change to the replaced one")
    }
}
