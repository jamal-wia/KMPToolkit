package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The hazard monitor and its wiring into the model fixture (design 4.3): FREEZE mode collects and the end of the
 * test fails, FAIL_FAST mode fails at the offending call, both name the window and the last 20 log entries. No
 * JavaFX, no real time.
 */
class HazardMonitorTest {

    private class Rig(val scope: FxModelScope, val player: ScriptedNativePlayer, val monitor: HazardMonitor) {
        fun play() = scope.onFx { player.play() }

        fun rate(value: Double) = scope.onFx { player.rate = value }
    }

    /** A ready player under a monitor that is NOT wired to the end of the test, so a test may provoke violations. */
    private fun FxModelScope.rig(mode: MonitorMode = MonitorMode.FREEZE, config: HazardConfig = HazardConfig(), tailCount: Int = 20): Rig {
        val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        val monitor: HazardMonitor = player.withHazards(config, mode, tailCount)
        advanceBy(50)
        return Rig(this, player, monitor)
    }

    @Test
    fun freezeModeCollectsTheViolationAndLetsTheCallsContinue() = fxModel {
        val rig: Rig = rig(MonitorMode.FREEZE)
        rig.play()

        rig.rate(2.0)
        rig.rate(3.0)

        assertEquals(2, rig.monitor.violations.size)
        assertTrue(rig.monitor.hazards.frozen)
        assertEquals(listOf(2.0, 3.0), rig.player.log.rates())
    }

    @Test
    fun assertNoViolationNamesTheWindowTheCallAndTheLogTail() = fxModel {
        val rig: Rig = rig(MonitorMode.FREEZE)
        rig.play()
        rig.rate(2.0)

        val failure: AssertionError = assertFailsWith { rig.monitor.assertNoViolation() }

        val message: String = failure.message.orEmpty()
        assertTrue("W-PLAY" in message, message)
        assertTrue("SET_RATE(2.0)" in message, message)
        assertTrue("native call log" in message, message)
        assertTrue("PLAY" in message, message)
        assertTrue("seed" in message, message)
    }

    @Test
    fun assertNoViolationPassesWhenNothingWasViolated() = fxModel {
        val rig: Rig = rig()
        rig.play()
        rig.scope.advanceBy(100)
        rig.rate(2.0)

        rig.monitor.assertNoViolation()
    }

    @Test
    fun failFastThrowsAtTheOffendingCallWithTheWindowNamed() = fxModel {
        val rig: Rig = rig(MonitorMode.FAIL_FAST)
        rig.play()

        val failure: AssertionError = assertFailsWith { rig.rate(2.0) }

        val message: String = failure.message.orEmpty()
        assertTrue("W-PLAY" in message, message)
        assertTrue("FAIL_FAST" in message, message)
        assertTrue(rig.monitor.hazards.frozen)
        assertEquals(1, rig.monitor.violations.size)
        assertEquals(listOf(2.0), rig.player.log.rates(), "the call was logged before it failed")
    }

    @Test
    fun failFastLetsASafeCallThrough() = fxModel {
        val rig: Rig = rig(MonitorMode.FAIL_FAST)
        rig.play()
        rig.scope.advanceBy(100)

        rig.rate(2.0)

        assertFalse(rig.monitor.hazards.frozen)
    }

    @Test
    fun theMessageShowsTheLastTwentyEntriesAndCountsTheEarlierOnes() = fxModel {
        val rig: Rig = rig(MonitorMode.FREEZE)
        rig.scope.onFx {
            for (i in 1..30) rig.player.volume = if (i % 2 == 0) 0.25 else 0.5
        }
        rig.play()
        rig.rate(2.0)

        val message: String = assertFailsWith<AssertionError> { rig.monitor.assertNoViolation() }.message.orEmpty()

        // 30 volume writes (the first of 0.5 changes 1.0 -> 0.5), 1 play and 1 rate write: 32 entries, 20 shown.
        assertTrue("32 entries, 12 earlier omitted" in message, message)
        assertEquals(20, message.lines().count { it.startsWith("  t=") })
        assertTrue("SET_RATE(2) " in message, message)
    }

    @Test
    fun theTailLengthIsConfigurable() = fxModel {
        val rig: Rig = rig(MonitorMode.FREEZE, tailCount = 2)
        rig.play()
        rig.rate(2.0)

        val message: String = assertFailsWith<AssertionError> { rig.monitor.assertNoViolation() }.message.orEmpty()

        assertEquals(2, message.lines().count { it.startsWith("  t=") })
    }

