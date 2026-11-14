package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 boss 1, B6 gate pin (ruling reviews/d2-boss1-b6-ruling-opus.md, rule R2, D-283 and D-285): on the real macOS decoder a looping
 * 3 s clip at 2x, paused at the instant it wraps and started again at once, must not stand still with the status PLAYING. Before the
 * fix `start()` read the playhead held near 0 after the wrap and took the seek path (PAUSE, SEEK(0), PLAY, then the rate pair),
 * which left the playhead at 0 for the whole observation window in 6 of 60 trials; the settle path (a plain play, no seek of its
 * own) held in 0 of 60, and so did raw JavaFX and the engine at speed 1 (adversarial/D2/boss-1-b6-ab.md).
 *
 * TIER R (real-media soak, `jvmRealMediaTest`): it needs the exclusive real-media slot and `-Pjavafx.required=true`. Knobs
 * `-Pkmptoolkit.test.b6.b6Trials=<n>` (60, about three minutes) and `-Pkmptoolkit.test.b6.b6MaxHeld=<n>` (1, the bound of the ruling:
 * the platform may still hold now and then on its own, the engine must not add to it). Its exact F twin is
 * `model.ResumeAtWrapD2Boss1B6ModelTest`.
 *
 * One trial: seek to 2.3 s, start, wait until the published time has passed 2.6 s and then fallen below 1 s (the wrap), then
 * `pause()` and `start()` at once; the trial is HELD when the playhead does not advance by 400 ms from the lowest position seen since
 * the call within 5 s. A held trial is recovered (a seek to the position it stands at, then 400 ms of advance awaited) so that it
 * cannot carry into the next trial and the count stays honest on a regression. Every wait is a polled condition with a real-time hang bound (`awaitTrue`); the verdict is a boolean the
 * condition produced, never an elapsed time.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxResumeAtWrapD2Boss1B6RealMediaTest {

    private var engine: JavaFxVideoEngine? = null

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        engine?.release()
    }

    private fun knob(name: String, default: Int): Int =
        System.getProperty("kmptoolkit.test.b6.$name")?.toIntOrNull() ?: default

    @Test
    fun `a looping clip at twice the speed paused at the wrap and started again moves on in all but at most one of sixty trials`() {
        val trials: Int = knob("b6Trials", DEFAULT_TRIALS)
        val maxHeld: Int = knob("b6MaxHeld", DEFAULT_MAX_HELD)
        val engine = JavaFxVideoEngine(readyTimeoutMs = 15_000L).also { this.engine = it }
        runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
        engine.setLooping(true)
        engine.setSpeed(2f)
        val held: MutableList<Int> = mutableListOf()
        for (trial: Int in 0 until trials) {
            engine.seekTo(2_300L)
            engine.start()
            awaitTrue(HANG_MS, { "the playhead never passed 2600 ms (trial $trial)" }) { engine.positionMs() >= 2_600L }
            awaitTrue(HANG_MS, { "the clip never wrapped (trial $trial)" }) { engine.positionMs() < 1_000L }
            engine.pause()
            engine.start()
            var lowest: Long = engine.positionMs()
            val advanced: Boolean = try {
                awaitTrue(HOLD_WINDOW_MS, { "held" }) {
                    val position: Long = engine.positionMs()
                    if (position < lowest) lowest = position
                    position - lowest >= ADVANCE_MS
                }
                true
            } catch (_: AssertionError) {
                false
            }
            if (!advanced) {
                held += trial
                // The recovery of adversarial/D2/boss-1-b6-ab.md: a seek to where the playhead stands moves a held decoder on, so
                // the next trial starts from a playing clip. Whether it recovers is not asserted, only awaited within a bound.
                engine.seekTo(engine.positionMs())
                var recoveredFrom: Long = engine.positionMs()
                try {
                    awaitTrue(HOLD_WINDOW_MS, { "not recovered" }) {
                        val position: Long = engine.positionMs()
                        if (position < recoveredFrom) recoveredFrom = position
                        position - recoveredFrom >= ADVANCE_MS
                    }
                } catch (_: AssertionError) {
                    // A decoder that stays held is reported by the next trial's waits with their own messages.
                }
            }
            engine.pause()
        }
        println("B6-GATE-PIN held=${held.size} of $trials trials (allowed at most $maxHeld) held trials=$held")
        assertTrue(
            held.size <= maxHeld,
            "the playhead stood still for ${HOLD_WINDOW_MS / 1_000L} s in ${held.size} of $trials trials after pause() and start() at a loop wrap " +
                "(allowed: at most $maxHeld; held trials: $held)",
        )
    }

    private companion object {
        const val DEFAULT_TRIALS: Int = 60
        const val DEFAULT_MAX_HELD: Int = 1
        const val HANG_MS: Long = 8_000L
        const val HOLD_WINDOW_MS: Long = 5_000L
        const val ADVANCE_MS: Long = 400L
    }
}
