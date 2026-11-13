package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hazard part of the scripted player (design B4, B6 and B11): every window opens and closes at its exact virtual
 * edge, a violation freezes the decoder, and recovery follows the configured rule. Edges are tested to the
 * nanosecond: a rate change 1 ns before the width is a violation, one exactly at the width is safe. No JavaFX, no
 * real time.
 */
class HazardModelTest {

    private companion object {
        const val MS: Long = 1_000_000L
    }

    private class Rig(val scope: FxModelScope, val player: ScriptedNativePlayer, val hazards: HazardModel) {
        fun advanceMs(ms: Long) = scope.advanceBy(ms)

        fun advanceNanos(nanos: Long) = scope.host.advanceByNanos(nanos)

        fun play() = scope.onFx { player.play() }

        fun pause() = scope.onFx { player.pause() }

        fun seek(ms: Double = 0.0) = scope.onFx { player.seek(ms) }

        fun rate(value: Double) = scope.onFx { player.rate = value }

        /** A play, as a replay on a running player does it: seek, then play. */
        fun replay() = scope.onFx {
            player.seek(0.0)
            player.play()
        }
    }

    /** A ready player (the ready edge is 50 ms) with a hazard part; the decoder does not run yet. */
    private fun FxModelScope.rig(config: HazardConfig = HazardConfig(), waitForReady: Boolean = true): Rig {
        val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        val hazards = HazardModel(host, config)
        player.hazard = hazards
        if (waitForReady) advanceBy(50)
        return Rig(this, player, hazards)
    }

    /** A rig whose decoder runs from a plain play at the ready edge + 0, with W-PLAY already closed. */
    private fun FxModelScope.runningRig(config: HazardConfig = HazardConfig()): Rig =
        rig(config).also {
            it.play()
            it.advanceMs(500)
        }

    // -- B4: the decoder after a plain play --

    @Test
    fun aPlayFromNotRunningStartsTheDecoderAtUnityWhateverThePropertyHolds() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.advanceMs(300)

        rig.play()

