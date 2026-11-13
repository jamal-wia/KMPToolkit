package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [NativeCallLog] on its own: the model's assertions on what reached the native player rest on it. */
class NativeCallLogTest {

    private var clockMs: Double = 0.0
    private val log: NativeCallLog = NativeCallLog { clockMs }

    @Test
    fun anEntryKeepsTheCallTheArgumentTheTimeTheStatusAndThePosition() {
        clockMs = 12.5
        log.record(NativeCall.SEEK, 1_000.0, NativeStatus.PLAYING, 400.0, effective = true)

        val entry: NativeCallEntry = log.entries.single()
        assertEquals(NativeCallEntry(12.5, NativeCall.SEEK, 1_000.0, NativeStatus.PLAYING, 400.0, true), entry)
    }

    @Test
    fun entriesComeBackInTheOrderTheyWereRecorded() {
        log.record(NativeCall.PLAY, null, NativeStatus.READY, 0.0, true)
        clockMs = 5.0
        log.record(NativeCall.PAUSE, null, NativeStatus.PLAYING, 0.0, true)
        clockMs = 9.0
        log.record(NativeCall.DISPOSE, null, NativeStatus.PAUSED, 0.0, true)

        assertEquals(listOf(NativeCall.PLAY, NativeCall.PAUSE, NativeCall.DISPOSE), log.entries.map { it.call })
        assertEquals(listOf(0.0, 5.0, 9.0), log.entries.map { it.tMs })
        assertEquals(3, log.size)
    }

    @Test
    fun theEntriesListIsASnapshotThatLaterRecordsDoNotChange() {
        log.record(NativeCall.PLAY, null, NativeStatus.READY, 0.0, true)
        val before: List<NativeCallEntry> = log.entries
        log.record(NativeCall.PAUSE, null, NativeStatus.PLAYING, 0.0, true)

        assertEquals(1, before.size)
        assertEquals(2, log.entries.size)
    }

    @Test
    fun callsFiltersByKindAndKeepsNoEffectEntries() {
        log.record(NativeCall.SET_RATE, 2.0, NativeStatus.READY, 0.0, true)
        log.record(NativeCall.PLAY, null, NativeStatus.READY, 0.0, true)
        log.record(NativeCall.SET_RATE, 2.0, NativeStatus.PLAYING, 0.0, false)

        assertEquals(listOf(true, false), log.calls(NativeCall.SET_RATE).map { it.effective })
        assertEquals(1, log.calls(NativeCall.PLAY).size)
        assertTrue(log.calls(NativeCall.SEEK).isEmpty())
    }

    @Test
    fun ratesListsOnlyTheEffectiveRateWritesInOrder() {
        log.record(NativeCall.SET_RATE, 1.5, NativeStatus.READY, 0.0, true)
        log.record(NativeCall.SET_RATE, 1.5, NativeStatus.READY, 0.0, false)
        log.record(NativeCall.SET_VOLUME, 0.5, NativeStatus.READY, 0.0, true)
        log.record(NativeCall.SET_RATE, 1.0, NativeStatus.READY, 0.0, true)

        assertEquals(listOf(1.5, 1.0), log.rates())
    }

    @Test
    fun tailShowsTheLastEntriesAndCountsTheEarlierOnes() {
        for (i in 0 until 25) log.record(NativeCall.SEEK, i.toDouble(), NativeStatus.PAUSED, 0.0, true)

        val tail: String = log.tail(3)

        assertTrue(tail.contains("25 entries, 22 earlier omitted"), tail)
        assertTrue(tail.contains("SEEK(24)") && tail.contains("SEEK(22)"), tail)
        assertFalse(tail.contains("SEEK(21)"), tail)
        assertEquals(1 + 3, tail.lines().size, tail)
    }

    @Test
    fun tailWithTheDefaultCountShowsTwentyEntries() {
        for (i in 0 until 30) log.record(NativeCall.SEEK, i.toDouble(), NativeStatus.PAUSED, 0.0, true)

        assertEquals(1 + NativeCallLog.DEFAULT_TAIL, log.tail().lines().size)
        assertEquals(20, NativeCallLog.DEFAULT_TAIL)
    }

    @Test
    fun tailOfAShortLogSaysNothingOmitted() {
        log.record(NativeCall.PLAY, null, NativeStatus.READY, 0.0, true)

        assertFalse(log.tail().contains("omitted"), log.tail())
    }

    @Test
    fun anEntryThatHadNoEffectSaysSoInItsText() {
        log.record(NativeCall.SET_RATE, 1.0, NativeStatus.READY, 0.0, false)

        assertTrue(log.toString().contains("SET_RATE(1)") && log.toString().contains("[no effect]"), log.toString())
    }

    @Test
    fun aTailOfZeroEntriesIsRefused() {
        assertFailsWith<IllegalArgumentException> { log.tail(0) }
    }
}
