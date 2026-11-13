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
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 8, the mutation survivors of rounds 5 to 7 (J3, J8, J9, J11, J13, J14, J15, J16, J17, J18, J6) judged
 * "not observable" because the engine's native calls could not be seen. They can: the native rate is the
 * MediaPlayer's rate property, and its change events are the native rate changes. A recorder on that property
 * (installed by reflection on the FX thread) shows HOW and WHEN the engine gives a speed to the native player:
 * once and away-and-back at a resume, not at all when paused again at once, after the playhead has moved at a
 * loop wrap, a while after a seek, and at once when nothing is deferred.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxRateGivingR8AdversarialTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private class RateEvent(val atNanos: Long, val rate: Double, val positionMs: Double)

    private fun loaded(listener: RecordingListener? = null): JavaFxVideoEngine {
        val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }
        listener?.let { e.setListener(it) }
        runBlocking { withTimeout(20_000L) { e.load(VideoSource.Asset(TestClip.ASSET)) } }
        return e
    }

    private fun playback(e: JavaFxVideoEngine): Any {
        val m = JavaFxVideoEngine::class.java.getDeclaredMethod("activePlayback").apply { isAccessible = true }
        return checkNotNull(m.invoke(e)) { "no playback" }
    }

    private fun nativePlayer(e: JavaFxVideoEngine): MediaPlayer {
        val pb: Any = playback(e)
        val f = pb.javaClass.getDeclaredField("native").apply { isAccessible = true }
        return (f.get(pb) as JavaFxNativePlayer).mediaPlayer
    }

    private fun longField(e: JavaFxVideoEngine, name: String): Long {
        val pb: Any = playback(e)
        return pb.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(pb)
    }

    private fun record(e: JavaFxVideoEngine): MutableList<RateEvent> {
        val events: MutableList<RateEvent> = CopyOnWriteArrayList()
        val player: MediaPlayer = nativePlayer(e)
        runBlocking { FxThread.call { player.rateProperty().addListener { _, _, now -> events += RateEvent(System.nanoTime(), now.toDouble(), player.currentTime.toMillis()) } } }
        return events
    }

    private fun flushFx(e: JavaFxVideoEngine) {
        runBlocking { withTimeout(5_000L) { e.inspectPlayback() } }
    }

    /** Runs [block] with the FX thread held, so everything the test thread posts meanwhile queues in order. */
    private fun withFxHeld(block: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Platform.runLater { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { block() } finally { release.countDown() }
    }

    private fun sleep(ms: Long) = Thread.sleep(ms)

    private fun await(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (!cond()) {
            if (System.nanoTime() > end) fail("timed out after $timeoutMs ms: $what")
            sleep(5L)
        }
    }

    @Test
    fun `a speed given right after a resume replaces the pending nudge and reaches the native player once`() {
        val e: JavaFxVideoEngine = loaded()
        e.setSpeed(2f)
        flushFx(e)
        val events = record(e)
        withFxHeld { e.start(); e.setSpeed(3f) }
        await(5_000L, "rate 3.0 given") { events.any { it.rate == 3.0 } }
        sleep(400L)
        assertEquals(listOf(1.0, 3.0), events.map { it.rate }, "away and back to the NEW speed, no change to the replaced one")
    }

    @Test
    fun `a resume that is paused again at once leaves the native rate alone`() {
        val e: JavaFxVideoEngine = loaded()
        e.setSpeed(2f)
        flushFx(e)
        val events = record(e)
        withFxHeld { e.start(); e.pause() }
        sleep(600L)
        flushFx(e)
        assertEquals(emptyList(), events.map { it.rate }, "no away-and-back on a player that is paused again")
        assertEquals(2.0, nativePlayer(e).rate)
    }

    @Test
    fun `a replay after the end at a speed gives the speed away and back`() {
        val listener = RecordingListener()
        val e: JavaFxVideoEngine = loaded(listener)
        e.setSpeed(2f)
        e.start()
        await(10_000L, "completed") { listener.events.any { it is EngineEvent.Completed } }
        val events = record(e)
        e.start()
        await(5_000L, "the nudge after the end") { events.size >= 2 }
        sleep(300L)
        assertEquals(listOf(1.0, 2.0), events.map { it.rate })
    }

    private fun waitForWrap(e: JavaFxVideoEngine) {
        var last = 0L
        await(15_000L, "the loop wrap") {
            val p: Long = e.positionMs()
            val wrapped: Boolean = last > 1_800L && p < 1_000L
            last = p
            wrapped
        }
    }

    @Test
    fun `a speed given right after a loop wrap at normal speed reaches the native player at once`() {
        val e: JavaFxVideoEngine = loaded()
        e.setLooping(true)
        e.start()
        waitForWrap(e)
        e.setSpeed(2f)
        val rate: Double = runBlocking { withTimeout(5_000L) { checkNotNull(e.inspectPlayback()).rate } }
        assertEquals(2.0, rate, "nothing was deferred at a wrap at normal speed")
    }

    @Test
    fun `at a loop wrap the speed is given back only after the playhead has moved 200 ms`() {
        val e: JavaFxVideoEngine = loaded()
        e.setSpeed(2f)
        e.setLooping(true)
        flushFx(e)
        val events = record(e)
        e.start()
        await(15_000L, "the wrap drops the rate to 1.0") { events.any { it.rate == 1.0 && it.atNanos - events.first().atNanos > 1_000_000_000L } }
        val drop: RateEvent = events.first { it.rate == 1.0 && it.atNanos - events.first().atNanos > 1_000_000_000L }
        await(10_000L, "the speed given back") { events.any { it.atNanos > drop.atNanos && it.rate == 2.0 } }
        val back: RateEvent = events.first { it.atNanos > drop.atNanos && it.rate == 2.0 }
        // Native restart latency dominates the wall time, so the playhead is the measure: it stood at about 0 when
        // the cycle restarted and must have moved on by the settle distance (200 ms; JavaFX steps about 100 ms).
        assertTrue(back.positionMs >= 190.0, "the speed came back with the playhead at ${back.positionMs} ms: it had not moved 200 ms")
        assertTrue(back.positionMs < 1_500.0, "the speed came back only at ${back.positionMs} ms")
    }

    @Test
    fun `a seek while the speed is deferred still gets the speed back`() {
        val e: JavaFxVideoEngine = loaded()
        e.setSpeed(2f)
        e.setLooping(true)
        flushFx(e)
        val events = record(e)
        e.start()
        await(15_000L, "the wrap drops the rate to 1.0") { events.any { it.rate == 1.0 && it.atNanos - events.first().atNanos > 1_000_000_000L } }
        val seekAt: Long = System.nanoTime()
        e.seekTo(500L)
        await(10_000L, "the speed given back after the seek") { events.any { it.atNanos > seekAt && it.rate == 2.0 } }
    }

    @Test
    fun `a speed changed a short while after a plain play is given as a resume and clears the fresh-play mark`() {
        val e: JavaFxVideoEngine = loaded()
        e.start()
        flushFx(e)
        assertTrue(longField(e, "playIssuedAtNanos") != 0L, "a plain play leaves the fresh-play mark")
        sleep(200L)
        e.setSpeed(2f)
        flushFx(e)
        assertEquals(0L, longField(e, "playIssuedAtNanos"), "a speed 200 ms after the play is inside the fresh window")
    }

    @Test
    fun `a play after a native seek leaves no fresh-play mark`() {
        val e: JavaFxVideoEngine = loaded()
        e.seekTo(500L)
        e.start()
        flushFx(e)
        assertEquals(0L, longField(e, "playIssuedAtNanos"), "a seek was posted before this play")
    }

    // The native currentTime does not always read a seek that was just posted (it read 0.0 right after seek(2000) in a
    // full-suite run), so a seek to "where the player stands" issued after a posted seek would undo that seek. start()
    // and setSpeed() skip it when a seek is posted since the last play: these trials hit the stale reading sometimes.

    @Test
    fun `a seek posted before a resume at a speed is not undone by the resume`() {
        repeat(12) { trial ->
            val e: JavaFxVideoEngine = loaded()
            try {
                e.setSpeed(2f)
                e.seekTo(2_000L)
                e.start()
                sleep(350L)
                val position: Long = e.positionMs()
                assertTrue(position >= 1_900L, "trial $trial: the playhead is at $position ms after seekTo(2000) and a resume at speed 2")
            } finally {
                e.release()
            }
        }
    }

    @Test
    fun `a replay after the end at a speed starts over and does not complete again at once`() {
        repeat(6) { trial ->
            val listener = RecordingListener()
            val e: JavaFxVideoEngine = loaded(listener)
            try {
                e.setSpeed(2f)
                e.start()
                await(10_000L, "completed") { listener.events.any { it is EngineEvent.Completed } }
                e.start()
                sleep(300L)
                val completions: Int = listener.events.count { it is EngineEvent.Completed }
                val position: Long = e.positionMs()
                assertEquals(1, completions, "trial $trial: the replay completed again within 300 ms (playhead $position)")
                assertTrue(position < 2_500L, "trial $trial: the replay stands at $position ms")
            } finally {
                e.release()
            }
        }
    }

    @Test
    fun `a seek posted right after a play is not undone by a speed given in the fresh-play window`() {
        repeat(12) { trial ->
            val e: JavaFxVideoEngine = loaded()
            try {
                e.start()
                e.seekTo(2_000L)
                e.setSpeed(2f)
                sleep(350L)
                val position: Long = e.positionMs()
                assertTrue(position >= 1_900L, "trial $trial: the playhead is at $position ms after play, seekTo(2000), speed 2")
            } finally {
                e.release()
            }
        }
    }
}
