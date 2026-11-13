package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecordingFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Round 10, hostile consumer: collectors that throw or cancel mid-emission, a state collector that calls back
 * into the recorder from inside the publication of a state (an unconfined collector runs on the publisher's own
 * stack), collection after release, and extreme values in the configuration. Virtual time only, no real file,
 * no microphone; the same class runs on the Android host and on the iOS simulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderHostileConsumerR10Test {

    private val ops: List<String> = listOf("start", "pause", "resume", "stop", "cancel", "prepare", "release")
    private val states: List<String> = listOf("Ready", "Recording", "Paused", "Completed", "Interrupted", "Failed")

    private suspend fun TestScope.reach(fixture: RecorderFixture, target: String) {
        when (target) {
            "Ready" -> fixture.prepared()
            "Recording" -> fixture.recording()
            "Paused" -> { fixture.recording(); fixture.recorder.pause() }
            "Completed" -> { fixture.recording(); fixture.recorder.stop() }
            "Interrupted" -> {
                fixture.recording()
                fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.StorageLow))
            }
            "Failed" -> {
                fixture.prepared()
                fixture.engine.failures[RecorderOperation.START] = IllegalStateException("scripted start failure")
                fixture.recorder.start()
                fixture.engine.failures.clear()
            }
            else -> error(target)
        }
        runCurrent()
    }

    private suspend fun call(recorder: AudioRecorder, op: String): RecorderResult<*> = when (op) {
        "start" -> recorder.start()
        "pause" -> recorder.pause()
        "resume" -> recorder.resume()
        "stop" -> recorder.stop()
        "cancel" -> recorder.cancel()
        "prepare" -> recorder.prepare()
        "release" -> { recorder.release(); RecorderResult.Success(Unit) }
        else -> error(op)
    }

    private fun summary(result: RecorderResult<*>?): String = when (result) {
        null -> "no answer"
        is RecorderResult.Success -> "ok"
        is RecorderResult.Failure -> when (val error: RecorderError = result.error) {
            is RecorderError.IllegalState -> "IllegalState(${error.state::class.simpleName},${error.operation})"
            is RecorderError.EngineFailure -> "EngineFailure(${error.operation})"
            else -> error::class.simpleName.toString()
        }
    }

    private fun stateName(state: RecorderState): String = state::class.simpleName.toString()

    @Test
    fun `a collector that calls back into the recorder gets the answer a plain call gets in that state`() {
        val mismatches: MutableList<String> = mutableListOf()
        for (target: String in states) for (op: String in ops) {
            var plain: String? = null
            var plainFinal: String? = null
            runRecorderTest { fixture ->
                reach(fixture, target)
                plain = summary(call(fixture.recorder, op))
                runCurrent()
                plainFinal = stateName(fixture.recorder.state.value)
            }
            var reentrant: RecorderResult<*>? = null
            var thrown: Throwable? = null
            var reentrantFinal: String? = null
            runRecorderTest { fixture ->
                var fired = false
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.recorder.state.collect { state: RecorderState ->
                        if (!fired && stateName(state) == target) {
                            fired = true
                            try {
                                reentrant = call(fixture.recorder, op)
                            } catch (e: Throwable) {
                                thrown = e
                            }
                        }
                    }
                }
                reach(fixture, target)
                runCurrent()
                reentrantFinal = stateName(fixture.recorder.state.value)
                // nothing may be left holding the engine
                fixture.recorder.release()
                assertEquals(RecorderState.Released, fixture.recorder.state.value, "$target/$op: release after the re-entry")
            }
            if (thrown != null) mismatches += "$target/$op: the re-entrant call threw $thrown"
            else if (summary(reentrant) != plain || reentrantFinal != plainFinal) {
                mismatches += "$target/$op: plain ${plain} then $plainFinal; re-entrant ${summary(reentrant)} then $reentrantFinal"
            }
        }
        assertEquals(emptyList(), mismatches, "re-entry from a state collector differs from a plain call:\n" + mismatches.joinToString("\n"))
    }

    @Test
    fun `a collector that throws on every state leaves the recorder working`() = runRecorderTest { fixture ->
        val caught: MutableList<Throwable> = mutableListOf()
        val scope = CoroutineScope(
            SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> caught += e },
        )
        repeat(3) {
            scope.launch { fixture.recorder.state.collect { throw IllegalStateException("hostile collector on $it") } }
        }
        fixture.recorder.level.let { flow -> scope.launch { flow.collect { throw IllegalStateException("hostile level") } } }
        fixture.recorder.elapsed.let { flow -> scope.launch { flow.collect { throw IllegalStateException("hostile elapsed") } } }

        val path: String = fixture.recording()
        fixture.timeSource += 2.seconds
        advanceTimeBy(2.seconds)
        runCurrent()
        val stopped: RecorderResult<RecordedFile> = fixture.recorder.stop()

        assertEquals(RecorderResult.Success(RecordedFile(path, 2.seconds)), stopped)
        assertEquals(RecorderState.Completed(RecordedFile(path, 2.seconds)), fixture.recorder.state.value)
        assertTrue(caught.isNotEmpty(), "the hostile collectors ran")
        scope.cancel()
    }

    @Test
    fun `a collector that cancels itself mid recording stops the metering and a new one restarts it`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val first: kotlinx.coroutines.Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.level.collect { }
            }
            advanceTimeBy(500)
            runCurrent()
            val whileCollected: Int = fixture.engine.peakCalls
            assertTrue(whileCollected > 0, "metering runs while observed")

            first.cancel()
            runCurrent()
            val afterCancel: Int = fixture.engine.peakCalls
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(afterCancel, fixture.engine.peakCalls, "no collector, no microphone sampling")

            val second: kotlinx.coroutines.Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.level.collect { }
            }
            advanceTimeBy(500)
            runCurrent()
            assertTrue(fixture.engine.peakCalls > afterCancel, "a new collector restarts the metering")
            second.cancel()
        }

    @Test
    fun `collecting every flow of a released recorder starts no work and ends in Released`() = runRecorderTest { fixture ->
        fixture.recording()
        fixture.recorder.release()
        runCurrent()
        val callsAfterRelease: Int = fixture.engine.calls.size
        val peaksAfterRelease: Int = fixture.engine.peakCalls

        val seen: MutableList<RecorderState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.recorder.state.collect { seen += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.recorder.level.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.recorder.levelSamples.collect { } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.recorder.elapsed.collect { } }
        fixture.timeSource += 1.hours
        advanceTimeBy(1.hours)
        runCurrent()

        assertEquals(listOf<RecorderState>(RecorderState.Released), seen)
        assertEquals(callsAfterRelease, fixture.engine.calls.size, "no engine call after release: ${fixture.engine.calls}")
        assertEquals(peaksAfterRelease, fixture.engine.peakCalls, "no microphone sampling after release")
        assertEquals(RecorderState.Released, fixture.recorder.state.first())
    }

    @Test
    fun `a call on a released recorder from a collector of the released state is a typed refusal`() =
        runRecorderTest { fixture ->
            val answers: MutableList<RecorderResult<*>> = mutableListOf()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.state.collect { state: RecorderState ->
                    if (state == RecorderState.Released) {
                        answers += fixture.recorder.start()
                        answers += fixture.recorder.prepare()
                        answers += fixture.recorder.stop()
                        answers += fixture.recorder.cancel()
                        fixture.recorder.release()
                    }
                }
            }
            fixture.recording()
            fixture.recorder.release()
            runCurrent()

            assertEquals(4, answers.size)
            answers.forEach { answer ->
                assertTrue(
                    answer is RecorderResult.Failure && answer.error is RecorderError.AlreadyReleased,
                    "answer was $answer",
                )
            }
            // the prepare itself releases the previous engine once, so count since the latest prepare
            val sincePrepare: List<String> = fixture.engine.calls.takeLastWhile { !it.startsWith("prepare(") }
            assertEquals(1, sincePrepare.count { it == "release" }, "released once: ${fixture.engine.calls}")
        }

    // ---- configuration extremes ----

    private fun TestScope.assertRoundTrip(config: AudioRecorderConfig, label: String) {
        runRecorderTest(config) { fixture ->
            val path: String = fixture.recording()
            fixture.timeSource += 5.seconds
            advanceTimeBy(5.seconds)
            runCurrent()
            val stopped: RecorderResult<RecordedFile> = fixture.recorder.stop()
            assertEquals(RecorderResult.Success(RecordedFile(path, 5.seconds)), stopped, label)
        }
    }

    @Test
    fun `infinite intervals and the largest legal encoder numbers still record and stop with the exact duration`() {
        val configs: Map<String, AudioRecorderConfig> = mapOf(
            "infinite duration interval" to AudioRecorderConfig(durationUpdateInterval = Duration.INFINITE),
            "infinite level interval" to AudioRecorderConfig(levelUpdateInterval = Duration.INFINITE),
            "both infinite" to AudioRecorderConfig(
                durationUpdateInterval = Duration.INFINITE,
                levelUpdateInterval = Duration.INFINITE,
            ),
            "int max encoder numbers" to AudioRecorderConfig(
                sampleRate = Int.MAX_VALUE,
                channelCount = Int.MAX_VALUE,
                bitRate = Int.MAX_VALUE,
            ),
            "free space minimum at the long maximum" to AudioRecorderConfig(minimumFreeSpaceBytes = Long.MAX_VALUE),
            "free space minimum of one byte" to AudioRecorderConfig(minimumFreeSpaceBytes = 1L),
            "free space check off" to AudioRecorderConfig(minimumFreeSpaceBytes = 0L),
            "tiny floor" to AudioRecorderConfig(levelFloorDbfs = -Float.MAX_VALUE),
            "floor near zero" to AudioRecorderConfig(levelFloorDbfs = -Float.MIN_VALUE),
        )
        for ((label: String, config: AudioRecorderConfig) in configs) {
            kotlinx.coroutines.test.runTest { assertRoundTrip(config, label) }
        }
    }

    @Test
    fun `a minimum free space at the long maximum is met by exactly that much and refused by one byte less`() {
        runRecorderTest(AudioRecorderConfig(minimumFreeSpaceBytes = Long.MAX_VALUE)) { fixture ->
            fixture.fileSystem.freeSpace = Long.MAX_VALUE - 1
            val refused: RecorderResult<String> = fixture.recorder.prepare()
            val error: RecorderError? = refused.errorOrNull()
            assertTrue(error is RecorderError.InsufficientStorage, "answer was $refused")
            assertEquals(Long.MAX_VALUE, error.requiredBytes)
            assertEquals(Long.MAX_VALUE - 1, error.availableBytes)

            fixture.fileSystem.freeSpace = Long.MAX_VALUE
            val accepted: RecorderResult<String> = fixture.recorder.prepare()
            assertTrue(accepted.isSuccess, "answer was $accepted")
            val limit: Long? = fixture.engine.preparedMaxFileSizes.last()
            assertTrue(limit == null || limit > 0, "the size limit handed to the engine must not overflow: $limit")
        }
    }

    @Test
    fun `an unknown free space passes any minimum and hands the engine no size limit`() {
        runRecorderTest(AudioRecorderConfig(minimumFreeSpaceBytes = Long.MAX_VALUE)) { fixture ->
            fixture.fileSystem.freeSpace = -1L

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertTrue(result.isSuccess, "answer was $result")
            assertNull(fixture.engine.preparedMaxFileSizes.last())
        }
    }

    @Test
    fun `a free space that is below the reserve ends the recording and one at the reserve does not`() {
        // minimum 1 byte: the reserve is capped at the minimum itself (one byte)
        runRecorderTest(AudioRecorderConfig(minimumFreeSpaceBytes = 1L)) { fixture ->
            fixture.fileSystem.freeSpace = 1L
            val path: String = fixture.recording()
            fixture.fileSystem.freeSpace = 1L
            fixture.timeSource += 10.seconds
            advanceTimeBy(10.seconds)
            runCurrent()
            assertTrue(fixture.recorder.state.value is RecorderState.Recording, "free space at the reserve keeps recording")

            fixture.fileSystem.freeSpace = 0L
            fixture.timeSource += 10.seconds
            advanceTimeBy(10.seconds)
            runCurrent()

            val state: RecorderState = fixture.recorder.state.value
            assertTrue(
                state is RecorderState.Interrupted && state.reason == InterruptionReason.StorageLow &&
                    state.recording.path == path,
                "state was $state",
            )
        }
    }

    @Test
    fun `a file system that reports a hostile negative free space is read as unknown`() {
        runRecorderTest { fixture ->
            fixture.fileSystem.freeSpace = Long.MIN_VALUE

            val result: RecorderResult<String> = fixture.recorder.prepare()

            assertTrue(result.isSuccess, "answer was $result")
            assertNotNull(fixture.recorder.state.value as? RecorderState.Ready)
        }
    }
}
