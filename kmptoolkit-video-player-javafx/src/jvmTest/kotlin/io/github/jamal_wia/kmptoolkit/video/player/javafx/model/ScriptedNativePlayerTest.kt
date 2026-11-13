package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The skeleton of the scripted native player, one group per behaviour of design section 4.1: B1 (property
 * semantics), B2 (status lag), B9 (ready and failure), plus the call log, the clip and the extension slots. No
 * JavaFX, no real time: every wait is virtual.
 */
class ScriptedNativePlayerTest {

    /** A player built the way `FxPlayback` builds one: on the FX thread, handlers installed at once. */
    private class Rig(val scope: FxModelScope, val backend: ScriptedBackend, val player: ScriptedNativePlayer, val events: RecordingNativeEvents) {
        fun advance(ms: Long) = scope.advanceBy(ms)

        fun <T> onFx(block: () -> T): T = scope.onFx(block)

        fun now(): Double = scope.host.elapsedNanos / 1_000_000.0
    }

    private fun FxModelScope.rig(
        script: StatusScript = StatusScript(),
        clip: ClipModel = ClipModel(),
        installEvents: Boolean = true,
    ): Rig {
        val backend = ScriptedBackend(host, clip, script)
        val events = RecordingNativeEvents(host)
        val player: ScriptedNativePlayer = onFx {
            val created: ScriptedNativePlayer = backend.create(backend.probe("clip.mp4")) as ScriptedNativePlayer
            if (installEvents) created.setEvents(events)
            created
        }
        return Rig(this, backend, player, events)
    }

    /** A rig advanced to the ready edge with the event record cleared, the usual start of a command test. */
    private fun FxModelScope.readyRig(script: StatusScript = StatusScript()): Rig =
        rig(script).also {
            it.advance(50)
            it.events.clear()
        }

    // -- B9: ready --

