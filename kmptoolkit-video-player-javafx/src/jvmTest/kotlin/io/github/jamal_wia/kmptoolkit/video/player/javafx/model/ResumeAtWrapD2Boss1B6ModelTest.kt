package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 1, B6 (ruling reviews/d2-boss1-b6-ruling-opus.md, rule R2, D-283 and D-285): a looping clip at a speed other than 1
 * that is paused at the instant it wraps and played again soon after must resume on the settle path (a plain play, no native
 * seek of its own, the speed given as one away-and-back once the playhead has really moved 200 ms), not on the seek path.
 * Right after a wrap the native playhead stands at 0 for about 300 ms (the real engine publishes that 0; the model's published time keeps the old cycle's end meanwhile), so [start] sees the playhead "near the start" and, before
 * the fix, issued PAUSE, SEEK(0), PLAY, SET_RATE(1.0), SET_RATE(2.0) at the wrap instant, right next to JavaFX's own restart
 * seek. On the real macOS decoder that sequence left the player PLAYING with the playhead standing at 0 for the whole
 * observation window in 6 of 60 trials (adversarial/D2/boss-1-b6-ab.md); the settle path held in 0 of 60, and so did raw JavaFX
 * and the engine at speed 1. The replay `adversarial/D2/suspects/boss1-B6-model-replay.kt` is the shape of these tests.
 *
 * A start more than half a second after the wrap (the window is 500 ms) near the start of the clip still takes the seek path:
 * the seek to where the playhead stands moves nothing there, and it is the guard the seek path exists for.
 *
 * Both decoder variants of the model are run: one that starts every play at normal speed (macOS) and one that honours the rate
 * property (the assumed Windows and Linux behaviour).
 */
class ResumeAtWrapD2Boss1B6ModelTest {

    /** What one run saw from the `start()` call on. */
    private class Observation(
        val unity: Boolean,
        val gapMs: Long,
        val nativeAtPauseMs: Long,
        val seeks: List<NativeCallEntry>,
        val plays: List<NativeCallEntry>,
        val rateWrites: List<NativeCallEntry>,
        val speedGivenAfterPlayMs: Double?,
        val violations: List<String>,
        val frozen: Boolean,
        val furthestNativeMs: Long,
        val log: String,
    ) {
        override fun toString(): String =
            "unity=$unity gap=${gapMs}ms nativeAtPause=${nativeAtPauseMs}ms seeks=${seeks.size} plays=${plays.size} " +
                "rates=${rateWrites.map { it.arg }} speedGivenAfterPlay=${speedGivenAfterPlayMs}ms frozen=$frozen " +
                "violations=$violations furthestNative=${furthestNativeMs}ms\n$log"
    }

    /**
     * A looping 3 s clip at 2x, started at 2.3 s; at the wrap (detected by the loop model's cycle count, the engine's own
     * published time still reads the old cycle for the publish hold) it pauses, waits [gapMs] and starts again, then runs on.
     */
    private fun observe(gapMs: Long, decoderStartsAtUnity: Boolean): Observation {
        lateinit var seen: Observation
        fxModel {
            val backend: ComposedBackend = composeAll(
                ScriptedBackend(host, clip = ClipModel(durationMs = CLIP_MS, keyFrameIntervalMs = 100.0), script = StatusScript()),
                timeScript = TimeScript(),
                hazardConfig = HazardConfig(decoderStartsAtUnity = decoderStartsAtUnity),
            )
            val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
            load(engine, VideoSource.Asset(TestClip.ASSET))
            try {
                val composed: ComposedPlayer = backend.last
                val native: ScriptedNativePlayer = composed.player
                engine.setLooping(true)
                engine.setSpeed(2f)
                engine.seekTo(2_300L)
                engine.start()
                var guard = 0
                while (engine.positionMs() < 2_600L) {
                    check(guard++ < MAX_STEPS) { "the clip never passed 2600 ms" }
                    advanceBy(1L)
                }
                while (composed.loop.cycle < 2) {
                    check(guard++ < MAX_STEPS) { "the clip never wrapped" }
                    advanceBy(1L)
                }
                engine.pause()
                val nativeAtPause: Long = native.positionMs.toLong()
                if (gapMs > 0L) advanceBy(gapMs)
                val callsBeforeStart: Int = native.log.size
                engine.start()
                val trace = StringBuilder()
                var elapsed = 0L
                var furthest = 0L
                while (elapsed < OBSERVE_MS) {
                    advanceBy(TRACE_STEP_MS)
                    elapsed += TRACE_STEP_MS
                    furthest = maxOf(furthest, native.positionMs.toLong())
                    trace.append(" $elapsed:${native.positionMs.toLong()}/${engine.positionMs()}")
                }
                val after: List<NativeCallEntry> = native.log.entries.drop(callsBeforeStart).filter { it.call != NativeCall.SNAPSHOT }
                val plays: List<NativeCallEntry> = after.filter { it.call == NativeCall.PLAY }
                val rateWrites: List<NativeCallEntry> = after.filter { it.call == NativeCall.SET_RATE }
                val speedGiven: NativeCallEntry? = rateWrites.firstOrNull { it.arg == 2.0 }
                seen = Observation(
                    unity = decoderStartsAtUnity,
                    gapMs = gapMs,
                    nativeAtPauseMs = nativeAtPause,
                    seeks = after.filter { it.call == NativeCall.SEEK },
                    plays = plays,
                    rateWrites = rateWrites,
                    speedGivenAfterPlayMs = if (speedGiven != null && plays.isNotEmpty()) speedGiven.tMs - plays.first().tMs else null,
                    violations = composed.monitor.violations.map { it.describe() },
                    frozen = composed.hazards.frozen,
                    furthestNativeMs = furthest,
                    log = after.joinToString("\n") { "    $it" } + "\n    trace(ms:native/published)=$trace",
                )
            } finally {
                engine.release()
                advanceBy(50)
            }
        }
        return seen
    }

