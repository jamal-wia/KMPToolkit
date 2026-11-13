package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2 round 13, Q2: the `MovementGauge` of `FxPlayback` against a straight reference written from its KDoc, over seeded
 * random tick sequences and a set of boundary cases. The gauge is a private nested class, so it is reached by reflection
 * (the test owns no production change); no wall clock is read, every instant is a number the sequence makes up.
 *
 * The invariants taken from the KDoc:
 *  1. the first tick after a reset becomes the anchor and answers false, whatever it reads;
 *  2. a tick that reports the playhead behind its anchor, or ahead of it by more than
 *     `elapsed * max(1.0, rateBound) + 50 ms`, becomes the new anchor (position and time) and answers false;
 *  3. any other tick answers `moved >= need`, and does not move the anchor;
 *  4. `reset()` forgets the anchor.
 */
class MovementGaugeR13ModelTest {

    private val gaugeClass: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.video.player.javafx.FxPlayback\$MovementGauge")
    private val constructor: Constructor<*> = gaugeClass.getDeclaredConstructor().apply { isAccessible = true }
    private val movedMethod: Method = gaugeClass.getDeclaredMethod(
        "moved", java.lang.Double.TYPE, java.lang.Long.TYPE, java.lang.Double.TYPE, java.lang.Double.TYPE,
    ).apply { isAccessible = true }
    private val resetMethod: Method = gaugeClass.getDeclaredMethod("reset").apply { isAccessible = true }

    private inner class Real {
        val gauge: Any = constructor.newInstance()
        fun moved(position: Double, nowNanos: Long, rateBound: Double, needMs: Double): Boolean =
            movedMethod.invoke(gauge, position, nowNanos, rateBound, needMs) as Boolean

        fun reset() {
            resetMethod.invoke(gauge)
        }
    }

    /** The KDoc, as a history: every tick since the reset is kept and the anchor is an index into it. */
    private class Reference {
        private class Tick(val position: Double, val nanos: Long)

        private val ticks: MutableList<Tick> = mutableListOf()
        private var anchor: Int = -1

        fun reset() {
            ticks.clear()
            anchor = -1
        }

        fun moved(position: Double, nowNanos: Long, rateBound: Double, needMs: Double): Boolean {
            if (anchor < 0) {
                ticks += Tick(position, nowNanos)
                anchor = 0
                return false
            }
            val from: Tick = ticks[anchor]
            val allowedMs: Double = (nowNanos - from.nanos) / 1_000_000.0 * max(1.0, rateBound) + SLACK_MS
            val deltaMs: Double = position - from.position
            ticks += Tick(position, nowNanos)
            if (deltaMs < 0.0 || deltaMs > allowedMs) {
                anchor = ticks.size - 1
                return false
            }
            return deltaMs >= needMs
        }
    }

    private val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: DEFAULT_SEED

    private class Step(val kind: String, val position: Double, val nowNanos: Long, val rateBound: Double, val reset: Boolean)

    /**
     * One random world: a playhead of [clipMs] (looping, so it wraps) moving at [decoderRate] times the wall clock, and a
     * reader that reports it with the faults of the field: stale readings (a lag of up to 500 ms), seeks back and forward,
     * a wrap, a late reading that steps back, and resets the owner makes (a seek, a new speed).
     */
    private fun world(rnd: Random, clipMs: Double, decoderRate: Double, ticks: Int): List<Step> {
        val steps: MutableList<Step> = ArrayList(ticks)
        var truePosition: Double = rnd.nextDouble(0.0, clipMs)
        var nowNanos: Long = 1_000_000_000L + rnd.nextLong(0L, 1_000_000_000L)
        var rateBound: Double = RATES[rnd.nextInt(RATES.size)]
        repeat(ticks) {
            val dtMs: Double = rnd.nextDouble(1.0, 150.0)
            nowNanos += (dtMs * 1_000_000.0).toLong()
            truePosition += dtMs * decoderRate
            var kind = "play"
            while (truePosition >= clipMs) {
                truePosition -= clipMs
                kind = "wrap"
            }
            var reported: Double = truePosition
            var reset = false
            val roll: Int = rnd.nextInt(100)
            when {
                roll < 8 -> { // a stale reading: the playhead as it stood a while ago, possibly across a wrap
                    reported = truePosition - rnd.nextDouble(0.0, 500.0)
                    kind = "stale"
                }
                roll < 13 -> { // a seek back
                    truePosition = rnd.nextDouble(0.0, truePosition.coerceAtLeast(1.0))
                    reported = truePosition
                    kind = "seek-back"
                }
                roll < 18 -> { // a seek forward
                    truePosition = rnd.nextDouble(truePosition, clipMs)
                    reported = truePosition
                    kind = "seek-forward"
                }
                roll < 22 -> { // a late reading that steps back by a little
                    reported = truePosition - rnd.nextDouble(0.0, 80.0)
                    kind = "late"
                }
                roll < 26 -> { // an unchanged reading while time passes
                    reported = steps.lastOrNull()?.position ?: truePosition
                    kind = "same"
                }
                roll < 30 -> reset = true
                roll < 36 -> rateBound = RATES[rnd.nextInt(RATES.size)]
            }
            steps += Step(kind, reported, nowNanos, rateBound, reset)
        }
        return steps
    }