    @Test
    fun aNonPositiveTailIsRejected() = fxModel {
        val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        val model = HazardModel(host)
        assertFailsWith<IllegalArgumentException> { HazardMonitor(player, model, MonitorMode.FREEZE, tailCount = 0) }
    }

    @Test
    fun strictHitsAreListedAndNeverFail() = fxModel {
        val rig: Rig = rig(MonitorMode.FAIL_FAST)
        rig.scope.onFx { rig.player.seek(0.0) }
        rig.play()
        rig.scope.advanceBy(10)

        rig.rate(2.0)

        rig.monitor.assertNoViolation()
        val report: String = rig.monitor.strictReport()
        assertTrue("1 hit(s)" in report, report)
        assertTrue("W-SEEK-RUN" in report, report)
        assertTrue("[strict reading only]" in report, report)
    }

    @Test
    fun theStrictReportSaysSoWhenThereAreNoHits() = fxModel {
        val rig: Rig = rig()

        assertEquals("strict reading of Q-M1: no hits", rig.monitor.strictReport())
    }

    @Test
    fun withHazardsRefusesASlotThatIsTaken() = fxModel {
        val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
        player.withHazards()

        assertFailsWith<IllegalStateException> { player.withHazards() }
    }

    // -- the fixture wiring: the end-of-test check --

    @Test
    fun aWatchedPlayerThatViolatesAWindowFailsTheModelTestAtTheEnd() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
                watchHazards(player)
                advanceBy(50)
                onFx {
                    player.play()
                    player.rate = 2.0
                }
            }
        }

        assertTrue("W-PLAY" in failure.message.orEmpty(), failure.message)
        assertTrue("no fragile window may be violated" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun aWatchedPlayerThatKeepsOutOfTheWindowsPassesTheModelTest() {
        fxModel {
            val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
            val monitor: HazardMonitor = watchHazards(player)
            advanceBy(50)
            onFx { player.play() }
            advanceBy(100)
            onFx { player.rate = 2.0 }
            assertEquals(listOf(monitor), hazardMonitors)
        }
    }

    @Test
    fun aFailingBlockKeepsItsOwnFailureAndTheViolationIsAddedAsSuppressed() {
        val failure: IllegalStateException = assertFailsWith {
            fxModel {
                val player: ScriptedNativePlayer = onFx { ScriptedNativePlayer(host) }
                watchHazards(player)
                advanceBy(50)
                onFx {
                    player.play()
                    player.rate = 2.0
                }
                throw IllegalStateException("the block's own failure")
            }
        }

        assertEquals("the block's own failure", failure.message)
        val suppressed: Throwable = failure.suppressed.single()
        assertTrue("W-PLAY" in suppressed.message.orEmpty(), suppressed.message)
    }

    @Test
    fun theHazardBackendWatchesEveryPlayerItCreates() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val backend = ScriptedBackend(host)
                val watched: HazardBackend = hazardBackend(backend)
                val first: ScriptedNativePlayer = onFx { watched.create(watched.probe("a.mp4")) as ScriptedNativePlayer }
                val second: ScriptedNativePlayer = onFx { watched.create(watched.probe("b.mp4")) as ScriptedNativePlayer }
                assertSame(first, backend.players[0])
                assertSame(second, backend.last)
                assertEquals(2, hazardMonitors.size)
                assertSame(first, hazardMonitors[0].player)
                assertTrue(first.hazardModel != null && second.hazardModel != null)
                assertEquals(listOf("a.mp4", "b.mp4"), backend.probed)
                assertSame(host, watched.host)
                advanceBy(50)
                onFx {
                    second.play()
                    second.rate = 2.0
                }
            }
        }

        assertTrue("W-PLAY" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun theHazardBackendPassesTheConfigAndModeOn() {
        val failure: AssertionError = assertFailsWith {
            fxModel {
                val backend = ScriptedBackend(host)
                val watched: HazardBackend = hazardBackend(backend, HazardConfig(playWindowMs = 10.0), MonitorMode.FAIL_FAST)
                val player: ScriptedNativePlayer = onFx { watched.create(watched.probe("a.mp4")) as ScriptedNativePlayer }
                advanceBy(50)
                onFx { player.play() }
                advanceBy(20)
                onFx { player.rate = 2.0 }
                advanceBy(1)
                assertEquals(MonitorMode.FAIL_FAST, hazardMonitors.single().mode)
                onFx {
                    player.pause()
                    player.play()
                    player.rate = 3.0
                }
            }
        }

        assertTrue("FAIL_FAST" in failure.message.orEmpty(), failure.message)
        assertTrue("W-PLAY" in failure.message.orEmpty(), failure.message)
    }
}
