package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The time and seek part of the scripted native player, one group per behaviour of design section 4.1: B3 (the
 * sampled and published playhead), B5 (key-frame seeks), B7 (the end without looping), plus the `LOAD` profile and
 * the hooks the hazard part uses. Every wait is virtual; the checked-in clip's numbers are the defaults
 * (3000 ms long, key frames 1000 ms apart, ready after 50 ms, status lag 30 ms).
 */
class TimeModelTest {

    private class Rig(
        val scope: FxModelScope,
        val player: ScriptedNativePlayer,
        val time: TimeModel,
        val events: RecordingNativeEvents,
        val loop: LoopModel? = null,
    ) {
        fun advance(ms: Long) = scope.advanceBy(ms)

        fun <T> onFx(block: () -> T): T = scope.onFx(block)

        fun now(): Double = scope.host.elapsedNanos / 1_000_000.0

        /** Millisecond times of the `currentTime` events so far. */
        fun tickTimes(): List<Double> = events.events.filter { it.name == "currentTime" }.map { it.tMs }

        fun count(name: String): Int = events.names.count { it == name }
    }

    /** A player with a time part, built the way `FxPlayback` builds one, not yet ready. */
    private fun FxModelScope.rig(
        script: TimeScript = TimeScript(),
        clip: ClipModel = ClipModel(),
        status: StatusScript = StatusScript(),
        withLoop: Boolean = false,
    ): Rig {
        val events = RecordingNativeEvents(host)
        val (player: ScriptedNativePlayer, time: TimeModel) = onFx {
            val p = ScriptedNativePlayer(host, clip, status)
            p.setEvents(events)
            val loop: LoopModel? = if (withLoop) LoopModel(p).also { p.loop = it } else null
            p to TimeModel(p, script, loop)
        }
        allowUnwatched(player, "part self-test rig: the time part is asserted directly, no hazard part is wanted")
        return Rig(this, player, time, events, player.loop as? LoopModel)
    }

    /** A rig advanced to the ready edge (t=50) with the event record cleared. */
    private fun FxModelScope.readyRig(
        script: TimeScript = TimeScript(),
        clip: ClipModel = ClipModel(),
    ): Rig = rig(script, clip).also {
        it.advance(50)
        it.events.clear()
    }

    // -- the part fills its slot --

    @Test
    fun theTimeModelInstallsItselfInTheTimeSlot() = fxModel {
        val rig: Rig = rig()

        assertTrue(rig.player.time === rig.time)
    }

    // -- B3: the playhead is sampled and published --

    @Test
    fun aPausedPlayerDoesNotMoveAndPublishesNothing() = fxModel {
        val rig: Rig = readyRig()

        rig.advance(5_000)

        assertEquals(0.0, rig.player.currentTimeMs)
        assertEquals(0, rig.count("currentTime"))
        assertEquals(0, rig.scope.host.pendingCount)
    }

    @Test
    fun aPlayingPlayerPublishesTheNativePositionEvery100Ms() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(299)