        assertTrue(rig.hazards.decoderRunning)
        assertEquals(2.0, rig.player.rate)
        assertEquals(1.0, rig.hazards.effectiveRate)
        assertTrue(rig.hazards.violations.isEmpty())
    }

    @Test
    fun aLaterPropertyChangeSetsTheEffectiveRate() = fxModel {
        val rig: Rig = runningRig()

        rig.rate(1.5)

        assertEquals(1.5, rig.hazards.effectiveRate)
        assertFalse(rig.hazards.frozen)
    }

    @Test
    fun aWriteOfTheHeldValueIsNoChangeAndTouchesNoWindow() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.advanceMs(10)

        rig.rate(1.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
    }

    @Test
    fun withoutB4AnotherPlatformStartsTheDecoderAtThePropertyRate() = fxModel {
        val rig: Rig = rig(HazardConfig(decoderStartsAtUnity = false))
        rig.rate(2.0)
        rig.advanceMs(300)

        rig.play()

        assertEquals(2.0, rig.hazards.effectiveRate)
    }

    // -- W-PLAY --

    @Test
    fun wPlayIsOpenUntilOneNanosecondBeforeItsWidthAndAChangeInsideFreezes() = fxModel {
        val rig: Rig = rig()
        rig.play()

        rig.advanceNanos(100 * MS - 1)
        assertEquals(setOf(FragileWindow.PLAY), rig.hazards.openWindows)
        rig.rate(2.0)

        assertTrue(rig.hazards.frozen)
        val violation: HazardViolation = rig.hazards.violations.single()
        assertEquals(FragileWindow.PLAY, violation.window)
        assertEquals(NativeCall.SET_RATE, violation.trigger)
        assertEquals(2.0, violation.arg)
        assertEquals(50.0, violation.openedAtMs)
        assertEquals(150.0 - 1e-6, violation.tMs, 1e-9)
    }

    @Test
    fun wPlayIsClosedExactlyAtItsWidthAndAChangeThereIsSafe() = fxModel {
        val rig: Rig = rig()
        rig.play()

        rig.advanceNanos(100 * MS)
        assertTrue(rig.hazards.openWindows.isEmpty())
        rig.rate(2.0)

        assertFalse(rig.hazards.frozen)
        assertTrue(rig.hazards.violations.isEmpty())
        assertEquals(2.0, rig.hazards.effectiveRate)
    }

    @Test
    fun wPlayDoesNotOpenAfterASeekSinceTheLastPlay() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()

        rig.rate(2.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
        assertTrue(rig.hazards.openWindows.isEmpty())
    }

    @Test
    fun wPlayOpensAgainAfterAPauseAndAPlainPlay() = fxModel {
        val rig: Rig = runningRig()
        rig.pause()
        rig.advanceMs(500)
        rig.play()

        rig.advanceMs(99)
        rig.rate(2.0)

        assertEquals(FragileWindow.PLAY, rig.hazards.violations.single().window)
    }

    @Test
    fun aPlayBeforeReadyStartsTheDecoderAtTheReadyEdgeAndOpensWPlayThere() = fxModel {
        val rig: Rig = rig(waitForReady = false)
        rig.play()
        rig.advanceMs(49)
        assertFalse(rig.hazards.decoderRunning)

        rig.advanceMs(1)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertTrue(rig.hazards.decoderRunning)
        assertEquals(setOf(FragileWindow.PLAY), rig.hazards.openWindows)

        rig.advanceNanos(100 * MS - 1)
        rig.rate(2.0)
        assertEquals(FragileWindow.PLAY, rig.hazards.violations.single().window)
    }

    @Test
    fun aPauseBeforeReadyCancelsThePendingPlay() = fxModel {
        val rig: Rig = rig(waitForReady = false)
        rig.play()
        rig.pause()

        rig.advanceMs(100)

        assertFalse(rig.hazards.decoderRunning)
    }

    // -- W-IDLE --

    @Test
    fun wIdleFreezesAPlayOneNanosecondInsideTheGap() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)

        rig.advanceNanos(200 * MS - 1)
        rig.play()

        assertTrue(rig.hazards.frozen)
        val violation: HazardViolation = rig.hazards.violations.single()
        assertEquals(FragileWindow.IDLE, violation.window)
        assertEquals(NativeCall.PLAY, violation.trigger)
        assertEquals(50.0, violation.openedAtMs)
        assertEquals(200.0, violation.widthMs)
    }

    @Test
    fun wIdleIsSafeForAPlayExactlyAtTheGap() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)

        rig.advanceNanos(200 * MS)
        rig.play()

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
    }

    @Test
    fun wIdleCountsTheNewestPropertyChangeOfAnIdlePlayer() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.advanceMs(150)
        rig.rate(3.0)

        rig.advanceNanos(150 * MS)
        rig.play()

        assertEquals(FragileWindow.IDLE, rig.hazards.violations.single().window)
    }

    @Test
    fun aPlayConsumesTheIdleChangeSoALaterPlayIsNotBlamedForIt() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.advanceMs(300)
        rig.play()
        rig.pause()

        rig.play()

        assertTrue(rig.hazards.violations.isEmpty())
    }

    // -- W-SEEK-RUN and the readings of Q-M1 --

    @Test
    fun wSeekRunIsOpenUntilOneNanosecondBeforeItsWidthAfterASeekWhileRunning() = fxModel {
        val rig: Rig = runningRig()
        rig.seek(0.0)

        rig.advanceNanos(300 * MS - 1)
        assertEquals(setOf(FragileWindow.SEEK_RUN), rig.hazards.openWindows)
        rig.rate(1.5)

        val violation: HazardViolation = rig.hazards.violations.single()
        assertEquals(FragileWindow.SEEK_RUN, violation.window)
        assertEquals(550.0, violation.openedAtMs)
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun wSeekRunIsClosedExactlyAtItsWidth() = fxModel {
        val rig: Rig = runningRig()
        rig.seek(0.0)

        rig.advanceNanos(300 * MS)
        assertTrue(rig.hazards.openWindows.isEmpty())
        rig.rate(1.5)

        assertTrue(rig.hazards.violations.isEmpty())
        assertEquals(1.5, rig.hazards.effectiveRate)
    }

    @Test
    fun aSecondSeekWhileRunningRestartsWSeekRun() = fxModel {
        val rig: Rig = runningRig()
        rig.seek(0.0)
        rig.advanceMs(200)
        rig.seek(0.0)

        rig.advanceNanos(300 * MS - 1)
        rig.rate(1.5)

        assertEquals(FragileWindow.SEEK_RUN, rig.hazards.violations.single().window)
    }

    @Test
    fun aReplayOfAPlayingClipOpensWSeekRunButNotWPlay() = fxModel {
        val rig: Rig = runningRig()
        rig.replay()

        assertEquals(setOf(FragileWindow.SEEK_RUN), rig.hazards.openWindows)
        assertTrue(rig.hazards.decoderRunning)
    }

    @Test
    fun underTheDefaultReadingASeekWhileNotRunningOpensNoWindowAndItsRateChangeIsOnlyListedAsStrict() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()
        rig.advanceMs(10)

        rig.rate(2.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
        val hit: HazardViolation = rig.hazards.strictHits.single()
        assertEquals(FragileWindow.SEEK_RUN, hit.window)
        assertTrue(hit.strictOnly)
        assertEquals(50.0, hit.openedAtMs)
        assertEquals(60.0, hit.tMs)
    }

    @Test
    fun underTheDefaultReadingAStrictHitNeedsTheRateChangeInsideTheWidth() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()
        rig.advanceNanos(300 * MS)

        rig.rate(2.0)

        assertTrue(rig.hazards.strictHits.isEmpty())
    }

    @Test
    fun underTheStrictReadingASeekWhileNotRunningOpensWSeekRunAndFreezes() = fxModel {
        val rig: Rig = rig(HazardConfig(seekReading = SeekHazardReading.STRICT))
        rig.seek(0.0)
        rig.play()
        rig.advanceMs(10)

        rig.rate(2.0)

        assertEquals(FragileWindow.SEEK_RUN, rig.hazards.violations.single().window)
        assertFalse(rig.hazards.violations.single().strictOnly)
        assertTrue(rig.hazards.strictHits.isEmpty())
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun aSeekWhileRunningMakesAPendingStrictHitAnActualViolation() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()
        rig.advanceMs(10)
        rig.seek(0.0)

        rig.rate(2.0)

        assertEquals(FragileWindow.SEEK_RUN, rig.hazards.violations.single().window)
        assertTrue(rig.hazards.strictHits.isEmpty())
    }

    // -- W-WRAP --

    @Test
    fun wWrapOpensAtTheLoopWrapAndResetsTheDecoderToUnity() = fxModel {
        val rig: Rig = runningRig()
        rig.rate(2.0)
        assertEquals(2.0, rig.hazards.effectiveRate)

        rig.scope.onFx { rig.hazards.onLoopWrap(rig.player) }

        assertEquals(setOf(FragileWindow.WRAP), rig.hazards.openWindows)
        assertEquals(1.0, rig.hazards.effectiveRate)
    }

    @Test
    fun wWrapFreezesAChangeOneNanosecondBeforeItsWidth() = fxModel {
        val rig: Rig = runningRig()
        rig.scope.onFx { rig.hazards.onLoopWrap(rig.player) }

        rig.advanceNanos(100 * MS - 1)
        rig.rate(3.0)

        assertEquals(FragileWindow.WRAP, rig.hazards.violations.single().window)
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun wWrapIsSafeExactlyAtItsWidth() = fxModel {
        val rig: Rig = runningRig()
        rig.scope.onFx { rig.hazards.onLoopWrap(rig.player) }

        rig.advanceNanos(100 * MS)
        rig.rate(3.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertEquals(3.0, rig.hazards.effectiveRate)
    }

    // -- several windows, widths --

    @Test
    fun aChangeInTwoOpenWindowsIsReportedForBothAndFreezesOnce() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.advanceMs(10)
        rig.seek(0.0)
        rig.advanceMs(10)

        rig.rate(2.0)

        assertEquals(
            listOf(FragileWindow.PLAY, FragileWindow.SEEK_RUN),
            rig.hazards.violations.map { it.window },
        )
        assertEquals(70.0, rig.hazards.frozenAtMs)
    }

    @Test
    fun theWindowWidthsFollowTheConfig() = fxModel {
        val rig: Rig = rig(HazardConfig(playWindowMs = 40.0))
        rig.play()

        rig.advanceNanos(40 * MS - 1)
        assertEquals(setOf(FragileWindow.PLAY), rig.hazards.openWindows)
        rig.advanceNanos(1)
        assertTrue(rig.hazards.openWindows.isEmpty())
    }

    @Test
    fun aNegativeOrNonFiniteWidthIsRejected() {
        assertFailsWith<IllegalArgumentException> { HazardConfig(playWindowMs = -1.0) }
        assertFailsWith<IllegalArgumentException> { HazardConfig(idleGapMs = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { HazardConfig(wrapWindowMs = Double.POSITIVE_INFINITY) }
    }

    @Test
    fun theModelWorksOnAnyClockEpochAcrossTheLongRange() {
        for (epoch: Long in listOf(-1_000_000_000_000L, 0L, Long.MAX_VALUE - 1_000 * MS)) {
            fxModel(epochNanos = epoch) {
                val rig: Rig = rig()
                rig.play()
                rig.advanceNanos(100 * MS - 1)
                rig.rate(2.0)
                assertEquals(FragileWindow.PLAY, rig.hazards.violations.single().window, "epoch $epoch")
            }
        }
    }

    // -- stopping the decoder --

    @Test
    fun aPauseStopsTheDecoderAtOnceAndClosesEveryWindow() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.seek(0.0)
        assertEquals(setOf(FragileWindow.PLAY, FragileWindow.SEEK_RUN), rig.hazards.openWindows)

        rig.pause()

        assertFalse(rig.hazards.decoderRunning)
        assertTrue(rig.hazards.openWindows.isEmpty())
    }

    @Test
    fun theDecoderFollowsTheCommandNotTheLaggingStatus() = fxModel {
        val rig: Rig = runningRig()
        rig.pause()
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertFalse(rig.hazards.decoderRunning)

        rig.rate(2.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
    }

    @Test
    fun aFailureStopsTheDecoder() = fxModel {
        val rig: Rig = runningRig()

        rig.scope.onFx { rig.player.failAt(0.0, IllegalStateException("boom")) }
        rig.advanceMs(1)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertFalse(rig.hazards.decoderRunning)
    }

    @Test
    fun disposeStopsTheDecoderAndClosesTheWindows() = fxModel {
        val rig: Rig = rig()
        rig.play()

        rig.scope.onFx { rig.player.dispose() }

        assertFalse(rig.hazards.decoderRunning)
        assertTrue(rig.hazards.openWindows.isEmpty())
    }

    @Test
    fun aDisposedPlayerIsNotChecked() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.scope.onFx { rig.player.dispose() }

        rig.rate(2.0)
        rig.play()

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.decoderRunning)
    }

    @Test
    fun aPlayOnAHaltedPlayerStartsNothing() = fxModel {
        val rig: Rig = rig()
        rig.scope.onFx { rig.player.failAt(0.0, IllegalStateException("boom")) }
        rig.advanceMs(1)

        rig.play()

        assertFalse(rig.hazards.decoderRunning)
    }

    // -- B11: freeze and recovery --

    @Test
    fun aFrozenDecoderNeverRecoversByDefaultNotEvenByAPauseAndPlayOrAReplay() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.rate(2.0)
        assertTrue(rig.hazards.frozen)
        assertEquals(50.0, rig.hazards.frozenAtMs)

        rig.advanceMs(1_000)
        rig.replay()
        rig.advanceMs(1_000)
        rig.pause()
        rig.advanceMs(1_000)
        rig.play()

        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun underPausePlayRecoveryAFrozenDecoderComesBackAtAPlayAfterAPause() = fxModel {
        val rig: Rig = rig(HazardConfig(recovery = FreezeRecovery.PAUSE_PLAY))
        rig.play()
        rig.rate(2.0)
        assertTrue(rig.hazards.frozen)

        rig.pause()
        assertTrue(rig.hazards.frozen)
        rig.advanceMs(500)
        rig.play()

        assertFalse(rig.hazards.frozen)
        assertEquals(null, rig.hazards.frozenAtMs)
        assertEquals(1.0, rig.hazards.effectiveRate)
    }

    @Test
    fun underPausePlayRecoveryAReplayWithoutAPauseDoesNotRecover() = fxModel {
        val rig: Rig = rig(HazardConfig(recovery = FreezeRecovery.PAUSE_PLAY))
        rig.play()
        rig.rate(2.0)

        rig.advanceMs(500)
        rig.replay()

        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun aRecoveredDecoderCanFreezeAgainAndKeepsAllItsViolations() = fxModel {
        val rig: Rig = rig(HazardConfig(recovery = FreezeRecovery.PAUSE_PLAY))
        rig.play()
        rig.rate(2.0)
        rig.pause()
        rig.advanceMs(500)
        rig.play()
        rig.rate(3.0)

        assertTrue(rig.hazards.frozen)
        assertEquals(2, rig.hazards.violations.size)
    }

    @Test
    fun theObserversHearViolationsButNotStrictHits() = fxModel {
        val rig: Rig = rig()
        val heard: MutableList<HazardViolation> = mutableListOf()
        rig.hazards.addObserver { heard += it }
        rig.seek(0.0)
        rig.play()
        rig.rate(2.0)
        assertTrue(heard.isEmpty())

        rig.pause()
        rig.advanceMs(500)
        rig.play()
        rig.rate(3.0)

        assertEquals(1, heard.size)
        assertEquals(FragileWindow.PLAY, heard.single().window)
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun theHazardSlotAccessorFindsAnInstalledModelOnly() = fxModel {
        val rig: Rig = rig()
        assertEquals(rig.hazards, rig.player.hazardModel)

        val bare: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        assertEquals(null, bare.hazardModel)
    }

    // -- MUST-1: a write of the rate the decoder already plays at is no native change --

    /** A rig whose property holds 2.0 while the decoder plays at 1.0 (B4): the play was made at 2.0, windows closed. */
    private fun FxModelScope.unityDecoderRig(): Rig {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.advanceMs(300)
        rig.play()
        assertEquals(1.0, rig.hazards.effectiveRate)
        assertEquals(2.0, rig.player.rate)
        return rig
    }

    @Test
    fun aWriteEqualToTheEffectiveRateInsideWPlayIsNoViolation() = fxModel {
        val rig: Rig = unityDecoderRig()
        assertEquals(setOf(FragileWindow.PLAY), rig.hazards.openWindows)

        rig.rate(1.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
        assertEquals(1.0, rig.hazards.effectiveRate)
    }

    @Test
    fun aDifferentWriteInsideWPlayIsAViolation() = fxModel {
        val rig: Rig = unityDecoderRig()

        rig.rate(3.0)

        assertEquals(FragileWindow.PLAY, rig.hazards.violations.single().window)
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun aWriteEqualToTheEffectiveRateInsideWWrapIsNoViolationAndADifferentOneIs() = fxModel {
        val rig: Rig = runningRig()
        rig.rate(2.0)
        rig.scope.onFx { rig.hazards.onLoopWrap(rig.player) }
        assertEquals(setOf(FragileWindow.WRAP), rig.hazards.openWindows)
        assertEquals(1.0, rig.hazards.effectiveRate)

        // The loop wrap's own write: the property goes 2.0 -> 1.0 while the decoder already plays at 1.0.
        rig.advanceNanos(100 * MS - 1)
        rig.rate(1.0)
        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)

        rig.rate(2.0)
        assertEquals(FragileWindow.WRAP, rig.hazards.violations.single().window)
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun aWriteEqualToTheEffectiveRateInsideWSeekRunIsNoViolationAndADifferentOneIs() = fxModel {
        val rig: Rig = unityDecoderRig()
        rig.advanceMs(500)
        rig.seek(0.0)
        assertEquals(setOf(FragileWindow.SEEK_RUN), rig.hazards.openWindows)

        rig.advanceNanos(300 * MS - 1)
        rig.rate(1.0)
        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)

        rig.rate(2.0)
        assertEquals(FragileWindow.SEEK_RUN, rig.hazards.violations.single().window)
    }

    @Test
    fun aWriteEqualToTheEffectiveRateRecordsNoStrictHitEither() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.advanceMs(300)
        rig.seek(0.0)
        rig.play()
        assertEquals(1.0, rig.hazards.effectiveRate)

        rig.rate(1.0)

        assertTrue(rig.hazards.strictHits.isEmpty(), "the decoder did not change, so no reading counts it")
        assertTrue(rig.hazards.violations.isEmpty())

        rig.rate(2.0)
        assertEquals(FragileWindow.SEEK_RUN, rig.hazards.strictHits.single().window)
    }

    @Test
    fun aWriteEqualToTheEffectiveRateIsNoViolationUnderTheStrictReadingToo() = fxModel {
        val rig: Rig = rig(HazardConfig(seekReading = SeekHazardReading.STRICT))
        rig.rate(2.0)
        rig.advanceMs(300)
        rig.seek(0.0)
        rig.play()

        rig.rate(1.0)

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
    }

    // -- MUST-2: a native seek between the idle write and the play clears the gap under DEFAULT --

    @Test
    fun underTheDefaultReadingASeekAfterAnIdleRateWriteClearsTheGapAndTheCloseReplayIsOnlyAStrictHit() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.seek(0.0)
        rig.play()

        assertTrue(rig.hazards.violations.isEmpty())
        assertFalse(rig.hazards.frozen)
        val hit: HazardViolation = rig.hazards.strictHits.single()
        assertEquals(FragileWindow.IDLE, hit.window)
        assertEquals(NativeCall.PLAY, hit.trigger)
        assertTrue(hit.strictOnly)
        assertEquals(50.0, hit.openedAtMs)
        assertEquals(50.0, hit.tMs)
    }

    @Test
    fun theIdleStrictHitNeedsAPlayOneNanosecondInsideTheGapAfterTheSeek() = fxModel {
        val inside: Rig = rig().also {
            it.rate(2.0)
            it.seek(0.0)
            it.advanceNanos(200 * MS - 1)
            it.play()
        }
        assertEquals(FragileWindow.IDLE, inside.hazards.strictHits.single().window)
        assertTrue(inside.hazards.violations.isEmpty())
    }

    @Test
    fun aPlayExactlyAtTheGapAfterASeekIsNoStrictHit() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.seek(0.0)
        rig.advanceNanos(200 * MS)

        rig.play()

        assertTrue(rig.hazards.strictHits.isEmpty())
        assertTrue(rig.hazards.violations.isEmpty())
    }

    @Test
    fun underTheStrictReadingASeekKeepsTheIdleGapAndThePlayFreezes() = fxModel {
        val rig: Rig = rig(HazardConfig(seekReading = SeekHazardReading.STRICT))
        rig.rate(2.0)
        rig.seek(0.0)
        rig.play()

        val violation: HazardViolation = rig.hazards.violations.single()
        assertEquals(FragileWindow.IDLE, violation.window)
        assertFalse(violation.strictOnly)
        assertTrue(rig.hazards.strictHits.isEmpty())
        assertTrue(rig.hazards.frozen)
    }

    @Test
    fun aRateWriteAfterTheSeekStartsAFreshGapThatFreezesAPlayInsideIt() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.seek(0.0)
        rig.advanceMs(100)
        rig.rate(3.0)

        rig.advanceNanos(200 * MS - 1)
        rig.play()

        val violation: HazardViolation = rig.hazards.violations.single()
        assertEquals(FragileWindow.IDLE, violation.window)
        assertEquals(150.0, violation.openedAtMs, "the gap is counted from the newest property change")
        assertTrue(rig.hazards.strictHits.isEmpty(), "the superseded shadow is not reported")
    }

    @Test
    fun aSeekWithNoIdleRateWriteBeforeItLeavesNoIdleShadow() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()

        assertTrue(rig.hazards.strictHits.none { it.window == FragileWindow.IDLE })
        assertTrue(rig.hazards.violations.isEmpty())
    }

    @Test
    fun theIdleShadowIsConsumedByThePlayThatReportedIt() = fxModel {
        val rig: Rig = rig()
        rig.rate(2.0)
        rig.seek(0.0)
        rig.play()
        rig.pause()

        rig.play()

        assertEquals(1, rig.hazards.strictHits.count { it.window == FragileWindow.IDLE })
    }

    // -- MINOR-7: a pause closes every window; the strict list is recorded --

    @Test
    fun aWindowOpenAtAPauseDoesNotSurviveTheNextPlay() = fxModel {
        val rig: Rig = runningRig()
        rig.seek(0.0)
        rig.pause()
        rig.advanceMs(20)
        rig.seek(0.0)
        rig.play()
        rig.advanceMs(150)

        rig.rate(2.0)

        assertTrue(rig.hazards.violations.isEmpty(), "the SEEK-RUN of before the pause is closed")
        assertFalse(rig.hazards.frozen)
    }

    @Test
    fun aStrictHitIsListedWithoutFailingAndTheListSurvivesTheDecoderStopping() = fxModel {
        val rig: Rig = rig()
        rig.seek(0.0)
        rig.play()
        rig.rate(2.0)
        rig.pause()

        assertEquals(1, rig.hazards.strictHits.size)
        assertTrue(rig.hazards.violations.isEmpty())
    }
}
