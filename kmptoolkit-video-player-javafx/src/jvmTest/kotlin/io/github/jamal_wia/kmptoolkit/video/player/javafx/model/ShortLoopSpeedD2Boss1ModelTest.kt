package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.lang.reflect.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D2 boss 1, B1 (ruling reviews/d2-boss1-ruling-opus.md section 1, D-111): a pin of the sentence of 05-platform-notes.md,
 * "Playback speed", as it reads after the ruling. After a loop wrap the engine puts the rate back to normal and gives the
 * speed again once the playhead has wrapped and moved about 200 ms, which lands about 0.55 s into the cycle; so a looping
 * clip whose cycle outlasts that wait keeps its speed in every cycle after the first, and a looping clip shorter than
 * about 0.6 s wraps again before the speed returns and plays every cycle after the first at normal speed. The limit is
 * the intended behaviour: restoring the speed inside such a short cycle would be a rate change right after a native seek
 * on every cycle, the measured freeze shape. The suspect `adversarial/D2/suspects/boss1-B1-model.kt` stays as evidence of
 * the boss's measurement.
 *
 * Both decoder variants of the model are run: one that starts every play at normal speed (macOS) and one that honours the
 * rate property (the assumed Windows and Linux behaviour), because the sentence says "on every platform".
 */
class ShortLoopSpeedD2Boss1ModelTest {

    private val holderFields: List<Field> =
        listOf("pendingNudge", "settleNudge", "deferredRate").map { name: String ->
            FxPlayback::class.java.getDeclaredField(name).apply { isAccessible = true }
        }

    private val sessionField: Field = JavaFxVideoEngine::class.java.getDeclaredField("session").apply { isAccessible = true }

    private fun playbackOf(engine: JavaFxVideoEngine): FxPlayback {
        val session: Any = checkNotNull(sessionField.get(engine)) { "no session after the load" }
        val field: Field = session.javaClass.getDeclaredField("playback").apply { isAccessible = true }
        return checkNotNull(field.get(session) as FxPlayback?) { "no playback after the load" }
    }

    /** The speed holders that are set right now, as `name=value`. */
    private fun setHolders(playback: FxPlayback): List<String> =
        holderFields.mapNotNull { field: Field -> field.get(playback)?.let { value: Any -> "${field.name}=$value" } }

    /** What one run saw: per cycle whether the decoder ran at 2x, and the state at every sample after the first wrap. */
    private class Observation(
        val clipMs: Double,
        val unity: Boolean,
        val cyclesAt2x: MutableMap<Int, Boolean> = linkedMapOf(),
        val holdersAfterFirstWrap: MutableList<List<String>> = mutableListOf(),
        var samplesAt2xAfterFirstWrap: Int = 0,
        var frozenSamples: Int = 0,
        var violations: Int = 0,
        var lastCycle: Int = 0,
        var holdersAfterPause: List<String> = emptyList(),
        var cyclesAfterRestart: Int = 0,
        var holdersAfterRestart: List<String> = emptyList(),
    ) {
        override fun toString(): String =
            "clip=${clipMs}ms unity=$unity cycles=$lastCycle samplesAt2x=$samplesAt2xAfterFirstWrap frozen=$frozenSamples " +
                "violations=$violations cyclesAt2x=$cyclesAt2x"
    }

