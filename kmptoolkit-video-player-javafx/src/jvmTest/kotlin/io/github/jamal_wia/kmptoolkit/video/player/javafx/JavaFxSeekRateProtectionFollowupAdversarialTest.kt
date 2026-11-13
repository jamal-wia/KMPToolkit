package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
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
 * Follow-up of D2-PF1 (Opus review minors M1 and M2): two paths that give the native player a rate right after a native
 * seek, outside the seek protection the D2-PF1 fix put in `FxPlayback.setSpeed`.
 *
 * - M1: a speed given within 600 ms of a plain `play()` took the fresh-play branch even when a native seek had been
 *   posted after that play, so it skipped the 300 ms seek settle (near the start it wrote `rate = 1.0; rate = s` at once).
 * - M2: the native restart of a looping source is a native seek that `FxPlayback` did not record, so a speed given right
 *   after the wrap at normal speed was written at once.
 *
 * Deterministic by construction: no wait for the media and no sleep. The operations are posted behind a held FX thread
 * (so they run in order within microseconds, far inside the 300 ms and 600 ms windows) and the verdict reads the private
 * state of `FxPlayback` on the FX thread by reflection, the way `JavaFxRateGivingR8AdversarialTest` does. The loop
 * restart is driven by running the player's own end-of-media handler on the FX thread, which is what JavaFX does at the
 * end of every cycle of a looping source.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxSeekRateProtectionFollowupAdversarialTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    /** What the speed machinery of the loaded playback holds, read on the FX thread. */
    private class State(
        val rate: Double,
        val deferredRate: Double?,
        val settleNudge: Double?,
        val pendingNudge: Double?,
        val lastNativeSeekNanos: Long,
    )

    private fun loaded(): JavaFxVideoEngine {
        val e = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { engine = it }
        runBlocking { withTimeout(20_000L) { e.load(VideoSource.Asset(TestClip.ASSET)) } }
        return e
    }

    private fun playback(e: JavaFxVideoEngine): Any {
        val m = JavaFxVideoEngine::class.java.getDeclaredMethod("activePlayback").apply { isAccessible = true }
        return checkNotNull(m.invoke(e)) { "no playback" }
    }

    private fun field(pb: Any, name: String): Any? = pb.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(pb)

    private fun setField(pb: Any, name: String, value: Long) {
        pb.javaClass.getDeclaredField(name).apply { isAccessible = true }.setLong(pb, value)
    }

    private fun state(e: JavaFxVideoEngine): State {
        val pb: Any = playback(e)
        return runBlocking {
            withTimeout(5_000L) {
                FxThread.call {
                    State(
                        rate = (field(pb, "player") as MediaPlayer).rate,
                        deferredRate = field(pb, "deferredRate") as Double?,
                        settleNudge = field(pb, "settleNudge") as Double?,
                        pendingNudge = field(pb, "pendingNudge") as Double?,
                        lastNativeSeekNanos = field(pb, "lastNativeSeekNanos") as Long,
                    )
                }
            }
        }
    }

    /** Runs [block] with the FX thread held, so everything the test thread posts meanwhile queues in order. */
    private fun withFxHeld(block: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Platform.runLater { entered.countDown(); release.await(10, TimeUnit.SECONDS) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try { block() } finally { release.countDown() }
    }

    /** Runs [block] as one FX task, so the calls in it follow each other without a pause. */
    private fun onFxThread(block: () -> Unit) {
        runBlocking { withTimeout(5_000L) { FxThread.call { block() } } }
    }

    private fun assertSpeedHeldBack(e: JavaFxVideoEngine, speed: Double, what: String) {
        val s: State = state(e)
        assertEquals(1.0, s.rate, "$what: the native rate was written at once")
        assertEquals(speed, s.deferredRate, "$what: the speed must wait in the deferred-rate machinery")
        assertEquals(null, s.settleNudge, "$what: the speed must not wait in the resume nudge, which has no seek settle")
        assertEquals(null, s.pendingNudge, "$what: no start nudge is pending")
    }

    // M1

    @Test
    fun `a speed given after a plain play and a seek to the start is held back by the seek settle`() {
        val e: JavaFxVideoEngine = loaded()
        withFxHeld { e.start(); e.seekTo(0L); e.setSpeed(2f) }
        assertSpeedHeldBack(e, 2.0, "plain play then seek to 0 then speed")
    }

    @Test
    fun `a speed given after a plain play and a seek further on is held back by the seek settle`() {
        val e: JavaFxVideoEngine = loaded()
        withFxHeld { e.start(); e.seekTo(1_500L); e.setSpeed(3f) }
        assertSpeedHeldBack(e, 3.0, "plain play then seek to 1500 then speed")
    }

    @Test
    fun `the seek settle of a speed after a plain play and a seek ends at 300 ms after the seek`() {
        val e: JavaFxVideoEngine = loaded()
        withFxHeld { e.start(); e.seekTo(1_500L); e.setSpeed(3f) }
        val pb: Any = playback(e)
        val notBefore: Long = runBlocking { withTimeout(5_000L) { FxThread.call { field(pb, "deferredNotBeforeNanos") as Long } } }
        val seekAt: Long = state(e).lastNativeSeekNanos
        assertEquals(seekAt + 300_000_000L, notBefore, "the speed is not given before 300 ms after the native seek")
    }

    @Test
    fun `a speed given after a plain play with no seek still takes the resume path`() {
        // Control: the plain-resume protection stays as it was when no native seek has been posted.
        val e: JavaFxVideoEngine = loaded()
        withFxHeld { e.start(); e.setSpeed(2f) }
        val s: State = state(e)
        assertEquals(null, s.deferredRate, "no seek was posted before the speed, so there is nothing to defer for")
        assertEquals(2.0, s.rate, "the fresh-play path gives the speed at once, away and back, at the start of the clip")
    }
}
