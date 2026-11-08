package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

private const val MIB_COARSE: Long = 1024L * 1024L

/**
 * `durationUpdateInterval` is documented as how often `elapsed` republishes, and `minimumFreeSpaceBytes`
 * as what drives the free-space watchdog, "polled at most every two seconds". Nothing ties the watchdog
 * to the timer's rate, yet it rides on the timer's tick: a consumer who publishes the elapsed time once a
 * minute (a coarse label) or once an hour silently stretches the watchdog to that rate, and a volume
 * that fills in between ends the recording only after it ran out of room for finalizing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderCoarseTimerWatchdogTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `a coarse elapsed interval does not stretch the free space watchdog`() =
        runRecorderTest(AudioRecorderConfig(durationUpdateInterval = 1.minutes)) { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.fileSystem.freeSpace = 1 * MIB_COARSE

            repeat(6) { passTime(fixture, 1.seconds) }

            assertEquals(
                RecorderState.Interrupted(RecordedFile(path, 2.seconds), InterruptionReason.StorageLow).reasonOnly(),
                fixture.recorder.state.value.reasonOnly(),
                "seven seconds after the volume fell below the reserve the recording still runs",
            )
        }

    @Test
    fun `a watchdog wake between two publishes does not publish elapsed early`() =
        runRecorderTest(AudioRecorderConfig(durationUpdateInterval = 1.minutes)) { fixture ->
            fixture.recording()
            val before: Duration = fixture.recorder.elapsed.value

            repeat(59) { passTime(fixture, 1.seconds) }
            assertEquals(before, fixture.recorder.elapsed.value, "elapsed moved before the first minute was over")

            passTime(fixture, 1.seconds)
            assertEquals(1.minutes, fixture.recorder.elapsed.value, "elapsed was not published at one minute")
        }

    @Test
    fun `an elapsed interval that is not a multiple of the watchdog interval publishes without drift`() =
        runRecorderTest(AudioRecorderConfig(durationUpdateInterval = 3.seconds)) { fixture ->
            fixture.recording()
            val seen: MutableList<Duration> = mutableListOf()
            repeat(6) {
                passTime(fixture, 1.seconds)
                seen += fixture.recorder.elapsed.value
            }

            assertEquals(
                listOf(Duration.ZERO, Duration.ZERO, 3.seconds, 3.seconds, 3.seconds, 6.seconds),
                seen,
                "elapsed must publish at 3 s and 6 s and at no other second",
            )
        }

    private fun RecorderState.reasonOnly(): String = when (this) {
        is RecorderState.Interrupted -> "Interrupted(${this.reason})"
        else -> toString()
    }
}
