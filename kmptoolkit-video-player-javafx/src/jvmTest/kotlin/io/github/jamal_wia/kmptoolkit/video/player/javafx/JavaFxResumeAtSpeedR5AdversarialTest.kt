package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * SUSPECT r5-D2-S2: on macOS a pause followed by a start at a speed other than 1 sometimes leaves the
 * playhead standing still for good while JavaFX reports PLAYING (probe: speed 4, playhead frozen at 373
 * for 3.5 s). The engine's start() calls `player.play()` and then [FxPlayback.reapplyRate] nudges the rate
 * to 1.0 and back to the speed straight away. The control at speed 1 (no nudge) never stalls.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxResumeAtSpeedR5AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    /** How many of [trials] fresh engines, paused once and started again at [speed], never moved on. */
    private fun stalledResumes(speed: Float, trials: Int): List<String> {
        val stalled: MutableList<String> = mutableListOf()
        repeat(trials) { trial: Int ->
            val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
            try {
                runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                engine.setSpeed(speed)
                engine.start()
                awaitTrue(message = { "trial $trial: the clip never advanced" }) { engine.positionMs() > 300L }
                engine.pause()
                Thread.sleep(300L)
                val paused: Long = engine.positionMs()
                engine.start()
                val deadline: Long = System.nanoTime() + 2_500L * 1_000_000L
                while (System.nanoTime() < deadline && engine.positionMs() <= paused + 150L) Thread.sleep(20L)
                if (engine.positionMs() <= paused + 150L) stalled += "trial $trial stood at ${engine.positionMs()} from $paused"
            } finally {
                engine.release()
            }
        }
        return stalled
    }

    @Test
    fun `a resume at a speed of four moves the playhead on every time`() {
        assertEquals(emptyList(), stalledResumes(speed = 4f, trials = 6))
    }

    @Test
    fun `a resume at normal speed moves the playhead on every time`() {
        assertEquals(emptyList(), stalledResumes(speed = 1f, trials = 6))
    }

    /**
     * Measured on the old code in three runs: the playhead moved 0.99 and 0.91 ms per ms after the resume, and once
     * stood still.
     *
     * The speed is measured once the resume has settled (the playhead 600 ms past where it paused, D-16): a
     * window that starts at the first move after the resume can read a held or catching-up playhead as JavaFX
     * reports it, not the speed. The jump lands before paused + 600.
     *
     * D-155: the window is anchored at the second change of the reported time after the resume, not at the reading
     * after the pause, which can be stale; see the comment at the window.
     */
    @Test
    fun `a pause and resume at a speed of two keeps the speed`() {
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L)
        try {
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            engine.setSpeed(2f)
            engine.start()
            awaitTrue(message = { "the clip never advanced" }) { engine.positionMs() > 300L }
            engine.pause()
            Thread.sleep(300L)
            val paused: Long = engine.positionMs()
            engine.start()
            // D-155: the reading after a pause can be stale (about 420 ms behind the decoder on macOS), and so can the
            // first one after the resume. The window is anchored at the second change of the reported time after the
            // resume, a reading the clock vouches for, and starts 600 ms of media later, past the speed being given and
            // the short hold that follows it. 400 ms, not 600: the 3 s clip must not end inside the window.
            awaitTrue(message = { "the playhead never moved on after the resume: ${engine.positionMs()} from $paused" }) {
                engine.positionMs() != paused
            }
            val firstChange: Long = engine.positionMs()
            awaitTrue(message = { "the playhead never moved on after the resume: ${engine.positionMs()} from $paused" }) {
                engine.positionMs() != firstChange
            }
            val resumedFrom: Long = engine.positionMs()
            awaitTrue(message = { "the playhead never moved on after the resume: ${engine.positionMs()} from $resumedFrom" }) {
                engine.positionMs() > resumedFrom + 600L
            }
            val first: Long = engine.positionMs()
            val begin: Long = System.nanoTime()
            Thread.sleep(400L)
            // Both edges of the window are timed at a change of the reported playhead (JavaFX publishes it about
            // every 100 ms, so a window ending at an arbitrary instant reads up to one step short: 1.49 at 400 ms
            // for a correct engine). D-22, plans/s6-slope-window-advice.md.
            val last: Long = engine.positionMs()
            val changeDeadline: Long = System.nanoTime() + 250L * 1_000_000L
            var now: Long = last
            while (now == last && System.nanoTime() < changeDeadline) {
                Thread.sleep(5L)
                now = engine.positionMs()
            }
            val speed: Double = (now - first) / ((System.nanoTime() - begin) / 1_000_000.0)
            kotlin.test.assertTrue(speed > 1.5, "after a resume the playhead moved $speed ms per ms at a speed of 2")
        } finally {
            engine.release()
        }
    }
}