    @Test
    fun theGaugeAnswersLikeTheKDocReferenceOnRandomTickSequences() {
        val rnd = Random(seed)
        var trues = 0
        var total = 0
        for (decoderRate: Double in RATES) {
            for (clipMs: Double in listOf(120.0, 199.0, 600.0, 3_000.0, 60_000.0)) { // 120 and 199 are shorter than the 200 ms need
                repeat(SEQUENCES_PER_CELL) { index: Int ->
                    val steps: List<Step> = world(rnd, clipMs, decoderRate, TICKS)
                    val real = Real()
                    val reference = Reference()
                    for ((i: Int, step: Step) in steps.withIndex()) {
                        if (step.reset) {
                            real.reset()
                            reference.reset()
                        }
                        val expected: Boolean = reference.moved(step.position, step.nowNanos, step.rateBound, NEED_MS)
                        val actual: Boolean = real.moved(step.position, step.nowNanos, step.rateBound, NEED_MS)
                        total++
                        if (expected) trues++
                        assertEquals(
                            expected, actual,
                            "seed=$seed decoderRate=$decoderRate clipMs=$clipMs sequence=$index tick=$i kind=${step.kind} " +
                                "position=${step.position} rateBound=${step.rateBound}",
                        )
                    }
                }
            }
        }
        // The cells are not vacuous: a good share of the ticks answers true, and the sequences are not all re-anchors.
        assertTrue(trues > total / 50, "only $trues of $total ticks answered true: the sequences do not reach the true branch (seed=$seed)")
    }

    // ---- boundaries, one comparison each ----

    private fun gaugeAt(position: Double, nanos: Long = T0): Real = Real().also { assertFalse(it.moved(position, nanos, 1.0, NEED_MS), "the first tick anchors") }

    @Test
    fun aTickExactlyAtTheNeedAnswersTrue() {
        val gauge: Real = gaugeAt(1_000.0)
        assertTrue(gauge.moved(1_200.0, T0 + 200 * MS, 1.0, NEED_MS), "moved == need is enough")
    }

    @Test
    fun aTickJustUnderTheNeedAnswersFalseAndKeepsTheAnchor() {
        val gauge: Real = gaugeAt(1_000.0)
        assertFalse(gauge.moved(1_199.999, T0 + 200 * MS, 1.0, NEED_MS))
        assertTrue(gauge.moved(1_200.0, T0 + 210 * MS, 1.0, NEED_MS), "the anchor stayed at 1000, so 200 ms have been moved")
    }

    @Test
    fun aTickExactlyAtTheClockBoundPlusTheSlackIsPlayback() {
        val gauge: Real = gaugeAt(1_000.0)
        // elapsed 200 ms at max(1, 1.0): 200 + 50 = 250 ms allowed, moved exactly 250.
        assertTrue(gauge.moved(1_250.0, T0 + 200 * MS, 1.0, NEED_MS), "moved == bound is not a re-anchor")
    }

