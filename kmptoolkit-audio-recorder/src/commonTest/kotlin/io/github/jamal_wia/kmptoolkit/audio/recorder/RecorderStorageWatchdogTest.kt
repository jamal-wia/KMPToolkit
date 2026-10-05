package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

private const val MIB: Long = 1024L * 1024L

/**
 * The free-space watchdog: while recording, the ticker looks at the volume at most every couple of
 * seconds, and a volume that has fallen below the reserve the library keeps for finalizing ends the
 * recording with [InterruptionReason.StorageLow] while there is still room to close the file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderStorageWatchdogTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `the reserve is half the minimum but within 2 MiB and the minimum itself`() {
        assertNull(freeSpaceReserveBytes(0), "0 turns the whole storage check off")
        assertEquals(4 * MIB, freeSpaceReserveBytes(8 * MIB), "the default minimum")
        assertEquals(2 * MIB, freeSpaceReserveBytes(3 * MIB), "never below 2 MiB")
        assertEquals(50 * MIB, freeSpaceReserveBytes(100 * MIB))
        assertEquals(1L, freeSpaceReserveBytes(1L), "never above the minimum")
        assertEquals(MIB, freeSpaceReserveBytes(MIB), "never above the minimum")
    }

    @Test
    fun `a volume that falls below the reserve ends the recording as storage low`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.fileSystem.freeSpace = 1 * MIB

            // Rate limited: one second in, the check has not come due yet.
            assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
            passTime(fixture, 1.seconds)

            assertEquals(
                RecorderState.Interrupted(RecordedFile(path, 2.seconds), InterruptionReason.StorageLow),
                fixture.recorder.state.value,
            )
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "finalized while there is room")
        }

    @Test
    fun `a volume above the reserve keeps recording`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.fileSystem.freeSpace = 5 * MIB

        repeat(5) { passTime(fixture, 1.seconds) }

        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
    }

    @Test
    fun `a platform that cannot report free space never ends a recording`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.fileSystem.freeSpace = -1

        repeat(5) { passTime(fixture, 1.seconds) }

        assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
    }

    @Test
    fun `a zero minimum switches the watchdog off and never asks the filesystem`() =
        runRecorderTest(AudioRecorderConfig(minimumFreeSpaceBytes = 0L)) { fixture ->
            val path: String = fixture.recording()
            fixture.fileSystem.freeSpace = 0L

            repeat(5) { passTime(fixture, 1.seconds) }

            assertEquals(RecorderState.Recording(path), fixture.recorder.state.value)
            assertEquals(emptyList(), fixture.fileSystem.freeSpaceQueries)
        }

    @Test
    fun `the watchdog polls at most once per poll interval`() = runRecorderTest { fixture ->
        fixture.recording()
        val queriesAfterPrepare: Int = fixture.fileSystem.freeSpaceQueries.size

        repeat(10) { passTime(fixture, 1.seconds) }

        val polls: Int = fixture.fileSystem.freeSpaceQueries.size - queriesAfterPrepare
        assertEquals(5, polls, "ten seconds at a two second interval")
    }

    @Test
    fun `the watchdog stops with the recording`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.pause()
        val queries: Int = fixture.fileSystem.freeSpaceQueries.size

        repeat(5) { passTime(fixture, 1.seconds) }

        assertEquals(queries, fixture.fileSystem.freeSpaceQueries.size, "nothing is captured while paused")
    }

    @Test
    fun `a volume that is low while paused ends the recording once resumed and checked`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.recorder.pause()
            fixture.fileSystem.freeSpace = 1 * MIB
            fixture.recorder.resume()

            passTime(fixture, 2.seconds)

            val state: RecorderState = fixture.recorder.state.value
            assertIs<RecorderState.Interrupted>(state)
            assertEquals(path, state.recording.path)
            assertEquals(InterruptionReason.StorageLow, state.reason)
            assertEquals(3.seconds, state.recording.duration)
        }

    @Test
    fun `prepare gives the engine the size limit that leaves the reserve free`() =
        runRecorderTest { fixture ->
            fixture.fileSystem.freeSpace = 100 * MIB

            fixture.prepared()

            assertEquals(listOf<Long?>(96 * MIB), fixture.engine.preparedMaxFileSizes)
        }

    @Test
    fun `prepare gives the engine no size limit when the volume cannot say or the check is off`() {
        runRecorderTest { fixture ->
            fixture.fileSystem.freeSpace = -1
            fixture.prepared()
            assertEquals(listOf<Long?>(null), fixture.engine.preparedMaxFileSizes)
        }
        runRecorderTest(AudioRecorderConfig(minimumFreeSpaceBytes = 0L)) { fixture ->
            fixture.prepared()
            assertEquals(listOf<Long?>(null), fixture.engine.preparedMaxFileSizes)
        }
    }
}