    /**
     * A start 0 to 450 ms after a pause at the wrap takes the settle path: no native seek of its own, one play, then the rate put
     * back to 1.0 and the speed given after the playhead has really moved 200 ms; no hazard window is violated and the playhead
     * is not frozen and moves on.
     */
    @Test
    fun aStartWithinHalfASecondOfAWrapPlaysWithoutASeekAndGivesTheSpeedAfterTwoHundredMillisecondsOfMovement() {
        for (unity: Boolean in listOf(true, false)) {
            for (gapMs: Long in listOf(0L, 5L, 50L, 200L, 450L)) {
                val seen: Observation = observe(gapMs, unity)
                println("BOSS1-B6-FIX $seen")
                assertTrue(seen.nativeAtPauseMs <= NEAR_START_MS, "the premise: the native playhead stands near 0 at the wrap: $seen")
                assertEquals(0, seen.seeks.size, "the start took the seek path: $seen")
                assertEquals(1, seen.plays.size, "the start did not issue exactly one play: $seen")
                val speedAfter: Double? = seen.speedGivenAfterPlayMs
                assertTrue(speedAfter != null, "the speed was never given: $seen")
                assertTrue(speedAfter >= SETTLE_MS, "the speed was given before the playhead had moved 200 ms: $seen")
                assertTrue(speedAfter <= SETTLE_LATEST_MS, "the speed was given far too late: $seen")
                assertEquals(
                    listOf(1.0, 2.0),
                    seen.rateWrites.map { it.arg },
                    "the speed was not given as one away-and-back: $seen",
                )
                assertTrue(
                    seen.rateWrites.last().positionMs >= SETTLE_MS,
                    "the speed was written before the native playhead had really moved 200 ms: $seen",
                )
                assertEquals(emptyList(), seen.violations, "a fragile window was violated: $seen")
                assertTrue(!seen.frozen, "the playhead froze: $seen")
                assertTrue(seen.furthestNativeMs > MOVED_MS, "the playhead did not move on after the start: $seen")
            }
        }
    }

    /**
     * A start 600 ms after the wrap near the start of the clip is past the window: it still takes the seek path (PAUSE is the
     * pause before it, then SEEK to where the playhead stands, PLAY), the guard that path exists for.
     */
    @Test
    fun aStartSixHundredMillisecondsAfterAWrapNearTheStartStillTakesTheSeekPath() {
        for (unity: Boolean in listOf(true, false)) {
            val seen: Observation = observe(600L, unity)
            println("BOSS1-B6-PIN $seen")
            assertTrue(seen.nativeAtPauseMs <= NEAR_START_MS, "the premise: the native playhead stands near 0 at the pause: $seen")
            assertEquals(1, seen.seeks.size, "the start did not take the seek path: $seen")
            assertEquals(1, seen.plays.size, "the start did not issue exactly one play: $seen")
            assertEquals(emptyList(), seen.violations, "a fragile window was violated: $seen")
            assertTrue(!seen.frozen, "the playhead froze: $seen")
        }
    }

    private companion object {
        const val CLIP_MS: Double = 3_000.0
        const val OBSERVE_MS: Long = 1_200L
        const val MAX_STEPS: Int = 100_000
        const val TRACE_STEP_MS: Long = 100L
        const val NEAR_START_MS: Long = 100L
        const val SETTLE_MS: Double = 200.0
        const val SETTLE_LATEST_MS: Double = 700.0
        const val MOVED_MS: Long = 300L
    }
}
