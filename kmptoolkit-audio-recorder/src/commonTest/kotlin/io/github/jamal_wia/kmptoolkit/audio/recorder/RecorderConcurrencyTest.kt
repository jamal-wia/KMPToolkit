package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

private const val MIB: Long = 1024L * 1024L

/**
 * Interleavings the threading contract allows, derived from the [AudioRecorder] KDoc: `release()`
 * may land while `prepare()` is suspended, a pause may land while the free-space watchdog is
 * reading the disk, the level meter must never overlap the transitions that stop the engine, and
 * `Preparing` never outlives a call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderConcurrencyTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    // --- release vs prepare ---

    @Test
    fun `release while the engine is preparing does not drive the engine`() =
        runRecorderTest { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.engine.prepareGate = gate
            val preparing: Job = launch { fixture.recorder.prepare() }
            runCurrent()
            assertTrue(fixture.engine.inPrepare, "the engine's prepare must be running")

            fixture.recorder.release()
            gate.complete(Unit)
            preparing.join()

            assertEquals(
                0,
                fixture.engine.releasesDuringPrepare,
                "release() drove the engine while its prepare was still running on the worker",
            )
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertFalse(fixture.engine.inPrepare)
        }

    @Test
    fun `a release that lands before the worker reaches the engine never opens the microphone`() =
        runRecorderTest { fixture ->
            // Lands on the worker after beginPrepare took Preparing and before engine.prepare ran.
            fixture.fileSystem.onEnsureWritableDirectory = { fixture.recorder.release() }

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertEquals(
                RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
                result.errorOrNull(),
            )
            assertTrue(
                fixture.engine.calls.none { it.startsWith("prepare(") },
                "engine.prepare ran for a recorder that was already released: ${fixture.engine.calls}",
            )
        }

    // --- the watchdog vs pause ---

    @Test
    fun `a low free-space reading taken before a pause does not end the paused recording`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            fixture.fileSystem.freeSpace = 1 * MIB
            // The pause lands while the watchdog is reading the disk, with the lock free.
            fixture.fileSystem.onFreeSpaceQuery = {
                fixture.fileSystem.onFreeSpaceQuery = {}
                fixture.recorder.pause()
            }

            passTime(fixture, 1.seconds)

            assertIs<RecorderState.Paused>(
                fixture.recorder.state.value,
                "the stale reading ended a recording the user had paused",
            )
            assertEquals(path, (fixture.recorder.state.value as RecorderState.Paused).outputPath)
        }

    // --- a throw before the engine runs ---

    @Test
    fun `a platform call that throws before the engine runs does not leave the recorder preparing`() =
        runRecorderTest { fixture ->
            fixture.fileSystem.ensureWritableDirectoryFailure = IllegalStateException("no app dir")

            val result: RecorderResult<String> = fixture.recorder.prepare()

            val error: RecorderError? = result.errorOrNull()
            assertIs<RecorderError.EngineFailure>(error)
            assertEquals(RecorderOperation.PREPARE, error.operation)
            assertIs<RecorderState.Failed>(fixture.recorder.state.value)

            fixture.fileSystem.ensureWritableDirectoryFailure = null
            assertTrue(fixture.recorder.prepare().isSuccess, "a second prepare must be accepted")
        }

    // --- the meter vs the transitions that stop the engine ---

    @Test
    fun `a meter sample never overlaps the engine calls of a transition that ends the session`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val watcher: Job = backgroundScope.launch { fixture.recorder.level.collect { } }
            runCurrent()
            // An event arrives from inside the engine's peak call: it must wait for the sample to
            // finish rather than detach the listener and stop the engine underneath it.
            fixture.engine.onPeak = {
                fixture.engine.onPeak = {}
                fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            }

            advanceTimeBy(AudioRecorderConfig().levelUpdateInterval)
            runCurrent()
            watcher.cancel()

            assertContentEquals(
                emptyList(),
                fixture.engine.callsDuringPeak,
                "the engine was driven while a meter sample was being taken",
            )
        }

    // --- an explicit output path that already exists ---

    @Test
    fun `a failed prepare does not delete a file that already existed at the explicit path`() =
        runRecorderTest { fixture ->
            val path = "/data/app/memo.m4a"
            fixture.fileSystem.existingPaths += path
            fixture.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("bad config")

            val result: RecorderResult<String> = fixture.recorder.prepare(path)

            assertIs<RecorderError.EngineFailure>(result.errorOrNull())
            assertFalse(path in fixture.fileSystem.deletedPaths, "the caller's existing file was deleted")
        }

    @Test
    fun `a failed prepare still deletes the file it created itself`() = runRecorderTest { fixture ->
        val path = "/data/app/new.m4a"
        fixture.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("bad config")

        fixture.recorder.prepare(path)

        assertContentEquals(listOf(path), fixture.fileSystem.deletedPaths)
    }
}
