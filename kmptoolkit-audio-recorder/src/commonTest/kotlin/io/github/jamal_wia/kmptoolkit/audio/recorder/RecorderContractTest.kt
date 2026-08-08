package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Promises the contract makes that no single operation's own test covers: the order the `prepare`
 * pre-checks run in, what happens when `release` lands in the middle of a `prepare`, which files
 * survive which teardown, and the guarantee that `state` does not emit on a duration tick.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderContractTest {

    // --- pre-check ordering ---

    @Test
    fun `permission is checked before storage`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.engine.permissionGranted = false
            fixture.fileSystem.unwritableDirectories += DEFAULT_DIRECTORY
            fixture.fileSystem.freeSpace = 0L

            assertEquals(
                RecorderResult.Failure(RecorderError.PermissionDenied),
                fixture.recorder.prepare(),
            )
            assertContentEquals(emptyList(), fixture.fileSystem.freeSpaceQueries)
        }
    }

    @Test
    fun `format support is checked before storage`() {
        val config = AudioRecorderConfig(format = AudioFormat.WAV, minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.engine.unsupportedFormats = setOf(AudioFormat.WAV)
            fixture.fileSystem.freeSpace = 0L

            assertEquals(
                RecorderResult.Failure(RecorderError.UnsupportedFormat(AudioFormat.WAV)),
                fixture.recorder.prepare(),
            )
            assertContentEquals(emptyList(), fixture.fileSystem.freeSpaceQueries)
        }
    }

    @Test
    fun `an unwritable directory is reported before free space is consulted`() {
        val config = AudioRecorderConfig(minimumFreeSpaceBytes = 1_000L)
        runRecorderTest(config) { fixture ->
            fixture.fileSystem.unwritableDirectories += DEFAULT_DIRECTORY
            fixture.fileSystem.freeSpace = 0L

            assertEquals(
                RecorderResult.Failure(RecorderError.DirectoryNotWritable(DEFAULT_DIRECTORY)),
                fixture.recorder.prepare(),
            )
            assertContentEquals(emptyList(), fixture.fileSystem.freeSpaceQueries)
        }
    }

    // --- re-preparing tears the previous recorder down ---

    @Test
    fun `re-preparing releases the previous native recorder before opening another`() =
        runRecorderTest { fixture ->
            fixture.prepared(outputPath = "/data/app/first.m4a")
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.prepared(outputPath = "/data/app/second.m4a")

            assertTrue(
                fixture.engine.releaseCount > releasesBefore,
                "a second prepare must not leave the first native recorder holding the microphone",
            )
        }

    @Test
    fun `re-preparing after a completed recording releases the previous native recorder`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.stop()
            val releasesBefore: Int = fixture.engine.releaseCount

            fixture.prepared(outputPath = "/data/app/second.m4a")

            assertTrue(fixture.engine.releaseCount > releasesBefore)
        }

    @Test
    fun `a re-prepare that fails a pre-check still discards the previously prepared file`() =
        runRecorderTest { fixture ->
            val first: String = fixture.prepared(outputPath = "/data/app/first.m4a")
            fixture.engine.permissionGranted = false

            assertEquals(
                RecorderResult.Failure(RecorderError.PermissionDenied),
                fixture.recorder.prepare(),
            )

            assertContentEquals(
                listOf(first),
                fixture.fileSystem.deletedPaths,
                "the file the previous prepare opened is this module's to clean up either way",
            )
        }

    // --- which files survive teardown ---

    @Test
    fun `release from ready deletes the file that was never recorded into`() =
        runRecorderTest { fixture ->
            val path: String = fixture.prepared()

            fixture.recorder.release()

            assertContentEquals(
                listOf(path),
                fixture.fileSystem.deletedPaths,
                "nothing can clean it up later — a released recorder can never prepare again",
            )
        }

    @Test
    fun `release from idle deletes nothing`() = runRecorderTest { fixture ->
        fixture.recorder.release()

        assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
    }

    @Test
    fun `release after a completed recording keeps the finished file`() =
        runRecorderTest { fixture ->
            fixture.recording()
            fixture.recorder.stop()

            fixture.recorder.release()

            assertContentEquals(emptyList(), fixture.fileSystem.deletedPaths)
        }

    // --- release racing an in-flight prepare ---

    @Test
    fun `releasing during a prepare leaves the recorder released rather than ready`() =
        runRecorderTest { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.engine.prepareGate = gate
            val preparing: Job = launch { fixture.recorder.prepare() }
            runCurrent()
            assertEquals(RecorderState.Preparing, fixture.recorder.state.value)

            fixture.recorder.release()
            gate.complete(Unit)
            preparing.join()

            assertEquals(
                RecorderState.Released,
                fixture.recorder.state.value,
                "Released is terminal — a late prepare must not resurrect the recorder",
            )
        }

    @Test
    fun `releasing during a prepare frees the recorder that prepare went on to create`() =
        runRecorderTest { fixture ->
            val gate = CompletableDeferred<Unit>()
            fixture.engine.prepareGate = gate
            val preparing: Job = launch { fixture.recorder.prepare() }
            runCurrent()
            fixture.recorder.release()
            val releasesAfterRelease: Int = fixture.engine.releaseCount

            gate.complete(Unit)
            preparing.join()

            assertTrue(
                fixture.engine.releaseCount > releasesAfterRelease,
                "the handle prepare created after release() must be freed, not leaked",
            )
            assertContentEquals(listOf(GENERATED_PATH), fixture.fileSystem.deletedPaths)
        }

    // --- a failed stop leaves the file findable ---

    @Test
    fun `a failed stop reports the file it left on disk`() = runRecorderTest { fixture ->
        val path: String = fixture.recording()
        fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("encoder died")

        fixture.recorder.stop()

        val state: RecorderState = fixture.recorder.state.value
        assertTrue(state is RecorderState.Failed)
        assertEquals(path, state.outputPath)
        assertEquals(path, fixture.recorder.state.value.outputPath)
    }

    @Test
    fun `a failure that left no file reports no path`() = runRecorderTest { fixture ->
        fixture.engine.permissionGranted = false

        fixture.recorder.prepare()

        assertEquals(
            RecorderState.Failed(RecorderError.PermissionDenied, outputPath = null),
            fixture.recorder.state.value,
        )
    }

    // --- state does not emit on a tick ---

    @Test
    fun `state emits on transitions only and never on a duration tick`() = runRecorderTest { fixture ->
        val observed: MutableList<RecorderState> = mutableListOf()
        val collector: Job = launch { fixture.recorder.state.collect { observed += it } }
        runCurrent()

        fixture.recording()
        runCurrent()
        val afterStart: Int = observed.size

        repeat(10) {
            fixture.timeSource += 1.seconds
            advanceTimeBy(1.seconds)
            runCurrent()
        }

        assertEquals(
            afterStart,
            observed.size,
            "ten ticks moved elapsed but must not have woken a state collector",
        )
        assertTrue(fixture.recorder.elapsed.value > Duration.ZERO, "the ticker really did run")
        collector.cancel()
    }
}
