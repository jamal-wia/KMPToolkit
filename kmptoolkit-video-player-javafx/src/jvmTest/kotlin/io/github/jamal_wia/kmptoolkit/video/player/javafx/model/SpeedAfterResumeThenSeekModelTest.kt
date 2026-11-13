package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 of the D2-PF1 review: a speed given to a playing clip right after a seek must never be written within the
 * W-SEEK-RUN window, even when the clip was resumed (or first played) only a moment before.
 *
 * The shape: a plain `start()` (a play with no native seek before it, so `FxPlayback` remembers it as FRESH for 600 ms),
 * then 150-500 ms of virtual time (the native status PLAYING has landed, so the decoder has started), then a seek of the
 * playing clip (`seekTo(1500)`, or `seekTo(0)` as the shared player's `replay()` of a playing clip does), then
 * `setSpeed` with a gap of 0-5 ms. Production routes that speed through the FRESH branch of `setSpeed`, which comes
 * before the seek-window branch: it writes the rate right after the seek (near the start) or 200 ms after it (further
 * on), inside the 300 ms window in which a rate change can leave the native player Playing with its playhead standing
 * still. The model is built the way every family test builds it: the composed, watched player ([composeAll]), so the
 * hazard monitor fails the test at the end for any violated window, and the scenario's own expectations (the
 * playhead keeps moving, the speed arrives once, no sooner than the window allows) fail it a second time.
 *
 * The control shape (the same seek and speed after the FRESH period is over) is green on the old code: it is the
 * D2-PF1 fix itself, so it proves the scenario is not vacuous.
 */
class SpeedAfterResumeThenSeekModelTest {

    /** How the caller reaches the engine: from the test thread (another thread for `FxPlayback`) or from the FX thread. */
    private enum class Caller { OTHER_THREAD, FX_THREAD }

    private data class Case(
        val seekToMs: Long,
        val waitAfterPlayMs: Long,
        val gapMs: Long,
        val speed: Float,
        val caller: Caller,
    ) {
        override fun toString(): String =
            "play, $waitAfterPlayMs ms, seekTo($seekToMs), $gapMs ms, setSpeed($speed) from $caller"
    }

    private fun cases(seekToMs: Long): List<Case> = buildList {
        for (wait: Long in listOf(150L, 300L, 500L)) {
            for (speed: Float in listOf(2f, 3f)) {
                for (gap: Long in listOf(0L, 2L, 5L)) add(Case(seekToMs, wait, gap, speed, Caller.OTHER_THREAD))
                add(Case(seekToMs, wait, 0L, speed, Caller.FX_THREAD))
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
                    engine.start()
                    advanceBy(case.waitAfterPlayMs)
                    val seekAt: Double = host.elapsedNanos / 1_000_000.0
                    when (case.caller) {
                        Caller.OTHER_THREAD -> {
                            engine.seekTo(case.seekToMs)
                            advanceBy(case.gapMs)
                            engine.setSpeed(case.speed)
                        }
                        Caller.FX_THREAD -> onFx {
                            engine.seekTo(case.seekToMs)
                            engine.setSpeed(case.speed)
                        }
                    }
                    advanceBy(2_500)

                    assertTrue(!composed.hazards.frozen, "the decoder froze\n${native.log.tail()}")
                    assertEquals(
                        case.speed.toDouble(), composed.hazards.effectiveRate,
                        "the speed never reached the decoder\n${native.log}",
                    )
                    val seek: NativeCallEntry = native.log.calls(NativeCall.SEEK).last { it.tMs >= seekAt }
                    val given: List<NativeCallEntry> =
                        native.log.calls(NativeCall.SET_RATE).filter { it.effective && it.tMs >= seek.tMs }
                    assertTrue(
                        given.all { it.tMs - seek.tMs >= HazardConfig.DEFAULT_SEEK_RUN_MS - ROUNDING_MS },
                        "a rate was written ${given.first().tMs - seek.tMs} ms after the seek, inside W-SEEK-RUN\n${native.log}",
                    )
                    val moved: Double = native.positionMs - case.seekToMs
                    assertTrue(moved >= 1_500.0, "the playhead moved only $moved ms in 2.5 s after the seek\n${native.log}")
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
    }

    private fun runAll(cases: List<Case>) {
        val failures: List<String> = cases.mapNotNull { run(it) }
        assertEquals(emptyList(), failures, "${failures.size} of ${cases.size} cases failed")
    }

    @Test
    fun aSpeedGivenRightAfterASeekToTheMiddleOfAClipResumedAMomentAgoIsNeverWrittenInsideTheSeekWindow() {
        runAll(cases(1_500L))
    }

    @Test
    fun aSpeedGivenRightAfterAReplayOfAClipResumedAMomentAgoIsNeverWrittenInsideTheSeekWindow() {
        runAll(cases(0L))
    }

    /** The control: the FRESH period (600 ms) is over, so the D2-PF1 branch already takes the speed. Green on the old code. */
    @Test
    fun aSpeedGivenRightAfterASeekOfAClipPlayingForOverAFreshPeriodIsNeverWrittenInsideTheSeekWindow() {
        val control: List<Case> = listOf(1_500L, 0L).flatMap { seekTo ->
            listOf(2f, 3f).map { Case(seekTo, 1_000L, 0L, it, Caller.OTHER_THREAD) }
        }
        val failures: List<String> = control.mapNotNull { run(it) }
        assertEquals(emptyList(), failures, "${failures.size} of ${control.size} control cases failed")
    }
}
