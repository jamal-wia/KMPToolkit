package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 of the D2-PF1 review: a speed given to a looping clip at normal speed, right after a loop wrap, must not be written
 * within the W-SEEK-RUN window of the wrap.
 *
 * JavaFX starts the next cycle of a looping source with a native seek to the start time that `FxPlayback` does not issue
 * itself, so it never stamps it. At 1x the wrap needs no rate to be given back (`deferRate` returns early), so a
 * `setSpeed` of 2x or 3x within 300 ms after the wrap reached the plain branch of `setSpeed` and was written at once: a
 * rate change that close to a native seek, on a player that is playing, is the D2-PF1 shape that can leave the playhead
 * standing still on macOS. The contract (ruling 3.3): no rate write within 300 ms of a native seek while playing, and the
 * speed arrives later, once.
 *
 * The shape: a looping clip played at 1x, driven to the instant of its first wrap, then 0-250 ms of virtual time, then
 * `setSpeed` of 2x or 3x (from another thread or from the FX thread). The model is built the way every family test builds
 * it: the composed, watched player ([composeAll]), so the hazard monitor fails the case at the end for any violated
 * window (W-WRAP is 100 ms wide, so it catches the gaps below 100 ms), and the case's own expectations catch the rest: the
 * playhead keeps moving, the speed arrives, and no sooner than 300 ms after the wrap.
 *
 * The control shape (the same speed 400 ms after the wrap, outside every window) is green on the old code: it proves the
 * scenario is not vacuous.
 */
class SpeedAtLoopWrapModelTest {

    /** How the caller reaches the engine: from the test thread (another thread for `FxPlayback`) or from the FX thread. */
    private enum class Caller { OTHER_THREAD, FX_THREAD }

    private data class Case(val gapMs: Long, val speed: Float, val caller: Caller) {
        override fun toString(): String = "looping at 1x, wrap, $gapMs ms, setSpeed($speed) from $caller"
    }

    private fun cases(gaps: List<Long>): List<Case> = buildList {
        for (gap: Long in gaps) {
            for (speed: Float in listOf(2f, 3f)) {
                for (caller: Caller in Caller.values()) add(Case(gap, speed, caller))
            }
        }
    }

    /** Runs one [case] in its own model; returns the failure of the scenario or of the end-of-test checks, or null. */
    private fun run(case: Case): String? {
        var failure: Throwable? = null
        try {
            fxModel {
                val scripted = ScriptedBackend(host)
                val backend: ComposedBackend = composeAll(scripted)
                val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
                load(engine, VideoSource.Asset(TestClip.ASSET))
                try {
                    val composed: ComposedPlayer = backend.last
                    val native: ScriptedNativePlayer = composed.player
                    var wrapAtMs = Double.NaN
                    composed.loop.onWrap { wrapAtMs = host.elapsedNanos / NANOS_PER_MS }
                    engine.setLooping(true)
                    engine.start()
                    engine.setSpeed(1f)
                    var steps = 0
                    while (composed.loop.cycle < 2) {
                        check(steps++ < MAX_STEPS) { "the first wrap did not come ($host)" }
                        check(step()) { "the host ran dry before the first wrap ($host)" }
                    }
                    advanceBy(case.gapMs)
                    when (case.caller) {
                        Caller.OTHER_THREAD -> engine.setSpeed(case.speed)
                        Caller.FX_THREAD -> onFx { engine.setSpeed(case.speed) }
                    }
                    advanceBy(OBSERVE_UNTIL_MS - case.gapMs)

                    assertTrue(!composed.hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    assertEquals(
                        case.speed.toDouble(), composed.hazards.effectiveRate,
                        "the speed never reached the decoder\n${native.log}",
                    )
                    val given: NativeCallEntry = native.log.calls(NativeCall.SET_RATE)
                        .first { it.effective && it.arg == case.speed.toDouble() && it.tMs >= wrapAtMs }
                    assertTrue(
                        given.tMs - wrapAtMs >= HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS,
                        "the speed was written ${given.tMs - wrapAtMs} ms after the wrap, inside W-SEEK-RUN\n${native.log}",
                    )
                    assertTrue(native.positionMs >= MIN_POSITION_MS, "the playhead stands at ${native.positionMs} ms\n${native.log}")
                } finally {
                    engine.release()
                    advanceBy(50)
                }
            }
        } catch (t: Throwable) {
            failure = t
        }
        return failure?.let { "[$case] ${it.message?.lineSequence()?.firstOrNull()}" }
    }

    private companion object {
        /** The virtual clock is in nanoseconds and the log in milliseconds: 300 ms can read 299.99999999999994. */
        const val ROUNDING_MS: Double = 0.001
        const val NANOS_PER_MS: Double = 1_000_000.0

        /** How long after the wrap a case is observed: long enough for the deferred speed (300 ms, then 200 ms of media at 1x), short of the second wrap. */
        const val OBSERVE_UNTIL_MS: Long = 1_000L
        const val MIN_POSITION_MS: Double = 800.0
        const val MAX_STEPS: Int = 100_000
    }

    private fun runAll(cases: List<Case>) {
        val failures: List<String> = cases.mapNotNull { run(it) }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} cases failed")
    }

    @Test
    fun aSpeedGivenRightAfterALoopWrapAtNormalSpeedIsNeverWrittenInsideTheSeekWindow() {
        runAll(cases(listOf(0L, 50L, 100L, 150L, 250L)))
    }

    /** The control: 400 ms after the wrap no window is open, so the speed is written at once. Green on the old code. */
    @Test
    fun aSpeedGivenLongAfterALoopWrapAtNormalSpeedIsGivenAtOnce() {
        val failures: List<String> = cases(listOf(400L)).mapNotNull { run(it) }
        assertEquals(emptyList(), failures, "${failures.size} control cases failed")
    }
}