    @Test
    fun aNewPlayerReadsUnknownWithNoDurationAndNoSize() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        assertEquals(NativeStatus.UNKNOWN, p.status)
        assertTrue(p.durationMs.isNaN())
        assertEquals(0, p.width)
        assertEquals(0, p.height)
        assertNull(p.playerError)
        assertNull(p.mediaError)
        assertEquals(1.0, p.rate)
        assertEquals(1.0, p.volume)
        assertEquals(1, p.cycleCount)
    }

    @Test
    fun readyArrivesAtTheReadyLatencyNotBefore() = fxModel {
        val rig: Rig = rig()

        rig.advance(49)
        assertEquals(NativeStatus.UNKNOWN, rig.player.status)
        assertTrue(rig.events.events.isEmpty())

        rig.advance(1)
        assertEquals(NativeStatus.READY, rig.player.status)
    }

    @Test
    fun readyGivesDurationSizeThenStatusThenOnReadyInOneInstant() = fxModel {
        val rig: Rig = rig()

        rig.advance(50)

        assertEquals(listOf("duration", "size", "size", "status:READY", "ready"), rig.events.names)
        assertEquals(setOf(50.0), rig.events.events.map { it.tMs }.toSet())
        assertEquals(3_000.0, rig.player.durationMs)
        assertEquals(64, rig.player.width)
        assertEquals(48, rig.player.height)
    }

    @Test
    fun everyEventIsDeliveredOnTheFxThread() = fxModel {
        val rig: Rig = rig()

        rig.advance(50)

        assertTrue(rig.events.events.isNotEmpty() && rig.events.events.all { it.onFxThread }, rig.events.events.toString())
    }

    @Test
    fun theReadyValuesAreSetBeforeAnyEventIsDelivered() = fxModel {
        val rig: Rig = rig()
        val seenAtDuration: MutableList<Triple<Double, Int, NativeStatus>> = mutableListOf()
        rig.onFx {
            rig.player.setEvents(
                object : NativeEvents by rig.events {
                    override fun onDuration() {
                        seenAtDuration += Triple(rig.player.durationMs, rig.player.width, rig.player.status)
                    }
                },
            )
        }

        rig.advance(50)

        assertEquals(listOf(Triple(3_000.0, 64, NativeStatus.UNKNOWN)), seenAtDuration)
    }

    @Test
    fun theReadyLatencyIsConfigurable() = fxModel {
        val rig: Rig = rig(StatusScript().apply { readyLatencyMs = 120.0 })

        rig.advance(119)
        assertEquals(NativeStatus.UNKNOWN, rig.player.status)
        rig.advance(1)
        assertEquals(NativeStatus.READY, rig.player.status)
    }

    @Test
    fun aCustomClipDefinesTheDurationAndTheSize() = fxModel {
        val rig: Rig = rig(clip = ClipModel(durationMs = 750.0, width = 320, height = 200))

        rig.advance(50)

        assertEquals(750.0, rig.player.durationMs)
        assertEquals(320, rig.player.width)
        assertEquals(200, rig.player.height)
    }

    @Test
    fun neverReadyKeepsUnknownForGoodWithNoEventAndNoError() = fxModel {
        val rig: Rig = rig(StatusScript().neverReady())

        rig.advance(3_600_000)

        assertEquals(NativeStatus.UNKNOWN, rig.player.status)
        assertTrue(rig.player.durationMs.isNaN())
        assertTrue(rig.events.events.isEmpty())
        assertNull(rig.player.playerError)
        assertNull(rig.player.mediaError)
    }

    @Test
    fun aPlayerWithNoHandlersYetStillBecomesReadyAndDeliversNothing() = fxModel {
        val rig: Rig = rig(installEvents = false)

        rig.advance(50)

        assertEquals(NativeStatus.READY, rig.player.status)
        assertEquals(3_000.0, rig.player.durationMs)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun disposeBeforeReadyDropsTheReadyForGood() = fxModel {
        val rig: Rig = rig()
        rig.advance(20)

        rig.onFx { rig.player.dispose() }
        rig.events.clear()
        rig.advance(1_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertTrue(rig.player.durationMs.isNaN())
        assertTrue(rig.events.events.isEmpty(), rig.events.names.toString())
    }

    // -- B9: failure --

    private val cause: Throwable = IllegalStateException("bad media")

    @Test
    fun aPlayerChannelFailureSetsOnlyThePlayerErrorAndHaltsThenFiresThePlayerHandler() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(10.0, cause, FailureVia.PLAYER))

        rig.advance(10)

        assertSame(cause, rig.player.playerError)
        assertNull(rig.player.mediaError)
        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertEquals(listOf("status:HALTED", "playerError"), rig.events.names)
    }

    @Test
    fun aMediaChannelFailureSetsOnlyTheMediaErrorAndFiresTheMediaHandler() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(10.0, cause, FailureVia.MEDIA))

        rig.advance(10)

        assertNull(rig.player.playerError)
        assertSame(cause, rig.player.mediaError)
        assertEquals(listOf("status:HALTED", "mediaError"), rig.events.names)
    }

    @Test
    fun aFailureOnBothChannelsFiresTheMediaHandlerThenThePlayerHandler() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(10.0, cause))

        rig.advance(10)

        assertSame(cause, rig.player.playerError)
        assertSame(cause, rig.player.mediaError)
        assertEquals(listOf("status:HALTED", "mediaError", "playerError"), rig.events.names)
    }

    @Test
    fun aFailureBeforeReadyMeansNoReadyEver() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(10.0, cause, FailureVia.PLAYER))

        rig.advance(10_000)

        assertTrue("ready" !in rig.events.names, rig.events.names.toString())
        assertTrue(rig.player.durationMs.isNaN())
        assertEquals(NativeStatus.HALTED, rig.player.status)
    }

    @Test
    fun aFailureAfterReadyHaltsAReadyPlayerAndKeepsItsDuration() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(200.0, cause, FailureVia.PLAYER))

        rig.advance(200)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertEquals(3_000.0, rig.player.durationMs)
        assertEquals(
            listOf("duration", "size", "size", "status:READY", "ready", "status:HALTED", "playerError"),
            rig.events.names,
        )
    }

    @Test
    fun aSecondFailureFiresItsHandlerAgainWithoutASecondStatusEvent() = fxModel {
        val second: Throwable = IllegalStateException("second")
        val rig: Rig = rig(StatusScript().failAt(10.0, cause, FailureVia.PLAYER).failAt(20.0, second, FailureVia.PLAYER))

        rig.advance(30)

        assertEquals(listOf("status:HALTED", "playerError", "playerError"), rig.events.names)
        assertSame(second, rig.player.playerError)
    }

    @Test
    fun aFailureScriptedAtRuntimeArrivesAtItsDelayFromNow() = fxModel {
        val rig: Rig = readyRig()
        rig.advance(100)

        rig.onFx { rig.player.failAt(40.0, cause, FailureVia.MEDIA) }
        rig.advance(39)
        assertEquals(NativeStatus.READY, rig.player.status)
        rig.advance(1)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertEquals(190.0, rig.events.events.last().tMs)
    }

    @Test
    fun aFailureWithNoHandlersInstalledStillSetsTheErrorsForTheEarlyCheck() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(0.0, cause), installEvents = false)

        rig.advance(0)

        assertSame(cause, rig.player.playerError)
        assertSame(cause, rig.player.mediaError)
        assertTrue(rig.events.events.isEmpty())
    }

    // -- B2: status lag --

    @Test
    fun playTurnsPlayingOnlyAfterTheStatusLatency() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.play() }
        assertEquals(NativeStatus.READY, rig.player.status)
        rig.advance(29)
        assertEquals(NativeStatus.READY, rig.player.status)
        rig.advance(1)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertEquals(listOf("status:PLAYING"), rig.events.names)
        assertEquals(30.0, rig.events.events.single().tMs - 50.0)
    }

    @Test
    fun pauseTurnsPausedOnlyAfterTheStatusLatency() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(30)
        rig.events.clear()

        rig.onFx { rig.player.pause() }
        rig.advance(29)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        rig.advance(1)

        assertEquals(NativeStatus.PAUSED, rig.player.status)
        assertEquals(listOf("status:PAUSED"), rig.events.names)
    }

    @Test
    fun theStatusLatencyIsConfigurable() = fxModel {
        val rig: Rig = readyRig(StatusScript().apply { statusLatencyMs = 5.0 })

        rig.onFx { rig.player.play() }
        rig.advance(4)
        assertEquals(NativeStatus.READY, rig.player.status)
        rig.advance(1)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aPerCallLatencyOverridesTheDefaultForThatCallOnly() = fxModel {
        val rig: Rig = readyRig(StatusScript().statusLatency(NativeCall.PLAY, 2, 100.0))
        rig.onFx { rig.player.play() }
        rig.advance(30)
        rig.onFx { rig.player.pause() }
        rig.advance(30)
        assertEquals(NativeStatus.PAUSED, rig.player.status)

        rig.onFx { rig.player.play() }
        rig.advance(99)
        assertEquals(NativeStatus.PAUSED, rig.player.status)
        rig.advance(1)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aPerCallLatencyOfZeroStillLandsThroughTheQueueNotInline() = fxModel {
        val rig: Rig = readyRig(StatusScript().statusLatency(NativeCall.PLAY, 1, 0.0))

        rig.onFx { rig.player.play() }
        assertEquals(NativeStatus.READY, rig.player.status)
        rig.advance(0)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aPlayFollowedByAQuickPauseLandsInTheOrderIssued() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.play() }
        rig.advance(5)
        rig.onFx { rig.player.pause() }
        rig.advance(100)

        assertEquals(listOf("status:PLAYING", "status:PAUSED"), rig.events.names)
        assertEquals(NativeStatus.PAUSED, rig.player.status)
        assertEquals(listOf(30.0, 35.0), rig.events.events.map { it.tMs - 50.0 })
    }

    @Test
    fun aPlayIssuedBeforeReadyStartsRightAfterReady() = fxModel {
        val rig: Rig = rig()
        rig.advance(10)

        rig.onFx { rig.player.play() }
        rig.advance(100)

        assertEquals(
            listOf("duration", "size", "size", "status:READY", "ready", "status:PLAYING"),
            rig.events.names,
        )
        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aPauseIssuedBeforeReadyLeavesThePlayerPausedAfterReady() = fxModel {
        val rig: Rig = rig()

        rig.onFx { rig.player.pause() }
        rig.advance(100)

        assertEquals(NativeStatus.PAUSED, rig.player.status)
    }

    @Test
    fun aPlayOnAPlayerThatNeverGetsReadyNeverStarts() = fxModel {
        val rig: Rig = rig(StatusScript().neverReady())

        rig.onFx { rig.player.play() }
        rig.advance(10_000)

        assertEquals(NativeStatus.UNKNOWN, rig.player.status)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun theStatusEventFiresOnlyForAChangedStatus() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(30)
        rig.events.clear()

        rig.onFx { rig.player.play() }
        rig.advance(100)
        assertTrue(rig.events.events.isEmpty(), rig.events.names.toString())

        rig.onFx { rig.player.pause() }
        rig.advance(100)
        rig.onFx { rig.player.pause() }
        rig.advance(100)
        assertEquals(listOf("status:PAUSED"), rig.events.names)
    }

    @Test
    fun aCommandOnAHaltedPlayerChangesNothing() = fxModel {
        val rig: Rig = rig(StatusScript().failAt(10.0, cause, FailureVia.PLAYER))
        rig.advance(10)
        rig.events.clear()

        rig.onFx { rig.player.play() }
        rig.advance(1_000)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun aFailureWhileACommandIsPendingKeepsTheHaltedStatus() = fxModel {
        val rig: Rig = readyRig(StatusScript().failAt(80.0, cause, FailureVia.PLAYER))
        rig.onFx { rig.player.play() }

        rig.advance(1_000)

        assertEquals(NativeStatus.HALTED, rig.player.status)
        assertEquals(listOf("status:HALTED", "playerError"), rig.events.names)
    }

    // -- dispose --

    @Test
    fun disposeSetsDisposedAtOnceAndFiresTheStatusEvent() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.dispose() }

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertEquals(listOf("status:DISPOSED"), rig.events.names)
    }

    @Test
    fun aPendingTransitionIsDroppedByDispose() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.play() }
        rig.advance(10)

        rig.onFx { rig.player.dispose() }
        rig.events.clear()
        rig.advance(1_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertTrue(rig.events.events.isEmpty(), rig.events.names.toString())
    }

    @Test
    fun disposingTwiceFiresOneStatusEventAndLogsTheSecondAsNoEffect() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.dispose(); rig.player.dispose() }

        assertEquals(listOf("status:DISPOSED"), rig.events.names)
        assertEquals(listOf(true, false), rig.player.log.calls(NativeCall.DISPOSE).map { it.effective })
    }

    @Test
    fun commandsAfterDisposeAreLoggedAsNotEffectiveAndChangeNothing() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.dispose() }
        rig.events.clear()

        rig.onFx {
            rig.player.play()
            rig.player.pause()
            rig.player.seek(500.0)
            rig.player.rate = 2.0
        }
        rig.advance(1_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertEquals(1.0, rig.player.rate)
        assertTrue(rig.events.events.isEmpty())
        val after: List<NativeCallEntry> = rig.player.log.entries.takeLast(4)
        assertEquals(listOf(NativeCall.PLAY, NativeCall.PAUSE, NativeCall.SEEK, NativeCall.SET_RATE), after.map { it.call })
        assertTrue(after.none { it.effective }, rig.player.log.tail())
    }

    // -- B1: property semantics --

    @Test
    fun aRateWriteOfTheHeldValueIsNoChange() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        p.rate = 1.0

        assertEquals(1.0, p.rate)
        assertFalse(p.log.calls(NativeCall.SET_RATE).single().effective)
        assertTrue(p.log.rates().isEmpty())
    }

    @Test
    fun aRateChangeIsEffectiveAndTheLogRatesListsOnlyChanges() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        p.rate = 1.0
        p.rate = 3.0
        p.rate = 3.0
        p.rate = 1.0

        assertEquals(listOf(3.0, 1.0), p.log.rates())
        assertEquals(listOf(false, true, false, true), p.log.calls(NativeCall.SET_RATE).map { it.effective })
        assertEquals(1.0, p.rate)
    }

    @Test
    fun awayAndBackOnAHeldSpeedIsTwoRealChanges() = fxModel {
        val p: ScriptedNativePlayer = rig().player
        p.rate = 1.5

        p.rate = 1.0
        p.rate = 1.5

        assertEquals(listOf(1.5, 1.0, 1.5), p.log.rates())
    }

    @Test
    fun aVolumeWriteOfTheHeldValueIsNoChangeAndAChangeIsOne() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        p.volume = 1.0
        p.volume = 0.25
        p.volume = 0.25

        assertEquals(listOf(false, true, false), p.log.calls(NativeCall.SET_VOLUME).map { it.effective })
        assertEquals(0.25, p.volume)
    }

    @Test
    fun theCycleCountStartsAtOneAndIndefiniteIsMinusOne() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        assertEquals(1, p.cycleCount)
        p.cycleCount = 1
        p.cycleCount = -1
        p.cycleCount = -1

        assertEquals(-1, p.cycleCount)
        assertEquals(listOf(false, true, false), p.log.calls(NativeCall.SET_CYCLE_COUNT).map { it.effective })
        assertEquals(-1.0, p.log.calls(NativeCall.SET_CYCLE_COUNT).last().arg)
    }

    @Test
    fun aNaNRateWriteIsAChangeEveryTimeAsInAPrimitiveProperty() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        p.rate = Double.NaN
        p.rate = Double.NaN

        assertEquals(listOf(true, true), p.log.calls(NativeCall.SET_RATE).map { it.effective })
    }

    @Test
    fun aPropertyWriteAfterDisposeIsIgnored() = fxModel {
        val rig: Rig = readyRig()
        rig.onFx { rig.player.dispose() }

        rig.player.volume = 0.1
        rig.player.cycleCount = 5

        assertEquals(1.0, rig.player.volume)
        assertEquals(1, rig.player.cycleCount)
    }

    // -- the call log --

    @Test
    fun everyMemberCallIsLoggedInTheOrderMadeBeforeItActs() = fxModel {
        val rig: Rig = rig(installEvents = false)
        val p: ScriptedNativePlayer = rig.player
        rig.advance(50)

        rig.onFx {
            p.setEvents(rig.events)
            p.rate = 2.0
            p.volume = 0.5
            p.cycleCount = -1
            p.play()
            p.seek(250.0)
            p.startPulse()
            p.snapshotInto(IntArray(4), 2, 2)
            p.stopPulse()
            p.pause()
            p.detachView()
            p.clearEndOfMedia()
            p.dispose()
        }

        assertEquals(
            listOf(
                NativeCall.SET_EVENTS, NativeCall.SET_RATE, NativeCall.SET_VOLUME, NativeCall.SET_CYCLE_COUNT, NativeCall.PLAY,
                NativeCall.SEEK, NativeCall.START_PULSE, NativeCall.SNAPSHOT, NativeCall.STOP_PULSE, NativeCall.PAUSE,
                NativeCall.DETACH_VIEW, NativeCall.CLEAR_END_OF_MEDIA, NativeCall.DISPOSE,
            ),
            p.log.entries.map { it.call },
        )
        assertEquals(250.0, p.log.calls(NativeCall.SEEK).single().arg)
        assertEquals(2.0, p.log.calls(NativeCall.SET_RATE).single().arg)
    }

    @Test
    fun anEntryCarriesTheStatusBeforeTheCallHadAnyEffect() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.play() }
        rig.advance(30)
        rig.onFx { rig.player.pause() }

        assertEquals(listOf(NativeStatus.READY, NativeStatus.PLAYING), rig.player.log.entries.drop(1).map { it.status })
    }

    @Test
    fun logTimestampsAreVirtualMillisecondsSinceTheHostStarted() = fxModel {
        val rig: Rig = rig()
        rig.advance(70)

        rig.onFx { rig.player.play() }
        rig.advance(13)
        rig.onFx { rig.player.pause() }

        assertEquals(listOf(0.0, 70.0, 83.0), rig.player.log.entries.map { it.tMs })
    }

    @Test
    fun seekIsLoggedAndMovesNoPlayheadWithoutATimePart() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.seek(1_500.0) }

        assertEquals(0.0, rig.player.currentTimeMs)
        assertEquals(1_500.0, rig.player.log.calls(NativeCall.SEEK).single().arg)
    }

    @Test
    fun thePlayheadStartAndBufferAreValuesAPartOrATestMoves() = fxModel {
        val p: ScriptedNativePlayer = rig().player
        assertEquals(0.0, p.currentTimeMs)
        assertEquals(0.0, p.startTimeMs)
        assertEquals(0.0, p.bufferProgressMs)

        p.positionMs = 420.0
        p.startMs = 10.0
        p.bufferMs = 900.0

        assertEquals(420.0, p.currentTimeMs)
        assertEquals(10.0, p.startTimeMs)
        assertEquals(900.0, p.bufferProgressMs)
        p.rate = 2.0
        assertEquals(420.0, p.log.entries.last().positionMs)
    }

    @Test
    fun pulseAndViewAndEndOfMediaFlagsFollowTheirCalls() = fxModel {
        val p: ScriptedNativePlayer = rig().player
        assertFalse(p.pulsing)
        assertTrue(p.viewAttached && p.endOfMediaHandlerInstalled)

        p.startPulse()
        assertTrue(p.pulsing)
        p.stopPulse()
        p.detachView()
        p.clearEndOfMedia()

        assertFalse(p.pulsing || p.viewAttached || p.endOfMediaHandlerInstalled)
    }

    @Test
    fun theSnapshotLeavesTheCallersArrayUntouchedWithoutAPicturePart() = fxModel {
        val p: ScriptedNativePlayer = rig().player
        val pixels: IntArray = IntArray(6) { 7 }

        p.snapshotInto(pixels, 3, 2)

        assertEquals(List(6) { 7 }, pixels.toList())
    }

    // -- end of media delivery --

    @Test
    fun deliverEndOfMediaReachesTheListenerOnlyWhileTheHandlerIsInstalled() = fxModel {
        val rig: Rig = readyRig()

        rig.onFx { rig.player.deliverEndOfMedia() }
        rig.onFx { rig.player.clearEndOfMedia() }
        rig.onFx { rig.player.deliverEndOfMedia() }
        assertEquals(listOf("endOfMedia"), rig.events.names)

        rig.onFx { rig.player.deliverEndOfMedia(ignoreClearedHandler = true) }
        assertEquals(listOf("endOfMedia", "endOfMedia"), rig.events.names)
    }

    // -- the extension slots --

    private class RecordingPart(val name: String, val journal: MutableList<String>) : ModelPart {
        override fun onReady(player: ScriptedNativePlayer) { journal += "$name.ready:${player.status}" }
        override fun onPlay(player: ScriptedNativePlayer) { journal += "$name.play:${player.status}" }
        override fun onPause(player: ScriptedNativePlayer) { journal += "$name.pause" }
        override fun onSeek(player: ScriptedNativePlayer, millis: Double) { journal += "$name.seek:$millis" }
        override fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) { journal += "$name.rate:$old>$new" }
        override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {
            journal += "$name.status:$old>$new:${player.status}"
        }
        override fun onDispose(player: ScriptedNativePlayer) { journal += "$name.dispose:${player.status}" }
        override fun onStartPulse(player: ScriptedNativePlayer) { journal += "$name.startPulse" }
        override fun onStopPulse(player: ScriptedNativePlayer) { journal += "$name.stopPulse" }
        override fun onSnapshot(player: ScriptedNativePlayer, pixels: IntArray, width: Int, height: Int) {
            journal += "$name.snapshot:${width}x$height"
        }
    }

    @Test
    fun theFourSlotsDefaultToTheEmptyPart() = fxModel {
        val p: ScriptedNativePlayer = rig().player

        assertSame(ModelPart.None, p.time)
        assertSame(ModelPart.None, p.hazard)
        assertSame(ModelPart.None, p.loop)
        assertSame(ModelPart.None, p.picture)
    }

    @Test
    fun everyPartHearsEveryCallInTheOrderTimeHazardLoopPicture() = fxModel {
        val journal: MutableList<String> = mutableListOf()
        val rig: Rig = rig()
        rig.player.time = RecordingPart("time", journal)
        rig.player.hazard = RecordingPart("hazard", journal)
        rig.player.loop = RecordingPart("loop", journal)
        rig.player.picture = RecordingPart("picture", journal)
        rig.advance(50)
        journal.clear()

        rig.onFx { rig.player.seek(100.0) }

        assertEquals(listOf("time.seek:100.0", "hazard.seek:100.0", "loop.seek:100.0", "picture.seek:100.0"), journal)
    }

    @Test
    fun aPartSeesEachKindOfCallWithTheStateAfterTheOwnChange() = fxModel {
        val journal: MutableList<String> = mutableListOf()
        val rig: Rig = rig()
        rig.player.time = RecordingPart("t", journal)

        rig.advance(50)
        rig.onFx {
            rig.player.rate = 2.0
            rig.player.play()
        }
        rig.advance(30)
        rig.onFx {
            rig.player.pause()
            rig.player.startPulse()
            rig.player.snapshotInto(IntArray(2), 2, 1)
            rig.player.stopPulse()
            rig.player.dispose()
        }

        assertEquals(
            listOf(
                "t.ready:UNKNOWN", "t.status:UNKNOWN>READY:READY", "t.rate:1.0>2.0", "t.play:READY",
                "t.status:READY>PLAYING:PLAYING", "t.pause", "t.startPulse", "t.snapshot:2x1", "t.stopPulse",
                "t.status:PLAYING>DISPOSED:DISPOSED", "t.dispose:DISPOSED",
            ),
            journal,
        )
    }

    @Test
    fun aPartIsNotToldOfARateWriteThatChangedNothing() = fxModel {
        val journal: MutableList<String> = mutableListOf()
        val p: ScriptedNativePlayer = rig().player
        p.hazard = RecordingPart("h", journal)

        p.rate = 1.0
        p.rate = 4.0
        p.rate = 4.0

        assertEquals(listOf("h.rate:1.0>4.0"), journal)
    }

    @Test
    fun aPartIsNotToldOfCallsOnADisposedPlayer() = fxModel {
        val journal: MutableList<String> = mutableListOf()
        val rig: Rig = readyRig()
        rig.player.time = RecordingPart("t", journal)
        rig.onFx { rig.player.dispose() }
        journal.clear()

        rig.onFx {
            rig.player.play()
            rig.player.pause()
            rig.player.seek(1.0)
            rig.player.dispose()
        }

        assertTrue(journal.isEmpty(), journal.toString())
    }

    @Test
    fun aPartUpdatesItsStateBeforeTheListenerReadsTheStatusEvent() = fxModel {
        val rig: Rig = readyRig()
        val seen: MutableList<Double> = mutableListOf()
        rig.player.time = object : ModelPart {
            override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {
                if (new == NativeStatus.PLAYING) player.positionMs = 123.0
            }
        }
        rig.onFx {
            rig.player.setEvents(
                object : NativeEvents by rig.events {
                    override fun onStatus(status: NativeStatus) {
                        seen += rig.player.currentTimeMs
                    }
                },
            )
            rig.player.play()
        }

        rig.advance(30)

        assertEquals(listOf(123.0), seen)
    }

    @Test
    fun aPartCanDeliverEventsThroughThePlayersListener() = fxModel {
        val rig: Rig = readyRig()
        rig.player.time = object : ModelPart {
            override fun onSeek(player: ScriptedNativePlayer, millis: Double) {
                player.positionMs = millis
                player.events?.onCurrentTime()
            }
        }

        rig.onFx { rig.player.seek(700.0) }

        assertEquals(700.0, rig.player.currentTimeMs)
        assertEquals(listOf("currentTime"), rig.events.names)
    }
}