    @Test
    fun aTickJustOverTheClockBoundIsAReanchor() {
        val gauge: Real = gaugeAt(1_000.0)
        assertFalse(gauge.moved(1_250.5, T0 + 200 * MS, 1.0, NEED_MS), "just over the bound re-anchors")
        assertFalse(gauge.moved(1_449.0, T0 + 400 * MS, 1.0, NEED_MS), "199 ms from the new anchor")
        assertTrue(gauge.moved(1_450.5, T0 + 410 * MS, 1.0, NEED_MS), "200 ms from the new anchor, which is the re-anchored position")
    }

    @Test
    fun theBoundScalesWithTheRateBoundAndNeverBelowOne() {
        for ((rateBound: Double, allowed: Double) in listOf(0.25 to 250.0, 1.0 to 250.0, 2.0 to 450.0, 4.0 to 850.0, 8.0 to 1_650.0)) {
            val gauge: Real = gaugeAt(10_000.0)
            assertTrue(gauge.moved(10_000.0 + allowed, T0 + 200 * MS, rateBound, NEED_MS), "rateBound=$rateBound allows $allowed ms in 200 ms")
            val over: Real = gaugeAt(10_000.0)
            assertFalse(over.moved(10_000.0 + allowed + 0.001, T0 + 200 * MS, rateBound, NEED_MS), "rateBound=$rateBound: just over $allowed ms re-anchors")
        }
    }

    @Test
    fun anUnchangedReadingIsNotBackwardAndKeepsTheAnchorTime() {
        val gauge: Real = gaugeAt(1_000.0)
        assertFalse(gauge.moved(1_000.0, T0 + 1_000 * MS, 1.0, NEED_MS), "no movement, not backward")
        // Measured from the ORIGINAL anchor time (1100 ms elapsed, 1150 allowed); a re-anchor at t=1000 would allow only 150.
        assertTrue(gauge.moved(1_400.0, T0 + 1_100 * MS, 1.0, NEED_MS))
    }

    @Test
    fun aBackwardTickReanchorsAtTheNewPositionAndTime() {
        val gauge: Real = gaugeAt(1_000.0)
        assertFalse(gauge.moved(999.5, T0 + 100 * MS, 1.0, NEED_MS), "behind the anchor")
        // The new anchor is (999.5, 100 ms): 200 ms from it at t=300 ms is exactly the need and exactly in the bound.
        assertTrue(gauge.moved(1_199.5, T0 + 300 * MS, 1.0, NEED_MS))
    }

    @Test
    fun resetForgetsTheAnchorAndTheNextTickIsTheAnchorAgain() {
        val gauge: Real = gaugeAt(1_000.0)
        assertTrue(gauge.moved(1_200.0, T0 + 200 * MS, 1.0, NEED_MS))
        gauge.reset()
        assertFalse(gauge.moved(1_200.0, T0 + 210 * MS, 1.0, NEED_MS), "the tick after a reset anchors")
        assertFalse(gauge.moved(1_300.0, T0 + 310 * MS, 1.0, NEED_MS))
        assertTrue(gauge.moved(1_400.0, T0 + 410 * MS, 1.0, NEED_MS))
    }

    @Test
    fun theFirstTickAnchorsWhateverItReadsAndTheTimeIsMeasuredFromIt() {
        for (position: Double in listOf(0.0, -5.0, 1.0e9)) {
            val gauge: Real = gaugeAt(position, T0 + 7 * MS)
            assertTrue(gauge.moved(position + 200.0, T0 + 207 * MS, 1.0, NEED_MS), "position=$position")
        }
    }

    @Test
    fun aClockThatWrapsDoesNotBreakTheBound() {
        // System.nanoTime has no zero: a difference across Long.MAX_VALUE is still the elapsed time.
        val start: Long = Long.MAX_VALUE - 100 * MS
        val gauge: Real = gaugeAt(1_000.0, start)
        assertTrue(gauge.moved(1_200.0, start + 200 * MS, 1.0, NEED_MS), "the wrapped clock reads 200 ms elapsed")
    }

    private companion object {
        const val SLACK_MS: Double = 50.0
        const val NEED_MS: Double = 200.0
        const val DEFAULT_SEED: Long = 13_013L
        const val SEQUENCES_PER_CELL: Int = 12
        const val TICKS: Int = 300
        const val T0: Long = 5_000_000_000L
        const val MS: Long = 1_000_000L
        val RATES: List<Double> = listOf(0.25, 1.0, 2.0, 4.0, 8.0)
    }
}
