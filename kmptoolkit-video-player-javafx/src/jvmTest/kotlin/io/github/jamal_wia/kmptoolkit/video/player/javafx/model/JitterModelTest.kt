package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host jitter (`VirtualFxHost(jitterMaxMs = 17.0)`, a `PauseTransition` that fires on a pulse) must not break the
 * scripted player's own timers: a full player on `composeAll` runs start, pause, seek, speed and stop scripts under
 * the jitter with no exception and a green hazard monitor (the end-of-test check of `fxModel` and the explicit one).
 *
 * Found by J10: the jittered fire of the picture part's pulse landed past the next point of its nominal grid, and
 * the next delay came out negative (`delayMillis must be finite and not negative`). Virtual time only, no JavaFX,
 * no real media; every random choice is from a `Random(seed)` and every failure message names the seed.
 */
class JitterModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend)

    private fun FxModelScope.jitteredEngine(body: Rig.() -> Unit) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        load(engine, VideoSource.Asset(TestClip.ASSET))
        try {
            Rig(engine, backend).body()
        } finally {
            engine.release()
            advanceBy(50)
        }
    }

    @Test
    fun aPlainStartAdvancePauseAdvanceRunsUnderTheJitterOfTheReportedSeed() = fxModel(seed = REPORTED_SEED, jitterMaxMs = JITTER_MAX_MS) {
        jitteredEngine {
            engine.start()
            advanceBy(1_000)
            engine.pause()
            advanceBy(1_000)

            backend.last.monitor.assertNoViolation()
            assertTrue(host.tasksRun > 0, "the host ran nothing ($host)")
        }
    }

    @Test
    fun thePulseOfAPlayingPlayerKeepsRunningUnderTheJitter() = fxModel(seed = REPORTED_SEED, jitterMaxMs = JITTER_MAX_MS) {
        jitteredEngine {
            engine.start()
            advanceBy(2_000)

            // A 60 Hz pulse over two seconds is about 120 ticks; the jitter must not lose them or stop the pulse.
            val pulses: Int = backend.last.picture.pulsesDelivered
            assertTrue(pulses in 100..130, "$pulses pulses in 2000 ms ($host)")
        }
    }

    @Test
    fun twoHundredSeedsOfRandomScriptsRunWithoutAnExceptionUnderTheJitter() {
        val seeds: List<Long> = listOf(REPORTED_SEED) + Random(SWEEP_BASE_SEED).let { rnd: Random -> List(SWEEP_SIZE) { rnd.nextLong() } }
        val failures: List<String> = seeds.mapNotNull { seed: Long -> runSeed(seed) }

        assertEquals(emptyList(), failures, "${failures.size} of ${seeds.size} seeds failed")
    }

    private fun runSeed(seed: Long): String? {
        try {
            fxModel(seed = seed, jitterMaxMs = JITTER_MAX_MS) {
                jitteredEngine {
                    val rnd = Random(seed)
                    repeat(OPS_PER_SEED) {
                        when (rnd.nextInt(5)) {
                            0 -> engine.start()
                            1 -> engine.pause()
                            2 -> engine.seekTo(rnd.nextLong(0L, 3_000L))
                            3 -> engine.setSpeed(SPEEDS[rnd.nextInt(SPEEDS.size)])
                            else -> engine.start()
                        }
                        advanceBy(rnd.nextLong(0L, 400L))
                    }
                    backend.last.monitor.assertNoViolation()
                }
            }
        } catch (t: Throwable) {
            return "[seed $seed] ${t::class.simpleName}: ${t.message?.take(MESSAGE_LIMIT)}"
        }
        return null
    }

    private companion object {
        /** The seed of the J10 report: `start(); advanceBy(1000); pause(); advanceBy(1000)` failed on it. */
        const val REPORTED_SEED: Long = -8890902686609799608L
        const val SWEEP_BASE_SEED: Long = 20261113L
        const val SWEEP_SIZE: Int = 200
        const val OPS_PER_SEED: Int = 8
        const val JITTER_MAX_MS: Double = 17.0
        const val MESSAGE_LIMIT: Int = 300
        val SPEEDS: List<Float> = listOf(0.5f, 1.5f, 2f, 3f)
    }
}
