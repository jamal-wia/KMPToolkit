package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecorderFileSystem whose paths are logical '/'-separated names

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 11, Q2 for the recorder: seeded random sequences of user calls interleaved with system events
 * (audio session interruption, storage low by the watchdog, input silenced and recovered, engine death
 * with and without a platform code, a callback of an older session, a permission that is withdrawn), in
 * virtual time, checked step by step against a small model written from the transition table and the
 * system-event paragraph of `docs/kmptoolkit-audio-recorder/04-api-reference.md` (not from the
 * implementation). Per step: the typed result, the published state, `elapsed` where the docs fix it,
 * the files deleted, "a refused call touches nothing", the engine listener attached exactly while a
 * session is open, and the whole state history only walks documented edges.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderSystemEventStormR11Test {

    private enum class Kind { Idle, Ready, Recording, Paused, Completed, Interrupted, Failed, Released }

    /** The model: only what the documents promise. */
    private class Model {
        var kind: Kind = Kind.Idle
        var path: String? = null

        /** Recorded time while the model says Recording (exact), in ms. */
        var recordedMs: Long = 0L

        /** The duration of a finished file may fall anywhere in `[lo, hi]` where the docs name no exact moment. */
        var lo: Long = 0L
        var hi: Long = 0L
        var reason: InterruptionReason? = null
        var failedWith: RecorderError? = null
        val deleted: MutableList<String> = mutableListOf()
        val allowedNext: Map<Kind, Set<Kind>> = mapOf(
            Kind.Idle to setOf(Kind.Idle, Kind.Released, Kind.Ready, Kind.Failed),
            Kind.Ready to setOf(Kind.Recording, Kind.Idle, Kind.Ready, Kind.Failed, Kind.Released),
            Kind.Recording to setOf(Kind.Paused, Kind.Completed, Kind.Interrupted, Kind.Failed, Kind.Idle, Kind.Released),
            Kind.Paused to setOf(Kind.Recording, Kind.Completed, Kind.Interrupted, Kind.Failed, Kind.Idle, Kind.Released),
            Kind.Completed to setOf(Kind.Ready, Kind.Failed, Kind.Released),
            Kind.Interrupted to setOf(Kind.Ready, Kind.Failed, Kind.Idle, Kind.Released),
            Kind.Failed to setOf(Kind.Ready, Kind.Failed, Kind.Idle, Kind.Released),
            Kind.Released to emptySet(),
        )
    }

    private fun kindOf(state: RecorderState): Kind = when (state) {
        RecorderState.Idle -> Kind.Idle
        RecorderState.Preparing -> error("Preparing is never at rest in a sequential driver")
        is RecorderState.Ready -> Kind.Ready
        is RecorderState.Recording -> Kind.Recording
        is RecorderState.Paused -> Kind.Paused
        is RecorderState.Completed -> Kind.Completed
        is RecorderState.Interrupted -> Kind.Interrupted
        is RecorderState.Failed -> Kind.Failed
        RecorderState.Released -> Kind.Released
    }

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    private val reasons: List<InterruptionReason> = listOf(
        InterruptionReason.AudioSessionInterrupted,
        InterruptionReason.StorageLow,
        InterruptionReason.EngineDied(),
        InterruptionReason.EngineDied(platformCode = -38),
    )

    private fun run(seed: Int, steps: Int) {
        runTest {
            val random = Random(seed)
            var segment = 0
            var performed = 0
            var counter = 0
            while (performed < steps) {
                segment++
                val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
                val recorder: AudioRecorder = fixture.recorder
                val model = Model()
                val history: MutableList<RecorderState> = mutableListOf()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    recorder.state.collect { history += it }
                }
                runCurrent()
                var prepares = 0
                val log: MutableList<String> = mutableListOf()
                fun fail(message: String): Nothing =
                    error("seed=$seed segment=$segment step=$performed: $message\nlog: ${log.takeLast(12)}")

                fun check(after: String) {
                    log += after
                    val actual: RecorderState = recorder.state.value
                    if (kindOf(actual) != model.kind) fail("after $after: state $actual, model ${model.kind}")
                    when (actual) {
                        is RecorderState.Ready -> if (actual.outputPath != model.path) fail("path ${actual.outputPath} vs ${model.path}")
                        is RecorderState.Recording -> if (actual.outputPath != model.path) fail("path ${actual.outputPath} vs ${model.path}")
                        is RecorderState.Paused -> {
                            if (actual.outputPath != model.path) fail("path ${actual.outputPath} vs ${model.path}")
                            if (actual.elapsed.inWholeMilliseconds != model.recordedMs) fail("paused elapsed ${actual.elapsed} vs ${model.recordedMs} ms")
                            if (recorder.elapsed.value.inWholeMilliseconds != model.recordedMs) fail("elapsed flow ${recorder.elapsed.value} while paused, model ${model.recordedMs} ms")
                        }
                        is RecorderState.Completed -> {
                            if (actual.recording.path != model.path) fail("path ${actual.recording.path} vs ${model.path}")
                            if (actual.recording.duration.inWholeMilliseconds != model.recordedMs) fail("duration ${actual.recording.duration} vs ${model.recordedMs} ms")
                            if (recorder.elapsed.value != actual.recording.duration) fail("elapsed flow ${recorder.elapsed.value} after stop")
                        }
                        is RecorderState.Interrupted -> {
                            if (actual.recording.path != model.path) fail("path ${actual.recording.path} vs ${model.path}")
                            if (actual.reason != model.reason) fail("reason ${actual.reason} vs ${model.reason}")
                            val ms: Long = actual.recording.duration.inWholeMilliseconds
                            if (ms < model.lo || ms > model.hi) fail("interrupted duration $ms ms outside [${model.lo}, ${model.hi}]")
                            if (recorder.elapsed.value != actual.recording.duration) fail("elapsed flow ${recorder.elapsed.value} vs the file ${actual.recording.duration}")
                        }
                        is RecorderState.Failed -> {
                            if (actual.error != model.failedWith) fail("error ${actual.error} vs ${model.failedWith}")
                            if (actual.outputPath != null) fail("a failure with a path where the model has none: $actual")
                        }
                        RecorderState.Idle, RecorderState.Released -> Unit
                        RecorderState.Preparing -> fail("Preparing at rest")
                    }
                    if (actual is RecorderState.Recording && recorder.elapsed.value.inWholeMilliseconds > model.recordedMs) {
                        fail("elapsed ${recorder.elapsed.value} runs ahead of the model ${model.recordedMs} ms")
                    }
                    if (actual is RecorderState.Idle && recorder.elapsed.value != Duration.ZERO) fail("elapsed ${recorder.elapsed.value} in Idle")
                    // The engine listener is attached while a session is open. (A re-prepare releases the engine without
                    // removing the old listener and a failed pre-flight leaves it: it is token-fenced, and the events
                    // the model sends through it in every state prove that it changes nothing.)
                    val open: Boolean = model.kind == Kind.Ready || model.kind == Kind.Recording || model.kind == Kind.Paused
                    if (open && fixture.engine.listener == null) fail("no listener attached while ${model.kind}")
                    if (model.kind == Kind.Released && fixture.engine.listener != null) fail("a listener is left after release")
                    if (fixture.fileSystem.deletedPaths != model.deleted) fail("deleted ${fixture.fileSystem.deletedPaths} vs ${model.deleted}")
                }

                /** A refused call: typed, and it touches neither the state, the engine nor a file. */
                fun refused(op: RecorderOperation, result: RecorderResult<*>, before: RecorderState, engineCalls: Int) {
                    val error: RecorderError = result.errorOrNull() ?: fail("$op was accepted in ${model.kind}")
                    if (model.kind == Kind.Released) {
                        assertEquals(RecorderError.AlreadyReleased(op), error, "seed=$seed")
                    } else {
                        if (error != RecorderError.IllegalState(before, op)) fail("$op in ${model.kind} answered $error")
                    }
                    if (recorder.state.value != before) fail("a refused $op moved the state to ${recorder.state.value}")
                    if (fixture.engine.calls.size != engineCalls) fail("a refused $op reached the engine: ${fixture.engine.calls.drop(engineCalls)}")
                }

                fun endSession(kind: Kind, reason: InterruptionReason, lo: Long, hi: Long) {
                    when (model.kind) {
                        Kind.Ready -> {
                            model.deleted += model.path!!
                            model.kind = Kind.Failed
                            model.failedWith = RecorderError.RecordingLost(reason)
                        }
                        else -> {
                            model.kind = kind
                            model.reason = reason
                            model.lo = lo
                            model.hi = hi
                        }
                    }
                }

                val limit: Int = performed + 40 + random.nextInt(60)
                var released = false
                while (performed < steps && performed < limit && !released) {
                    performed++
                    val before: RecorderState = recorder.state.value
                    val calls: Int = fixture.engine.calls.size
                    val pick: Int = random.nextInt(100)
                    when {
                        pick < 14 -> { // prepare
                            val path = "/rec/r11-${counter++}.m4a"
                            val denied: Boolean = random.nextInt(8) == 0
                            fixture.engine.permissionGranted = !denied
                            val result: RecorderResult<String> = runBlockingStep(this) { recorder.prepare(path) }
                            fixture.engine.permissionGranted = true
                            val legal: Boolean = model.kind in setOf(Kind.Idle, Kind.Ready, Kind.Completed, Kind.Interrupted, Kind.Failed)
                            if (!legal) {
                                refused(RecorderOperation.PREPARE, result, before, calls)
                            } else {
                                if (model.kind == Kind.Ready) model.deleted += model.path!!
                                if (denied) {
                                    if (result.errorOrNull() != RecorderError.PermissionDenied) fail("prepare without permission: $result")
                                    model.kind = Kind.Failed
                                    model.failedWith = RecorderError.PermissionDenied
                                    model.path = null
                                } else {
                                    if (result.getOrNull() != path) fail("prepare answered $result")
                                    prepares++
                                    model.kind = Kind.Ready
                                    model.path = path
                                    model.recordedMs = 0L
                                    model.failedWith = null
                                    model.reason = null
                                }
                            }
                            check("prepare${if (denied) "(denied)" else ""}")
                        }
                        pick < 24 -> { // start
                            val result: RecorderResult<Unit> = recorder.start()
                            if (model.kind != Kind.Ready) refused(RecorderOperation.START, result, before, calls)
                            else {
                                if (!result.isSuccess) fail("start from Ready: $result")
                                model.kind = Kind.Recording
                                model.recordedMs = 0L
                            }
                            check("start")
                        }
                        pick < 31 -> { // pause
                            val result: RecorderResult<Unit> = recorder.pause()
                            if (model.kind != Kind.Recording) refused(RecorderOperation.PAUSE, result, before, calls)
                            else {
                                if (!result.isSuccess) fail("pause from Recording: $result")
                                model.kind = Kind.Paused
                            }
                            check("pause")
                        }
                        pick < 38 -> { // resume
                            val result: RecorderResult<Unit> = recorder.resume()
                            if (model.kind != Kind.Paused) refused(RecorderOperation.RESUME, result, before, calls)
                            else {
                                if (!result.isSuccess) fail("resume from Paused: $result")
                                model.kind = Kind.Recording
                            }
                            check("resume")
                        }
                        pick < 46 -> { // stop
                            val result: RecorderResult<RecordedFile> = runBlockingStep(this) { recorder.stop() }
                            when (model.kind) {
                                Kind.Recording, Kind.Paused -> {
                                    val file: RecordedFile = result.getOrNull() ?: fail("stop from ${model.kind}: $result")
                                    if (file.path != model.path || file.duration.inWholeMilliseconds != model.recordedMs) {
                                        fail("stop returned $file, model ${model.path} ${model.recordedMs} ms")
                                    }
                                    model.kind = Kind.Completed
                                }
                                Kind.Interrupted -> {
                                    val file: RecordedFile = result.getOrNull() ?: fail("stop from Interrupted: $result")
                                    val shown: RecorderState.Interrupted = assertIs<RecorderState.Interrupted>(before)
                                    if (file != shown.recording) fail("stop from Interrupted returned $file, not the interrupted file ${shown.recording}")
                                    if (fixture.engine.calls.size != calls) fail("stop from Interrupted reached the engine")
                                }
                                else -> refused(RecorderOperation.STOP, result, before, calls)
                            }
                            check("stop")
                        }
                        pick < 53 -> { // cancel
                            val result: RecorderResult<Unit> = runBlockingStep(this) { recorder.cancel() }
                            when (model.kind) {
                                Kind.Ready, Kind.Recording, Kind.Paused -> {
                                    if (!result.isSuccess) fail("cancel from ${model.kind}: $result")
                                    model.deleted += model.path!!
                                    model.kind = Kind.Idle
                                    model.recordedMs = 0L
                                }
                                Kind.Interrupted -> {
                                    // `cancel` abandons: 03-guide rule 2 says it deletes the partial file; the table row says
                                    // only "yes" (report R11-03: the docs never say the finalized, playable file goes too).
                                    if (!result.isSuccess) fail("cancel from Interrupted: $result")
                                    model.deleted += model.path!!
                                    model.kind = Kind.Idle
                                    model.recordedMs = 0L
                                }
                                else -> refused(RecorderOperation.CANCEL, result, before, calls)
                            }
                            check("cancel")
                        }
                        pick < 66 -> { // an event the engine reports
                            val reason: InterruptionReason = reasons[random.nextInt(reasons.size)]
                            val opens: Boolean = model.kind in setOf(Kind.Ready, Kind.Recording, Kind.Paused)
                            val ms: Long = model.recordedMs
                            fixture.engine.emit(EngineEvent.Interrupted(reason))
                            runCurrent()
                            if (opens) endSession(Kind.Interrupted, reason, ms, ms)
                            check("event($reason)")
                        }
                        pick < 70 -> { // an older session's callback, delivered late
                            val last: Int = fixture.engine.registeredListeners.lastIndex
                            val open: Boolean = model.kind in setOf(Kind.Ready, Kind.Recording, Kind.Paused)
                            val older: Int? = if (open) (if (last > 0) random.nextInt(last) else null) else (if (last >= 0) random.nextInt(last + 1) else null)
                            if (older != null) {
                                fixture.engine.emitToStaleListener(EngineEvent.Interrupted(reasons[random.nextInt(reasons.size)]), older)
                                runCurrent()
                            }
                            check("stale")
                        }
                        pick < 75 -> { // the input is silenced and stays so
                            val silenced: Boolean = model.kind == Kind.Recording
                            val ms: Long = model.recordedMs
                            if (model.kind == Kind.Recording || model.kind == Kind.Paused) {
                                fixture.engine.emit(EngineEvent.InputSilenced(true))
                                val gap: Duration = 600.milliseconds + random.nextInt(900).milliseconds
                                passTime(fixture, gap)
                                if (silenced) {
                                    endSession(Kind.Interrupted, InterruptionReason.MicrophoneSilenced, ms, ms)
                                } else {
                                    fixture.engine.emit(EngineEvent.InputSilenced(false))
                                    runCurrent()
                                }
                            }
                            check("silenced")
                        }
                        pick < 78 -> { // silence that recovers within the debounce
                            if (model.kind == Kind.Recording) {
                                fixture.engine.emit(EngineEvent.InputSilenced(true))
                                passTime(fixture, 100.milliseconds)
                                model.recordedMs += 100
                                fixture.engine.emit(EngineEvent.InputSilenced(false))
                                passTime(fixture, 700.milliseconds)
                                model.recordedMs += 700
                            }
                            check("silence recovered")
                        }
                        pick < 83 -> { // the volume fills up, the watchdog looks
                            val recording: Boolean = model.kind == Kind.Recording
                            val start: Long = model.recordedMs
                            fixture.fileSystem.freeSpace = 1L * 1024 * 1024
                            passTime(fixture, 3.seconds)
                            fixture.fileSystem.freeSpace = Long.MAX_VALUE
                            if (recording) {
                                // Observed somewhere inside the three seconds (rate limited): the docs fix no exact moment.
                                endSession(Kind.Interrupted, InterruptionReason.StorageLow, start, start + 3_000)
                                // The elapsed bookkeeping of the model is frozen from here on.
                                if (model.kind == Kind.Interrupted) model.recordedMs = start
                            } else if (model.kind == Kind.Paused) {
                                // paused: no time recorded; the watchdog's reach in Paused is not documented, see report.
                            }
                            check("storage low")
                        }
                        pick < 98 -> { // time passes
                            val d: Duration = random.nextInt(0, 2500).milliseconds
                            passTime(fixture, d)
                            if (model.kind == Kind.Recording) model.recordedMs += d.inWholeMilliseconds
                            check("time ${d.inWholeMilliseconds}")
                        }
                        else -> { // release
                            val engineCallsBefore: Int = fixture.engine.calls.size
                            recorder.release()
                            if (model.kind == Kind.Ready) model.deleted += model.path!!
                            model.kind = Kind.Released
                            check("release")
                            released = true
                            if (fixture.engine.calls.size < engineCallsBefore) fail("calls went away")
                            // The terminal state: every call is refused as AlreadyReleased and nothing moves.
                            val n: Int = fixture.engine.calls.size
                            val rel: RecorderState = recorder.state.value
                            assertEquals(RecorderState.Released, rel)
                            refused(RecorderOperation.START, recorder.start(), rel, n)
                            refused(RecorderOperation.PAUSE, recorder.pause(), rel, n)
                            refused(RecorderOperation.RESUME, recorder.resume(), rel, n)
                            refused(RecorderOperation.STOP, runBlockingStep(this) { recorder.stop() }, rel, n)
                            refused(RecorderOperation.CANCEL, runBlockingStep(this) { recorder.cancel() }, rel, n)
                            refused(RecorderOperation.PREPARE, runBlockingStep(this) { recorder.prepare("/rec/after.m4a") }, rel, n)
                            recorder.release()
                            fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied()))
                            runCurrent()
                            check("after release")
                        }
                    }
                    // The model's Interrupted duration is frozen: it must not keep growing.
                    if (model.kind == Kind.Interrupted) model.recordedMs = model.recordedMs
                }
                if (!released) {
                    recorder.release()
                    if (model.kind == Kind.Ready) model.deleted += model.path!!
                    model.kind = Kind.Released
                    runCurrent()
                    check("final release")
                }

                // Resources and history of the segment.
                assertNull(fixture.engine.listener, "seed=$seed segment=$segment: a listener is left")
                assertTrue(
                    fixture.engine.releaseCount >= prepares,
                    "seed=$seed segment=$segment: $prepares sessions prepared but the engine was released ${fixture.engine.releaseCount} times",
                )
                var previous: Kind = Kind.Idle
                for (state in history.filter { it != RecorderState.Preparing }) {
                    val next: Kind = kindOf(state)
                    if (next != previous || state is RecorderState.Ready) {
                        assertTrue(next in model.allowedNext.getValue(previous), "seed=$seed segment=$segment: the state walked $previous -> $next ($state)")
                    }
                    previous = next
                }
                assertEquals(history.last(), RecorderState.Released, "seed=$seed segment=$segment: history does not end in Released")
                assertEquals(0, history.zipWithNext().count { (a, b) -> a == b }, "seed=$seed: a state was published twice in a row")
            }
        }
    }

    /** Runs a suspend call of the recorder to completion on the test scheduler. */
    private fun <T> runBlockingStep(scope: TestScope, block: suspend () -> T): T {
        var result: Result<T>? = null
        val job = scope.launch { result = runCatching { block() } }
        scope.runCurrent()
        scope.advanceTimeBy(1.milliseconds)
        scope.runCurrent()
        check(job.isCompleted) { "a recorder call did not finish on the test scheduler" }
        return result!!.getOrThrow()
    }

    @Test
    fun `seed 11 survives 300 steps of calls and system events and matches the model`() = run(11, 300)

    @Test
    fun `seed 29 survives 300 steps of calls and system events and matches the model`() = run(29, 300)

    @Test
    fun `seed 303 survives 300 steps of calls and system events and matches the model`() = run(303, 300)

    @Test
    fun `seed 4711 survives 300 steps of calls and system events and matches the model`() = run(4711, 300)

    @Test
    fun `seed 90210 survives 300 steps of calls and system events and matches the model`() = run(90210, 300)

    @Test
    fun `seed 777 survives 300 steps of calls and system events and matches the model`() = run(777, 300)
}