        // play() at t=50: samples at 150, 250, 350 carry 100, 200, 300
        assertEquals(listOf(150.0, 250.0), rig.tickTimes())
        assertEquals(200.0, rig.player.currentTimeMs)
        rig.advance(1)
        assertEquals(listOf(150.0, 250.0, 350.0), rig.tickTimes())
        assertEquals(300.0, rig.player.currentTimeMs)
    }

    @Test
    fun thePublishedPositionHoldsTheLastSampleBetweenSamples() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(100) // t=150: the first sample
        assertEquals(100.0, rig.player.currentTimeMs)
        rig.advance(99) // t=249: one before the next sample

        assertEquals(100.0, rig.player.currentTimeMs)
        assertEquals(199.0, rig.time.nativePositionMs)
    }

    @Test
    fun theSampleIntervalIsConfigurable() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { sampleIntervalMs = 50.0 })
        rig.onFx { rig.player.play() }

        rig.advance(150)

        assertEquals(listOf(100.0, 150.0, 200.0), rig.tickTimes())
        assertEquals(150.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSampleIsPublishedByASeparateQueuedBlockNotAtTheMomentItIsTaken() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(99) // t=149: the status landing at t=80 is behind us, the first sample is next
        assertTrue(rig.scope.step(), "the sample tick of t=150")
        assertEquals(0.0, rig.player.currentTimeMs, "taken, not published yet")
        assertTrue(rig.scope.step(), "the publication of that sample")

        assertEquals(100.0, rig.player.currentTimeMs)
    }

    @Test
    fun theListenerRunsOnTheFxThreadAndOnlyForAChangedValue() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.seek(1_540.0) }
        rig.advance(0)
        rig.onFx { rig.player.seek(1_999.0) } // lands on the same key frame: the same value
        rig.advance(0)

        assertEquals(1, rig.count("currentTime"))
        assertTrue(rig.events.events.single { it.name == "currentTime" }.onFxThread)
    }

    @Test
    fun aPublishDelayHoldsASampleInTheQueue() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { publishDelayMs = 70.0 })
        rig.onFx { rig.player.play() }

        rig.advance(169) // t=219
        assertEquals(0.0, rig.player.currentTimeMs)
        rig.advance(1)

        // the sample taken at t=150 carries 100 and lands at t=220
        assertEquals(100.0, rig.player.currentTimeMs)
        assertEquals(listOf(220.0), rig.tickTimes())
    }

    @Test
    fun aSampleTakenBeforeASeekLandsAfterItAndPutsTheOldPositionBack() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { publishDelayMs = 70.0 })
        rig.onFx { rig.player.play() }
        rig.advance(130) // t=180: the sample of t=150 (value 100) is in flight until t=220
        rig.onFx { rig.player.seek(1_540.0) } // lands on 1000 at t=180; its own sample lands at t=250

        rig.advance(39)
        assertEquals(0.0, rig.player.currentTimeMs)
        rig.advance(1) // t=220
        assertEquals(100.0, rig.player.currentTimeMs, "the stale sample is published after the seek")
        rig.advance(30) // t=250
        assertEquals(1_000.0, rig.player.currentTimeMs)
        rig.advance(70) // t=320: the sample of t=250 carries 1070
        assertEquals(1_070.0, rig.player.currentTimeMs)
    }

    // -- B3: the LOAD profile --

    @Test
    fun theLoadProfileDrawsDelaysInTheWindowAndDropsAboutATenth() {
        val script: TimeScript = TimeScript().load(seed = 7L)
        val draws: List<Double?> = List(10_000) { script.nextPublishDelayMs() }

        val dropped: Int = draws.count { it == null }
        assertTrue(dropped in 800..1_200, "dropped $dropped of 10000, expected about a tenth (seed 7)")
        val kept: List<Double> = draws.filterNotNull()
        assertTrue(kept.all { it >= 50.0 && it <= 400.0 }, "a delay left the 50-400 ms window (seed 7)")
        assertTrue(kept.minOrNull()!! < 60.0 && kept.maxOrNull()!! > 390.0, "the delays do not cover the window (seed 7)")
    }

    @Test
    fun theLoadProfileIsDeterministicPerSeed() {
        val a: List<Double?> = TimeScript().load(seed = 11L).let { s -> List(200) { s.nextPublishDelayMs() } }
        val b: List<Double?> = TimeScript().load(seed = 11L).let { s -> List(200) { s.nextPublishDelayMs() } }
        val c: List<Double?> = TimeScript().load(seed = 12L).let { s -> List(200) { s.nextPublishDelayMs() } }

        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun aCopyOfALoadedScriptRestartsFromTheSeed() {
        val script: TimeScript = TimeScript().load(seed = 5L)
        script.nextPublishDelayMs()
        script.nextPublishDelayMs()

        val copy: TimeScript = script.copy()

        assertTrue(copy.isLoaded)
        assertEquals(TimeScript().load(seed = 5L).nextPublishDelayMs(), copy.nextPublishDelayMs())
    }

    @Test
    fun underLoadNoSampleIsPublishedBeforeItsMinimumDelayAndSomeAreDropped() = fxModel {
        val clip = ClipModel(durationMs = 1_000_000.0)
        val rig: Rig = readyRig(TimeScript().load(seed = 3L), clip)
        rig.onFx { rig.player.play() }

        rig.advance(100_000)

        assertEquals(1_000, rig.time.samplesTaken, "one sample per 100 ms of 100 s (seed 3)")
        assertTrue(rig.time.samplesDropped in 50..150, "samples dropped ${rig.time.samplesDropped} of ${rig.time.samplesTaken} (seed 3)")
        assertTrue(rig.tickTimes().first() >= 200.0, "the first sample of t=150 cannot land before t=200 (seed 3)")
    }

    @Test
    fun theSameSeedGivesTheSameEventTraceUnderLoad() {
        fun trace(): List<Pair<String, Double>> {
            val out: MutableList<Pair<String, Double>> = mutableListOf()
            fxModel(seed = 99L) {
                val rig: Rig = readyRig(TimeScript().load(seed = 99L))
                rig.onFx { rig.player.play() }
                rig.advance(2_000)
                out += rig.events.events.map { it.name to it.tMs }
            }
            return out
        }

        assertEquals(trace(), trace())
    }

    /**
     * The published sequence of a loaded script without a `read` argument (AT_TICK, the default): every change of the
     * published playhead as (virtual ms, value), over a play, a seek to a key frame and the rest of the clip.
     */
    private fun publishedSequence(seed: Long, script: TimeScript): List<Pair<Double, Double>> {
        val out: MutableList<Pair<Double, Double>> = mutableListOf()
        fxModel(seed = seed) {
            val rig: Rig = readyRig(script)
            rig.onFx { rig.player.play() }
            var last: Double = rig.player.currentTimeMs
            for (step in 0 until 2_000) {
                if (step == 700) rig.onFx { rig.player.seek(1_540.0) }
                rig.advance(1)
                if (rig.player.currentTimeMs != last) {
                    last = rig.player.currentTimeMs
                    out += rig.now() to last
                }
            }
        }
        return out
    }

    @Test
    fun theDefaultReadOfTheLoadProfileIsAtTheTickAndItsPublishedSequenceIsPinned() {
        assertEquals(
            listOf(
                249.0 to 100.0, 458.0 to 300.0, 515.0 to 400.0, 604.0 to 200.0, 771.0 to 600.0, 850.0 to 500.0, 1025.0 to 1200.0,
                1063.0 to 1100.0, 1078.0 to 1000.0, 1118.0 to 700.0, 1260.0 to 1300.0, 1516.0 to 1500.0, 1542.0 to 1400.0,
                1792.0 to 1700.0, 1864.0 to 1800.0, 1924.0 to 1900.0, 2003.0 to 2000.0, 2044.0 to 2100.0,
            ),
            publishedSequence(99L, TimeScript().load(seed = 99L)),
            "seed 99",
        )
        assertEquals(
            publishedSequence(7L, TimeScript().load(seed = 7L)),
            publishedSequence(7L, TimeScript().load(seed = 7L, read = SampleRead.AT_TICK)),
            "an explicit AT_TICK is the default",
        )
        assertEquals(
            listOf(
                392.0 to 100.0, 572.0 to 400.0, 608.0 to 200.0, 872.0 to 500.0, 909.0 to 1000.0, 943.0 to 600.0, 1009.0 to 1100.0,
                1047.0 to 700.0, 1205.0 to 1200.0, 1433.0 to 1400.0, 1434.0 to 1300.0, 1448.0 to 1500.0, 1547.0 to 1700.0,
                1715.0 to 1900.0, 1789.0 to 1800.0, 2041.0 to 2100.0, 2042.0 to 2000.0,
            ),
            publishedSequence(7L, TimeScript().load(seed = 7L)),
            "seed 7",
        )
    }

    // -- B3: the LOAD profile reads the playhead when the publication runs (SampleRead.AT_PUBLISH, task J-load-fresh) --

    /** The values published after a seek made at [seekAtMs] of playing, one entry per change, polled every virtual ms. */
    private fun valuesPublishedAfterASeek(seed: Long, read: SampleRead): List<Double> {
        val out: MutableList<Double> = mutableListOf()
        fxModel(seed = seed) {
            val rig: Rig = readyRig(TimeScript().load(seed = seed, read = read))
            rig.onFx { rig.player.play() }
            rig.advance(300L + (seed % 7L) * 13L)
            rig.onFx { rig.player.seek(2_000.0) }
            var last: Double = rig.player.currentTimeMs
            for (step in 0 until 600) {
                rig.advance(1)
                if (rig.player.currentTimeMs != last) {
                    last = rig.player.currentTimeMs
                    out += last
                }
            }
        }
        return out
    }

    @Test
    fun underAtPublishNoValuePublishedAfterTheSeekBlockRanPredatesTheSeek() {
        val offenders: List<String> = (1L..200L).mapNotNull { seed ->
            val low: Double? = valuesPublishedAfterASeek(seed, SampleRead.AT_PUBLISH).filter { it < 2_000.0 }.minOrNull()
            low?.let { "seed $seed published $it after a seek to 2000" }
        }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun underAtTickAValuePublishedAfterTheSeekBlockRanCanPredateTheSeekSoTheAtPublishTestBites() {
        val stale: Int = (1L..200L).count { seed ->
            valuesPublishedAfterASeek(seed, SampleRead.AT_TICK).any { it < 2_000.0 }
        }

        assertTrue(stale > 20, "only $stale of 200 seeds published a pre-seek value after the seek under AT_TICK")
    }

    @Test
    fun aPublicationQueuedBeforeTheSeekBlockStillRunsFirstAndCarriesTheOldPosition() = fxModel {
        val rig: Rig = readyRig(TimeScript().load(seed = 1L, minDelayMs = 0.0, maxDelayMs = 0.0, dropFraction = 0.0, read = SampleRead.AT_PUBLISH))
        rig.onFx { rig.player.play() }
        rig.advance(430)
        val taken: Int = rig.time.samplesTaken
        var steps = 0
        while (rig.time.samplesTaken == taken) check(host.step() && steps++ < 100) { "no sample was taken ($host)" }
        assertTrue(host.pendingCount > 0, "the publication of the sample is not queued ($host)")
        val old: Double = rig.time.nativePositionMs
        assertTrue(old in 1.0..1_999.0, "the premise: the playhead is before the seek target, at $old")

        // From the test thread the seek block queues behind the publication already waiting, as on a busy FX thread.
        host.post { rig.player.seek(2_000.0) }
        rig.advance(0)

        // Two publications ran at this instant: the one queued before the seek block, then the sample the seek itself took.
        val ran: List<TimeModel.Publication> = rig.time.publications.filter { it.tickMs == rig.now() }
        assertEquals(2, ran.size, "the publications of this instant: ${rig.time.publications.map { "${it.index}@${it.runMs}=${it.value}" }}")
        assertEquals(old, ran[0].value, "the publication queued ahead of the seek block carries the old position")
        assertEquals(2_000.0, ran[1].value, "the sample the seek took carries the new position")
        assertEquals(ran[0].index + 1, ran[1].index, "the two ran in the posting order")
        assertEquals(2_000.0, rig.player.currentTimeMs)
        rig.advance(300)
        assertTrue(rig.player.currentTimeMs >= 2_000.0, "a later publication carries the new position, ${rig.player.currentTimeMs}")
    }

    private fun publicationsOf(seed: Long, jitterMaxMs: Double = 0.0): List<TimeModel.Publication> {
        var out: List<TimeModel.Publication> = emptyList()
        fxModel(seed = seed, jitterMaxMs = jitterMaxMs) {
            val rig: Rig = readyRig(TimeScript().load(seed = seed, read = SampleRead.AT_PUBLISH), ClipModel(durationMs = 1_000_000.0))
            rig.onFx { rig.player.play() }
            rig.advance(20_000)
            rig.advance(1_000) // the last publications of the window run
            out = rig.time.publications
        }
        return out
    }

    @Test
    fun underAtPublishPublicationsRunInThePostingOrderAtTheLaterOfThePreviousRunAndTheirOwnDelay() {
        var clamped = 0
        for (seed in 1L..200L) {
            val log: List<TimeModel.Publication> = publicationsOf(seed)
            assertTrue(log.size > 100, "seed $seed: only ${log.size} publications ran")
            log.forEachIndexed { i: Int, p: TimeModel.Publication ->
                assertEquals(i, p.index, "seed $seed: publication $i ran out of the posting order")
                val own: Double = p.tickMs + p.delayMs
                val expected: Double = if (i == 0) own else maxOf(log[i - 1].runMs, own)
                assertEquals(expected, p.runMs, 1e-6, "seed $seed publication $i: ran at ${p.runMs}, tick ${p.tickMs} delay ${p.delayMs}")
                if (p.runMs > own + 1e-6) clamped++
            }
        }
        assertTrue(clamped > 100, "only $clamped publications were held back behind an earlier one: the rule was never exercised")
    }

    @Test
    fun underAtPublishAJitteredHostStillRunsThePublicationsInThePostingOrderAndNeverEarly() {
        for (seed in 1L..50L) {
            val log: List<TimeModel.Publication> = publicationsOf(seed, jitterMaxMs = 17.0)
            log.forEachIndexed { i: Int, p: TimeModel.Publication ->
                assertEquals(i, p.index, "seed $seed: publication $i ran out of the posting order")
                assertTrue(p.runMs >= p.tickMs + p.delayMs - 1e-6, "seed $seed publication $i ran early: ${p.runMs} < ${p.tickMs + p.delayMs}")
                if (i > 0) assertTrue(p.runMs >= log[i - 1].runMs, "seed $seed publication $i ran before the one ahead of it")
            }
        }
    }

    @Test
    fun theDropFractionAndTheDrawsAreTheSameUnderAtPublishAsUnderAtTick() = fxModel {
        val clip = ClipModel(durationMs = 1_000_000.0)
        val rig: Rig = readyRig(TimeScript().load(seed = 3L, read = SampleRead.AT_PUBLISH), clip)
        rig.onFx { rig.player.play() }

        rig.advance(100_000)

        assertEquals(1_000, rig.time.samplesTaken, "one sample per 100 ms of 100 s (seed 3)")
        assertTrue(rig.time.samplesDropped in 50..150, "samples dropped ${rig.time.samplesDropped} of ${rig.time.samplesTaken} (seed 3)")
        val atTick: TimeScript = TimeScript().load(seed = 3L)
        val atPublish: TimeScript = TimeScript().load(seed = 3L, read = SampleRead.AT_PUBLISH)
        assertEquals(List(500) { atTick.nextPublishDelayMs() }, List(500) { atPublish.nextPublishDelayMs() })
        val posted: Int = rig.time.samplesTaken - rig.time.samplesDropped
        assertTrue(rig.time.publications.size in (posted - 10)..posted, "${rig.time.publications.size} publications ran of $posted posted (seed 3)")
    }

    @Test
    fun theReadOptionDefaultsToAtTickAndIsKeptByACopy() {
        assertEquals(SampleRead.AT_TICK, TimeScript().sampleRead)
        assertEquals(SampleRead.AT_TICK, TimeScript().load(seed = 1L).sampleRead)
        assertEquals(SampleRead.AT_PUBLISH, TimeScript().load(seed = 1L, read = SampleRead.AT_PUBLISH).copy().sampleRead)
        assertEquals(SampleRead.AT_TICK, TimeScript().apply { publishDelayMs = 5.0 }.sampleRead)
    }

    // -- B5: seeks land on key frames --

    @Test
    fun aSeekLandsOnTheKeyFrameAtOrBeforeTheTarget() = fxModel {
        val rig: Rig = readyRig()
        val landed: MutableList<Double> = mutableListOf()

        for (target in listOf(1_540.0, 999.0, 1_000.0, 2_999.0, 1_001.0)) {
            rig.onFx { rig.player.seek(target) }
            rig.advance(0)
            landed += rig.player.currentTimeMs
        }

        assertEquals(listOf(1_000.0, 0.0, 1_000.0, 2_000.0, 1_000.0), landed)
    }

    @Test
    fun aSeekBeyondTheDurationLandsOnTheLastKeyFrameThatFitsAndNeverBeyond() = fxModel {
        val rig: Rig = readyRig(clip = ClipModel(durationMs = 2_500.0))

        rig.onFx { rig.player.seek(9_999.0) }
        rig.advance(0)

        assertEquals(2_000.0, rig.player.currentTimeMs)
        rig.onFx { rig.player.seek(-5.0) }
        rig.advance(0)
        assertEquals(0.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSeekWithinTheStartWindowLandsOnZeroWhateverTheKeyFrames() = fxModel {
        val rig: Rig = readyRig(clip = ClipModel(keyFrameIntervalMs = 40.0))

        rig.onFx { rig.player.seek(100.0) }
        rig.advance(0)
        assertEquals(0.0, rig.player.currentTimeMs, "100 ms is inside the start window")
        rig.onFx { rig.player.seek(101.0) }
        rig.advance(0)
        assertEquals(80.0, rig.player.currentTimeMs, "101 ms is outside it: the key frame at 80")
    }

    @Test
    fun theStartWindowIsConfigurable() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { nearStartMs = 0.0 }, ClipModel(keyFrameIntervalMs = 40.0))

        rig.onFx { rig.player.seek(30.0) }
        rig.advance(0)

        assertEquals(0.0, rig.player.currentTimeMs, "the key frame at or before 30 is 0 anyway")
        rig.onFx { rig.player.seek(90.0) }
        rig.advance(0)
        assertEquals(80.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSeekOnAPausedPlayerPublishesTheLandingThroughTheQueueNotAtOnce() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx {
            rig.player.seek(1_540.0)
            assertEquals(0.0, rig.player.currentTimeMs, "not published inside the call")
        }
        assertEquals(0, rig.count("currentTime"))
        rig.advance(0)

        assertEquals(1_000.0, rig.player.currentTimeMs)
        assertEquals(1, rig.count("currentTime"))
        assertEquals(1_000.0, rig.time.nativePositionMs)
    }

    @Test
    fun aSeekToTheValueAlreadyPublishedFiresNoEvent() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.seek(1_540.0) }
        rig.advance(0)
        rig.events.clear()

        rig.onFx { rig.player.seek(1_900.0) }
        rig.advance(0)

        assertEquals(0, rig.count("currentTime"))
        assertEquals(1_000.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSeekWhilePlayingContinuesFromTheLanding() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(100) // t=150: the first sample is 100
        rig.onFx { rig.player.seek(1_540.0) }

        rig.advance(100) // t=250: the grid sample carries 1000 + 100

        assertEquals(1_100.0, rig.player.currentTimeMs)
        assertEquals(1_100.0, rig.time.nativePositionMs)
    }

    @Test
    fun aSeekBeforeReadyAndASeekToNaNDoNothing() = fxModel {
        val rig: Rig = rig()
        rig.onFx { rig.player.seek(1_540.0) }
        rig.advance(50)
        assertEquals(0.0, rig.time.nativePositionMs)

        rig.onFx { rig.player.seek(Double.NaN) }
        rig.advance(0)

        assertEquals(0.0, rig.player.currentTimeMs)
        assertEquals(0.0, rig.time.nativePositionMs)
        assertEquals(0, rig.count("currentTime"))
    }

    // -- the decoder: play, pause, rate, ready --

    @Test
    fun aPlayBeforeReadyStartsTheDecoderAtReady() = fxModel {
        val rig: Rig = rig()
        rig.onFx { rig.player.play() }

        rig.advance(49)
        assertEquals(0.0, rig.time.nativePositionMs)
        rig.advance(1) // ready at t=50
        rig.advance(100)

        assertEquals(100.0, rig.time.nativePositionMs)
        assertEquals(100.0, rig.player.currentTimeMs)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aPauseStopsTheDecoderAtTheCommandWhileTheStatusLags() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(250)
        rig.onFx { rig.player.pause() }

        rig.advance(10)
        assertEquals(NativeStatus.PLAYING, rig.player.status, "the status lags a pause")
        rig.advance(5_000)

        assertEquals(250.0, rig.time.nativePositionMs)
        assertEquals(200.0, rig.player.currentTimeMs, "the last published sample, up to 100 ms behind")
        assertEquals(0, rig.scope.host.pendingCount)
    }

    @Test
    fun aResumeGoesOnFromWhereThePauseStoodAndRestartsTheSampleGrid() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(250)
        rig.onFx { rig.player.pause() }
        rig.advance(1_000)
        rig.events.clear()
        rig.onFx { rig.player.play() }

        rig.advance(100)

        assertEquals(350.0, rig.player.currentTimeMs)
        assertEquals(listOf(1_400.0), rig.tickTimes())
    }

    @Test
    fun aStalePlayingStatusLandingAfterAPauseDoesNotRestartTheDecoder() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(10)
        rig.onFx { rig.player.pause() } // t=60; the PLAYING status lands at t=80, behind the pause command

        rig.advance(1_000)

        assertEquals(10.0, rig.time.nativePositionMs)
        assertEquals(NativeStatus.PAUSED, rig.player.status)
    }

    @Test
    fun theDecoderFollowsTheRatePropertyFromAPlay() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx {
            rig.player.rate = 2.0
            rig.player.play()
        }

        rig.advance(100)

        assertEquals(200.0, rig.time.nativePositionMs)
        assertEquals(200.0, rig.player.currentTimeMs)
    }

    @Test
    fun aRateChangeWhilePlayingKeepsThePositionAndChangesTheSlope() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(200)
        rig.onFx { rig.player.rate = 2.0 }

        rig.advance(100)

        assertEquals(400.0, rig.time.nativePositionMs)
        assertEquals(400.0, rig.player.currentTimeMs)
    }

    @Test
    fun aRateWriteOfTheHeldValueDoesNotRestartAnything() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(100)
        val pending: Int = rig.scope.host.pendingCount

        rig.onFx { rig.player.rate = 1.0 }

        assertEquals(pending, rig.scope.host.pendingCount)
        assertEquals(1.0, rig.time.effectiveRate)
    }

    @Test
    fun withTheHazardPartAPlainPlayStartsTheDecoderAtOneWhateverThePropertySays() = fxModel {
        val rig: Rig = readyRig()
        val hazard = HazardModel(rig.scope.host)
        rig.onFx {
            rig.player.hazard = hazard
            rig.player.rate = 2.0
        }
        rig.advance(250) // outside the idle gap of 200 ms
        rig.onFx { rig.player.play() }

        rig.advance(100)
        assertEquals(1.0, rig.time.effectiveRate)
        assertEquals(100.0, rig.time.nativePositionMs)
        rig.onFx { rig.player.rate = 3.0 } // exactly 100 ms after the play: the window is closed
        rig.advance(100)

        assertEquals(3.0, rig.time.effectiveRate)
        assertEquals(400.0, rig.time.nativePositionMs)
        assertTrue(hazard.violations.isEmpty(), "violations: ${hazard.violations.map { it.describe() }}")
    }

    @Test
    fun aFrozenDecoderHoldsItsPositionPublishesTheHeldValueOnceAndNeverEnds() = fxModel {
        val rig: Rig = readyRig()
        val hazard = HazardModel(rig.scope.host)
        rig.onFx { rig.player.hazard = hazard }
        rig.onFx { rig.player.play() }
        rig.advance(10)
        rig.onFx { rig.player.rate = 2.0 } // 10 ms after a plain play: W-PLAY freezes the decoder

        rig.advance(10_000)

        assertTrue(hazard.frozen)
        assertEquals(10.0, rig.time.nativePositionMs)
        assertEquals(10.0, rig.player.currentTimeMs)
        assertEquals(1, rig.count("currentTime"), "the held value is published once, then the samples carry the same value")
        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertTrue(rig.time.isRunning && rig.time.isHeld)
    }

    @Test
    fun aFrozenDecoderThatRecoversOnPausePlayMovesAgainFromWhereItStood() = fxModel {
        val rig: Rig = readyRig()
        val hazard = HazardModel(rig.scope.host, HazardConfig(recovery = FreezeRecovery.PAUSE_PLAY))
        rig.onFx { rig.player.hazard = hazard }
        rig.onFx { rig.player.play() }
        rig.advance(10)
        rig.onFx { rig.player.rate = 2.0 }
        rig.advance(500)
        rig.onFx {
            rig.player.pause()
            rig.player.play()
        }

        rig.advance(100)

        assertFalse(hazard.frozen)
        assertEquals(110.0, rig.time.nativePositionMs)
    }

    @Test
    fun aStalledPlayerHoldsThePlayheadUntilItResumes() = fxModel {
        val rig: Rig = readyRig()
        val stall = StallModel(rig.player)
        rig.onFx { rig.player.loop = stall }
        rig.onFx { rig.player.play() }
        rig.advance(200) // t=250, native 200
        rig.onFx { stall.stallAt(0.0) }

        rig.advance(1_000)

        assertEquals(NativeStatus.STALLED, rig.player.status)
        assertEquals(200.0, rig.time.nativePositionMs)
        assertEquals(0, rig.count("endOfMedia"))
        rig.onFx { stall.resumeAt(0.0) }
        rig.advance(100)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertEquals(300.0, rig.time.nativePositionMs)
    }

    @Test
    fun aLoopingSourceWrapsAtTheEndOfEveryCycleHoldsThePublishedTimeAndCatchesUp() = fxModel {
        val rig: Rig = rig(withLoop = true).also { it.advance(50) }
        val loop: LoopModel = checkNotNull(rig.loop)
        val time: TimeModel = rig.time
        rig.events.clear()
        rig.onFx {
            rig.player.cycleCount = LoopModel.INDEFINITE
            rig.player.play()
        }

        rig.advance(3_000) // t=3050: the end of the first cycle
        assertEquals(2, loop.cycle)
        assertEquals(1, rig.count("endOfMedia"), "one end per cycle")
        assertEquals(0, time.endsReached, "a wrap is not an end")
        assertEquals(0.0, rig.player.currentTimeMs)
        rig.advance(249) // t=3299: samples of the hold land and are ignored
        assertEquals(0.0, rig.player.currentTimeMs)
        rig.advance(51) // t=3350: the hold is over and the sample of that instant catches up
        assertEquals(300.0, rig.player.currentTimeMs)
        assertEquals(300.0, time.nativePositionMs)
        rig.advance(2_700) // t=6050: the second wrap
        assertEquals(3, loop.cycle)
        assertEquals(2, rig.count("endOfMedia"))
    }

    @Test
    fun aRateOfZeroMovesNothingAndNeverEnds() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx {
            rig.player.rate = 0.0
            rig.player.play()
        }

        rig.advance(10_000)

        assertEquals(0.0, rig.time.nativePositionMs)
        assertEquals(0, rig.count("endOfMedia"))
    }

    // -- B7: the end without looping --

    @Test
    fun theEndFiresOnceAtTheDurationAndTheStatusStaysPlaying() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(3_000)

        val end: RecordingNativeEvents.Event = rig.events.events.single { it.name == "endOfMedia" }
        assertEquals(3_050.0, end.tMs)
        assertTrue(end.onFxThread)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertEquals(3_000.0, rig.player.currentTimeMs)
        assertEquals(3_000.0, rig.time.nativePositionMs)
        assertEquals(1, rig.time.endsReached)
    }

    @Test
    fun theFinalPositionIsPublishedBeforeTheEndIsDelivered() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { publishDelayMs = 80.0 })
        val readAtEnd: MutableList<Double> = mutableListOf()
        rig.onFx {
            rig.player.setEvents(
                object : io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents by rig.events {
                    override fun onEndOfMedia() {
                        readAtEnd += rig.player.currentTimeMs
                        rig.events.onEndOfMedia()
                    }
                },
            )
            rig.player.play()
        }

        rig.advance(3_000)

        assertEquals(listOf(3_000.0), readAtEnd, "the position the end handler reads")
    }

    @Test
    fun theEndComesEarlierAtAHigherRate() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx {
            rig.player.rate = 2.0
            rig.player.play()
        }

        rig.advance(1_500)

        assertEquals(listOf(1_550.0), rig.events.events.filter { it.name == "endOfMedia" }.map { it.tMs })
    }

    @Test
    fun aRateChangeMovesTheEnd() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(1_000)
        rig.onFx { rig.player.rate = 2.0 }

        rig.advance(999)
        assertEquals(0, rig.count("endOfMedia"))
        rig.advance(1)

        assertEquals(1, rig.count("endOfMedia"))
        assertEquals(2_050.0, rig.now())
    }

    @Test
    fun afterTheEndThePositionHoldsSamplingStopsAndTheQueueIsIdle() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(3_000)
        rig.events.clear()

        rig.advance(10_000)

        assertEquals(3_000.0, rig.player.currentTimeMs)
        assertEquals(3_000.0, rig.time.nativePositionMs)
        assertEquals(0, rig.count("currentTime"))
        assertEquals(0, rig.scope.host.pendingCount)
    }

    @Test
    fun aPauseBeforeTheEndPreventsIt() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(2_000)
        rig.onFx { rig.player.pause() }

        rig.advance(10_000)

        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(2_000.0, rig.time.nativePositionMs)
    }

    @Test
    fun aClearedEndOfMediaHandlerHearsNothingButThePositionStillReachesTheEnd() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx {
            rig.player.clearEndOfMedia()
            rig.player.play()
        }

        rig.advance(3_000)

        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(3_000.0, rig.player.currentTimeMs)
        assertEquals(1, rig.time.endsReached)
    }

    @Test
    fun aSeekBackAfterTheEndAndAPlayEndsAgain() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(3_000)
        rig.onFx {
            rig.player.pause()
            rig.player.seek(0.0)
        }
        rig.advance(0)
        assertEquals(0.0, rig.player.currentTimeMs)
        rig.onFx { rig.player.play() }

        rig.advance(3_000)

        assertEquals(2, rig.count("endOfMedia"))
        assertEquals(3_000.0, rig.player.currentTimeMs)
    }

    @Test
    fun aPlayWhileThePositionStandsAtTheEndEndsAgainAtOnce() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(3_000)
        rig.onFx { rig.player.pause() }
        rig.advance(100)
        assertEquals(1, rig.count("endOfMedia"))

        rig.onFx { rig.player.play() }
        rig.advance(0)

        assertEquals(2, rig.count("endOfMedia"))
        assertEquals(3_000.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSeekToTheEndWhilePlayingEndsAtOnceThroughTheQueue() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(100)
        rig.events.clear()
        val before: Double = rig.now()

        rig.onFx {
            rig.player.seek(5_000.0)
            assertEquals(0, rig.count("endOfMedia"), "queued, not run inside the call")
        }
        rig.advance(0)

        assertEquals(1, rig.count("endOfMedia"))
        assertEquals(3_000.0, rig.player.currentTimeMs)
        assertEquals(before, rig.now())
    }

    @Test
    fun aSeekToTheEndWhilePausedDoesNotEndUntilThePlay() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.seek(5_000.0) }
        rig.advance(1_000)
        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(3_000.0, rig.player.currentTimeMs)

        rig.onFx { rig.player.play() }
        rig.advance(0)

        assertEquals(1, rig.count("endOfMedia"))
    }

    @Test
    fun theNativePositionNeverPassesTheDurationUnderAJitteredHost() = fxModel(jitterMaxMs = 17.0) {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(3_100)

        assertEquals(3_000.0, rig.time.nativePositionMs)
        assertEquals(3_000.0, rig.player.currentTimeMs)
        assertEquals(1, rig.count("endOfMedia"))
        val end: Double = rig.events.events.single { it.name == "endOfMedia" }.tMs
        assertTrue(end >= 3_050.0 && end <= 3_050.0 + 3 * 17.0, "end at $end with seed ${rig.scope.host.seed}")
    }

    // -- failure and dispose --

    @Test
    fun aDisposeStopsEverythingAndDropsWhatWasInFlight() = fxModel {
        val rig: Rig = readyRig(TimeScript().apply { publishDelayMs = 70.0 })
        rig.onFx { rig.player.play() }
        rig.advance(160) // a sample of t=150 is in flight until 220
        rig.onFx { rig.player.dispose() }
        rig.events.clear()

        rig.advance(10_000)

        assertEquals(0.0, rig.player.currentTimeMs)
        assertEquals(0, rig.count("currentTime"))
        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(0, rig.scope.host.pendingCount)
        assertFalse(rig.time.isRunning)
    }

    @Test
    fun aFailureStopsTheDecoder() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(250)
        rig.onFx { rig.player.failAt(0.0, IllegalStateException("boom")) }
        rig.advance(0)
        rig.events.clear()

        rig.advance(10_000)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertEquals(0, rig.count("currentTime"))
        assertEquals(0, rig.count("endOfMedia"))
        assertEquals(250.0, rig.time.nativePositionMs)
    }

    // -- the script --

    @Test
    fun theScriptRefusesNumbersThatCannotBeTimes() {
        assertFailsWith<IllegalArgumentException> { TimeScript().sampleIntervalMs = 0.0 }
        assertFailsWith<IllegalArgumentException> { TimeScript().publishDelayMs = -1.0 }
        assertFailsWith<IllegalArgumentException> { TimeScript().nearStartMs = Double.NaN }
        assertFailsWith<IllegalArgumentException> { TimeScript().load(minDelayMs = 100.0, maxDelayMs = 50.0) }
        assertFailsWith<IllegalArgumentException> { TimeScript().load(dropFraction = 1.5) }
    }

    @Test
    fun aScriptWithoutTheLoadProfileUsesThePublishDelayForEverySample() {
        val script: TimeScript = TimeScript().apply { publishDelayMs = 25.0 }

        assertFalse(script.isLoaded)
        assertEquals(List(5) { 25.0 }, List(5) { script.nextPublishDelayMs() })
    }

    // -- the backend that gives every player a time part --

    @Test
    fun aTimedBackendGivesEveryPlayerItsOwnTimeModelAndScriptCopy() = fxModel {
        val inner = ScriptedBackend(host)
        allowUnwatched(inner, "test of the timed backend: no hazard part is wanted")
        val backend = TimedBackend(inner, TimeScript().apply { publishDelayMs = 10.0 })

        val (a: ScriptedNativePlayer, b: ScriptedNativePlayer) = onFx {
            val source = backend.probe("clip.mp4")
            backend.create(source) as ScriptedNativePlayer to backend.create(source) as ScriptedNativePlayer
        }

        assertEquals(2, backend.models.size)
        assertTrue(a.time === backend.models[0] && b.time === backend.models[1])
        assertTrue(backend.models[0].script !== backend.models[1].script)
        assertEquals(10.0, backend.models[0].script.publishDelayMs)
        assertTrue(backend.lastModel === backend.models[1])
        assertEquals(listOf("clip.mp4"), inner.probed)
    }

    // -- a smoke of the whole sequence a player is put through: ready, play, seek, complete --

    @Test
    fun readyPlaySeekAndCompleteRunToTheEndInVirtualTimeOnly() = fxModel {
        val inner = ScriptedBackend(host)
        allowUnwatched(inner, "time-part smoke without a hazard part: the whole sequence runs unhazarded on purpose")
        val backend = TimedBackend(inner)
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = onFx {
            (backend.create(backend.probe("clip.mp4")) as ScriptedNativePlayer).also { it.setEvents(events) }
        }
        advanceBy(50)
        assertEquals(NativeStatus.READY, player.status)

        onFx { player.play() }
        advanceBy(500)
        onFx { player.seek(1_540.0) }
        advanceBy(100)
        assertEquals(NativeStatus.PLAYING, player.status)
        assertEquals(1_100.0, player.currentTimeMs)
        runUntilIdle()

        assertEquals(1, events.names.count { it == "endOfMedia" })
        assertEquals(3_000.0, player.currentTimeMs)
        assertEquals(NativeStatus.PLAYING, player.status)
        assertEquals(0, host.pendingCount)
        // 50 ready + 500 + the 2000 ms left after the seek to the key frame at 1000: the end is at t=2550
        assertTrue(events.events.single { it.name == "endOfMedia" }.tMs == 2_550.0, "events: ${events.events}")
    }

    // -- the stale first publication after a resume (D2 r11, opt-in) --

    private fun staleScript(lagMs: Double = 60.0, freshAfterMs: Double = 3.0): TimeScript =
        TimeScript().also { it.staleFirstPublicationAfterPlay = TimeScript.StaleFirstPublication(lagMs, freshAfterMs) }

    @Test
    fun theFirstTickAfterAPlayPublishesAStaleValueAndAFreshOneFollows() = fxModel {
        val rig: Rig = readyRig(staleScript(lagMs = 60.0, freshAfterMs = 3.0))
        rig.onFx { rig.player.play() }

        rig.advance(100) // t=150: the first tick, native 100, stale 40
        assertEquals(40.0, rig.player.currentTimeMs)
        rig.advance(3) // t=153: the fresh sample
        assertEquals(103.0, rig.player.currentTimeMs)
        assertEquals(listOf(150.0, 153.0), rig.tickTimes())
        rig.advance(97) // t=250: the next ordinary tick, native 200
        assertEquals(200.0, rig.player.currentTimeMs)
        assertEquals(listOf(150.0, 153.0, 250.0), rig.tickTimes())
    }

    @Test
    fun aStaleValueBelowZeroIsZeroAndPublishesNothingNewButTheFreshOneStillFollows() = fxModel {
        val rig: Rig = readyRig(staleScript(lagMs = 424.0, freshAfterMs = 3.0))
        rig.onFx { rig.player.play() }

        rig.advance(100)
        assertEquals(0.0, rig.player.currentTimeMs)
        assertEquals(emptyList(), rig.tickTimes())
        rig.advance(3)
        assertEquals(103.0, rig.player.currentTimeMs)
        assertEquals(listOf(153.0), rig.tickTimes())
    }

    @Test
    fun theStaleFirstPublicationIsOffByDefault() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }

        rig.advance(100)
        assertEquals(100.0, rig.player.currentTimeMs)
        assertEquals(listOf(150.0), rig.tickTimes())
        assertEquals(null, TimeScript().staleFirstPublicationAfterPlay)
    }

    @Test
    fun everyResumeOfAPausedPlayerGetsItsOwnStaleFirstPublicationAndPlayingOnDoesNot() = fxModel {
        val rig: Rig = readyRig(staleScript(lagMs = 60.0, freshAfterMs = 3.0))
        rig.onFx { rig.player.play() }
        rig.advance(400) // t=450, native 400: the first resume's pair is long past
        rig.onFx { rig.player.pause() }
        rig.advance(500)
        rig.events.clear()
        val resumedAt: Double = rig.now()
        rig.onFx { rig.player.play() }

        rig.advance(100)
        assertEquals(440.0, rig.player.currentTimeMs, "stale: the decoder stood at 400 and ran 100 ms, less the lag of 60")
        rig.advance(3)
        assertEquals(503.0, rig.player.currentTimeMs)
        assertEquals(listOf(resumedAt + 100.0, resumedAt + 103.0), rig.tickTimes())
        rig.advance(97)
        assertEquals(600.0, rig.player.currentTimeMs)
        rig.advance(100)
        assertEquals(700.0, rig.player.currentTimeMs, "the tick after the pair is ordinary again")
    }

    @Test
    fun ticksInsideTheWaitForTheFreshSampleFindTheStaleValueStanding() = fxModel {
        val rig: Rig = readyRig(staleScript(lagMs = 60.0, freshAfterMs = 150.0))
        rig.onFx { rig.player.play() }

        rig.advance(100) // t=150: the stale value, native 100 less 60
        assertEquals(40.0, rig.player.currentTimeMs)
        rig.advance(99) // t=249: the tick at 250 has not come yet
        rig.advance(1) // t=250: a tick inside the wait (the fresh sample is due at 300) publishes nothing
        assertEquals(40.0, rig.player.currentTimeMs)
        assertEquals(listOf(150.0), rig.tickTimes())
        rig.advance(50) // t=300: the fresh sample, native 250
        assertEquals(250.0, rig.player.currentTimeMs)
        assertEquals(listOf(150.0, 300.0), rig.tickTimes())
        rig.advance(50) // t=350: an ordinary tick again, native 300
        assertEquals(300.0, rig.player.currentTimeMs)
    }

    @Test
    fun aSeekSampleDoesNotUseUpTheStaleFirstPublication() = fxModel {
        val rig: Rig = readyRig(staleScript(lagMs = 60.0, freshAfterMs = 3.0))
        rig.onFx { rig.player.play() }
        rig.advance(10)
        rig.onFx { rig.player.seek(2_000.0) }
        rig.advance(1)
        assertEquals(2_000.0, rig.player.currentTimeMs, "the seek's own sample is ordinary")
        rig.advance(89) // the first tick after the play, at t=150: native 2000 + 99 less 60
        assertEquals(2_030.0, rig.player.currentTimeMs, "the first tick still carries the stale value: native 2090 less 60")
    }

    @Test
    fun theStaleFirstPublicationIsCopiedAndValidated() {
        val copy: TimeScript = staleScript(lagMs = 70.0, freshAfterMs = 4.0).copy()
        assertEquals(70.0, copy.staleFirstPublicationAfterPlay?.lagMs)
        assertEquals(4.0, copy.staleFirstPublicationAfterPlay?.freshAfterMs)
        assertFailsWith<IllegalArgumentException> { TimeScript.StaleFirstPublication(-1.0, 3.0) }
        assertFailsWith<IllegalArgumentException> { TimeScript.StaleFirstPublication(10.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { TimeScript.StaleFirstPublication(Double.NaN, 3.0) }
    }
}
