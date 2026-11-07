package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy

/**
 * Resource starvation: the in-process output path registry is process-wide and outlives every
 * recorder, so an entry that is not let go of is a leak per recording. The documented guarantee is
 * that the registry never holds more entries than there are live recorders and in-flight prepares,
 * "never one per recording". Every way a session can end is run against its own recorder, and once
 * every recorder is released nothing of this test may remain in the registry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderPathRegistryStarvationTest {

    private fun ownKeys(): List<String> {
        val field = OutputPathOwners::class.java.getDeclaredField("slots")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(OutputPathOwners) as MutableStateFlow<Map<String, Any>>
        return flow.value.keys.filter { it.startsWith(PREFIX) }
    }

    private suspend fun TestScope.scenario(
        name: String,
        body: suspend TestScope.(RecorderFixture, String) -> Unit,
    ) {
        val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
        val path = "$PREFIX$name.m4a"
        try {
            body(fixture, path)
        } finally {
            fixture.recorder.release()
        }
        runCurrent()
    }

    @Test
    fun `no way of ending a session leaves an entry in the path registry`() = runTest {
        // 1 prepared, released
        scenario("ready-release") { f, p -> f.prepared(p) }
        // 2 recorded, stopped, released
        scenario("stop") { f, p ->
            f.recording(p)
            f.recorder.stop()
        }
        // 3 recorded, cancelled
        scenario("cancel") { f, p ->
            f.recording(p)
            f.recorder.cancel()
        }
        // 4 re-prepare over and over
        scenario("reprepare") { f, p ->
            repeat(5) { f.prepared(p) }
            f.recording(p)
            f.recorder.stop()
            repeat(3) { f.prepared(p) }
        }
        // 5 directory not writable (claim taken, then failure)
        scenario("unwritable") { f, p ->
            f.fileSystem.unwritableDirectories += "/starve"
            f.recorder.prepare(p)
        }
        // 6 engine failure on prepare
        scenario("engine-fail") { f, p ->
            f.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("boom")
            f.recorder.prepare(p)
        }
        // 7 engine failure on prepare over an existing file (the file is the caller's)
        scenario("engine-fail-existing") { f, p ->
            f.fileSystem.existingPaths += p
            f.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("boom")
            f.recorder.prepare(p)
        }
        // 8 prepare cancelled while the engine opens
        scenario("cancel-prepare") { f, p ->
            f.engine.prepareGate = CompletableDeferred()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { f.recorder.prepare(p) }
            runCurrent()
            job.cancel()
            f.engine.prepareGate?.complete(Unit)
            runCurrent()
            job.join()
        }
        // 9 release while the engine opens, existing file
        scenario("release-in-prepare") { f, p ->
            f.fileSystem.existingPaths += p
            f.engine.prepareGate = CompletableDeferred()
            val job = launch { f.recorder.prepare(p) }
            runCurrent()
            f.recorder.release()
            f.engine.prepareGate?.complete(Unit)
            runCurrent()
            job.join()
        }
        // 10 system ends a recording; kept; released
        scenario("interrupted") { f, p ->
            f.recording(p)
            f.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()
        }
        // 11 system ends a recording; cancelled afterwards
        scenario("interrupted-cancel") { f, p ->
            f.recording(p)
            f.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()
            f.recorder.cancel()
        }
        // 12 system ends a prepared recorder
        scenario("ready-lost") { f, p ->
            f.prepared(p)
            f.engine.emit(EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted))
            runCurrent()
        }
        // 13 start fails
        scenario("start-fails") { f, p ->
            f.prepared(p)
            f.engine.failures[RecorderOperation.START] = IllegalStateException("no mic")
            f.recorder.start()
        }
        // 14 stop fails, file kept, then cancelled
        scenario("stop-fails-cancel") { f, p ->
            f.recording(p)
            f.engine.failures[RecorderOperation.STOP] = IllegalStateException("moov")
            f.recorder.stop()
            f.recorder.cancel()
        }
        // 15 stop fails, released while Failed
        scenario("stop-fails-release") { f, p ->
            f.recording(p)
            f.engine.failures[RecorderOperation.STOP] = IllegalStateException("moov")
            f.recorder.stop()
        }
        // 16 release while a stop is being finalized
        scenario("release-in-stop") { f, p ->
            f.recording(p)
            f.engine.onStop = { f.recorder.release() }
            f.recorder.stop()
            runCurrent()
        }
        // 17 release while a cancel is being finalized
        scenario("release-in-cancel") { f, p ->
            f.recording(p)
            f.engine.onStop = { f.recorder.release() }
            f.recorder.cancel()
            runCurrent()
        }
        // 18 storage low while recording
        scenario("storage-low") { f, p ->
            f.recording(p)
            f.fileSystem.freeSpace = 0L
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(emptyList(), ownKeys(), "entries leaked into the path registry")
    }

    private companion object {
        const val PREFIX: String = "/starve/"
    }
}
