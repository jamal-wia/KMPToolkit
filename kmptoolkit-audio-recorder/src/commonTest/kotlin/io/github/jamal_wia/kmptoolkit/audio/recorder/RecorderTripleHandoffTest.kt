package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Three recorders on one explicit path where the middle one commits while the newest one is still
 * preparing, and the newest one then fails. The middle recorder's takeover was made final by its own
 * commit, so nothing may ever hand the path back to the first recorder and let its `release()` delete
 * the middle recorder's finished recording.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTripleHandoffTest {

    private val path = "/data/rec/triple.m4a"

    @Test
    fun `registry - a claim that committed under a pending newer claim stays final when the newer one is released`() =
        runTest(timeout = 10.seconds) {
            val p = "/owners/triple-registry.m4a"
            val a = OutputPathClaim()
            val b = OutputPathClaim()
            val c = OutputPathClaim()
            OutputPathOwners.claim(p, a)
            OutputPathOwners.commit(p, a)
            OutputPathOwners.claim(p, b)
            OutputPathOwners.claim(p, c)
            OutputPathOwners.commit(p, b) // b's attempt succeeded while c was still running
            OutputPathOwners.release(c) // c failed

            OutputPathOwners.release(b) // b's session ends (its file is kept or deleted by b)

            assertFalse(
                OutputPathOwners.deleteIfOwner(p, a) { error("a was displaced by a committed claim and must not delete") },
                "a regained ownership of a path whose takeover b had already committed",
            )
            OutputPathOwners.release(a)
        }

    private fun TestScope.recorder(engine: FakeRecorderEngine, fileSystem: FakeRecordingFileSystem) =
        DefaultAudioRecorder(
            engine = engine,
            fileSystem = fileSystem,
            config = AudioRecorderConfig(),
            workerContext = StandardTestDispatcher(testScheduler),
            epochClock = EpochClock { 1L },
        )

    @Test
    fun `releasing the first recorder never deletes the finished recording of the second when the third failed`() =
        runTest(timeout = 10.seconds) {
            val fileSystem = FakeRecordingFileSystem()
            val first = recorder(FakeRecorderEngine(), fileSystem)
            val secondEngine = FakeRecorderEngine().also { it.prepareGate = CompletableDeferred() }
            val second = recorder(secondEngine, fileSystem)
            val thirdEngine = FakeRecorderEngine().also {
                it.prepareGate = CompletableDeferred()
                it.failures[RecorderOperation.PREPARE] = IllegalStateException("microphone busy")
            }
            val third = recorder(thirdEngine, fileSystem)
            try {
                first.prepare(path)
                fileSystem.existingPaths += path
                val secondPrepare = launch { second.prepare(path) }
                runCurrent()
                val thirdPrepare = launch { third.prepare(path) }
                runCurrent()
                secondEngine.prepareGate!!.complete(Unit)
                runCurrent()
                assertTrue(second.state.value is RecorderState.Ready, "second was ${second.state.value}")
                assertTrue(second.start().isSuccess)
                assertTrue(second.stop().isSuccess)
                thirdEngine.prepareGate!!.complete(Unit)
                runCurrent()
                secondPrepare.join()
                thirdPrepare.join()
                assertTrue(third.state.value !is RecorderState.Ready, "third was ${third.state.value}")

                second.release()
                first.release()

                assertEquals(
                    emptyList(),
                    fileSystem.deletedPaths,
                    "the first recorder's release deleted the second recorder's finished recording",
                )
            } finally {
                first.release()
                second.release()
                third.release()
            }
        }
}