    private fun observe(clipMs: Double, decoderStartsAtUnity: Boolean): Observation {
        val seen = Observation(clipMs, decoderStartsAtUnity)
        fxModel {
            val backend: ComposedBackend = composeAll(
                ScriptedBackend(host, clip = ClipModel(durationMs = clipMs, keyFrameIntervalMs = 100.0), script = StatusScript()),
                timeScript = TimeScript(),
                hazardConfig = HazardConfig(decoderStartsAtUnity = decoderStartsAtUnity),
            )
            val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
            load(engine, VideoSource.Asset(TestClip.ASSET))
            try {
                val composed: ComposedPlayer = backend.last
                val playback: FxPlayback = playbackOf(engine)
                engine.setLooping(true)
                engine.setSpeed(2f)
                engine.start()
                repeat(SAMPLES) {
                    advanceBy(SAMPLE_MS)
                    val cycle: Int = composed.loop.cycle
                    if (composed.hazards.frozen) seen.frozenSamples++
                    if (cycle >= 2) {
                        val at2x: Boolean = composed.hazards.effectiveRate == 2.0
                        if (at2x) seen.samplesAt2xAfterFirstWrap++
                        seen.cyclesAt2x[cycle] = (seen.cyclesAt2x[cycle] ?: false) || at2x
                        seen.holdersAfterFirstWrap += setHolders(playback)
                    }
                    seen.lastCycle = cycle
                }
                seen.violations = composed.monitor.violations.size
                // The next start: the speed is still held, in one holder, across a pause.
                engine.pause()
                advanceBy(RESTART_AFTER_PAUSE_MS)
                seen.holdersAfterPause = setHolders(playback)
                val cycleBefore: Int = composed.loop.cycle
                engine.start()
                advanceBy(RESTART_OBSERVE_MS)
                seen.cyclesAfterRestart = composed.loop.cycle - cycleBefore
                seen.holdersAfterRestart = setHolders(playback)
                seen.violations = composed.monitor.violations.size
                if (composed.hazards.frozen) seen.frozenSamples++
            } finally {
                engine.release()
                advanceBy(50)
            }
        }
        return seen
    }

    private fun both(clipMs: Double): List<Observation> =
        listOf(true, false).map { unity: Boolean -> observe(clipMs, unity).also { println("BOSS1-B1-PIN $it") } }

    /** A loop of 1.2 s outlasts the wait of about 0.55 s: the speed is given again in every cycle after the first. */
    @Test
    fun aLoopingClipOfOnePointTwoSecondsReachesItsSpeedInEveryCycleAfterTheFirst() {
        for (seen: Observation in both(1_200.0)) {
            val completed: Map<Int, Boolean> = seen.cyclesAt2x.filterKeys { it < seen.lastCycle }
            assertTrue(completed.size >= MIN_CYCLES, "too few completed cycles to judge: $seen")
            val without2x: List<Int> = completed.filterValues { !it }.keys.toList()
            assertEquals(emptyList(), without2x, "cycles that never reached 2x: $seen")
            assertEquals(0, seen.frozenSamples, "the playhead froze: $seen")
            assertEquals(0, seen.violations, "a fragile window was violated: $seen")
        }
    }

    /**
     * A loop of 300 ms wraps again before the speed returns: it plays on, at normal speed, in every cycle after the first,
     * with no hazard window violated and no freeze, and the speed stays held in exactly one holder (I1) for the next start.
     */
    @Test
    fun aLoopingClipOfThreeHundredMillisecondsPlaysOnAtNormalSpeedWithTheSpeedHeldInOneHolder() {
        for (seen: Observation in both(300.0)) {
            assertTrue(seen.lastCycle >= MIN_SHORT_CYCLES, "the short loop stopped wrapping: $seen")
            assertEquals(0, seen.samplesAt2xAfterFirstWrap, "a cycle after the first reached 2x: $seen")
            assertEquals(0, seen.frozenSamples, "the playhead froze: $seen")
            assertEquals(0, seen.violations, "a fragile window was violated: $seen")
            assertEquals(
                emptyList(),
                seen.holdersAfterFirstWrap.filter { it != SPEED_HELD },
                "the speed was not held in exactly one holder at every sample after the first wrap: $seen",
            )
            assertEquals(SPEED_HELD, seen.holdersAfterPause, "the pause lost or doubled the held speed: $seen")
            assertTrue(seen.cyclesAfterRestart >= 1, "the clip did not play on after the next start: $seen")
            assertEquals(SPEED_HELD, seen.holdersAfterRestart, "the next start lost or doubled the held speed: $seen")
        }
    }

    private companion object {
        const val SAMPLE_MS: Long = 50L
        const val SAMPLES: Int = 160
        const val RESTART_AFTER_PAUSE_MS: Long = 400L
        const val RESTART_OBSERVE_MS: Long = 1_000L
        const val MIN_CYCLES: Int = 4
        const val MIN_SHORT_CYCLES: Int = 20

        /** The one holder a speed other than 1 waits in after a wrap. */
        val SPEED_HELD: List<String> = listOf("deferredRate=2.0")
    }
}
