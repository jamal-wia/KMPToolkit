package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the differential runs on a fake RecordingFileSystem whose paths are logical '/'-separated names

import io.github.jamal_wia.kmptoolkit.audio.recorder.testing.FakeAudioRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Round 10 differential: one hand-written scenario table (every public call in every state, scripted engine
 * failures, system events) is run through the real [DefaultAudioRecorder] over its stand-ins and through
 * [FakeAudioRecorder]. The docs of the testing module promise that the fake "enforces the same transition
 * table as the real recorder", so every step must give the same typed result, the same state afterwards, the
 * same state history and the same deleted files. The documented differences are filtered, nothing else: the
 * fake never sits in `Preparing` and checks no file system.
 *
 * The table is deterministic: virtual time only, no random choice, no real file or microphone. The same class
 * runs on the Android host and on the iOS simulator, where it is the platform parity check of the recorder
 * core (the platform file systems have their own contract tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderFakeVersusRealR10Test {

    private val explicitPath: String = "/rec/explicit.m4a"

    /** Scenario table: each row is a list of steps, see [play]. */
    private val table: List<List<String>> = listOf(
        // every call from Idle
        listOf("start"),
        listOf("pause"),
        listOf("resume"),
        listOf("stop"),
        listOf("cancel"),
        listOf("release", "release", "prepare", "start", "pause", "resume", "stop", "cancel"),
        // the happy paths
        listOf("prepare", "start", "pause", "resume", "stop"),
        listOf("prepare", "start", "adv:3", "stop", "cancel"),
        listOf("prepare", "start", "adv:2", "pause", "adv:9", "resume", "adv:4", "stop"),
        listOf("prepare:explicit", "start", "stop", "prepare:explicit", "start", "stop"),
        // illegal calls in Ready, Recording, Paused, Completed
        listOf("prepare", "pause"),
        listOf("prepare", "resume"),
        listOf("prepare", "stop"),
        listOf("prepare", "start", "start"),
        listOf("prepare", "start", "resume"),
        listOf("prepare", "start", "prepare"),
        listOf("prepare", "start", "pause", "pause"),
        listOf("prepare", "start", "pause", "start"),
        listOf("prepare", "start", "pause", "prepare"),
        listOf("prepare", "start", "stop", "start", "pause", "resume", "stop", "cancel", "stop"),
        // re-prepare and cancel
        listOf("prepare", "prepare"),
        listOf("prepare", "cancel", "prepare", "start", "cancel"),
        listOf("prepare", "start", "adv:1", "cancel", "start"),
        // argument refusal and permission
        listOf("prepare:relative", "prepare:blank", "prepare"),
        listOf("perm:deny", "prepare", "start", "perm:grant", "prepare", "start", "stop"),
        listOf("prepare", "perm:deny", "start", "stop"),
        // scripted engine failures, one per operation
        listOf("fail:prepare", "prepare", "prepare"),
        listOf("prepare", "fail:start", "start", "cancel", "prepare", "start"),
        listOf("prepare", "start", "fail:pause", "pause", "stop"),
        listOf("prepare", "start", "pause", "fail:resume", "resume", "stop"),
        listOf("prepare", "start", "adv:2", "fail:stop", "stop", "cancel"),
        listOf("prepare", "start", "fail:stop", "stop", "prepare", "start", "stop"),
        // release in every state
        listOf("prepare", "release", "prepare", "stop"),
        listOf("prepare", "start", "adv:2", "release", "stop", "cancel", "pause"),
        listOf("prepare", "start", "pause", "release", "resume"),
        listOf("prepare", "start", "stop", "release", "prepare", "cancel"),
        // system events
        listOf("sys:session"),
        listOf("prepare", "sys:session", "cancel", "prepare", "start"),
        listOf("prepare", "start", "adv:3", "sys:session", "stop", "cancel"),
        listOf("prepare", "start", "adv:2", "sys:storage", "adv:5", "prepare", "start", "stop"),
        listOf("prepare", "start", "pause", "sys:died", "stop", "start", "cancel"),
        listOf("prepare", "start", "sys:session", "sys:storage", "stop", "release"),
        listOf("prepare", "start", "sys:session", "start", "pause", "resume"),
        listOf("prepare", "start", "adv:1", "lost:storage", "cancel", "prepare"),
        listOf("prepare", "start", "pause", "lost:session", "stop", "prepare"),
        listOf("prepare", "sys:storage", "prepare", "start", "stop"),
        listOf("prepare", "start", "stop", "sys:session", "cancel"),
    )

    @Test
    fun `the table has at least thirty rows over every public call`() {
        assertTrue(table.size >= 30, "rows: ${table.size}")
        val used: Set<String> = table.flatten().map { it.substringBefore(':') }.toSet()
        listOf("prepare", "start", "pause", "resume", "stop", "cancel", "release", "sys", "lost", "fail", "perm")
            .forEach { assertTrue(it in used, "no row uses $it") }
    }

    @Test
    fun `the fake and the real recorder answer every scenario row alike`() {
        val mismatches: MutableList<String> = mutableListOf()
        table.forEachIndexed { index: Int, row: List<String> ->
            runRecorderTest { fixture ->
                val real: List<String> = playReal(fixture, row)
                val fake: List<String> = playFake(row)
                if (real != fake) {
                    mismatches += "row $index $row\n" + diff(real, fake)
                }
            }
        }
        assertEquals(emptyList(), mismatches, "fake and real recorder differ:\n" + mismatches.joinToString("\n\n"))
    }

    private fun diff(real: List<String>, fake: List<String>): String = buildString {
        val size: Int = maxOf(real.size, fake.size)
        for (i: Int in 0 until size) {
            val r: String = real.getOrElse(i) { "<none>" }
            val f: String = fake.getOrElse(i) { "<none>" }
            if (r != f) append("  step $i\n    real: $r\n    fake: $f\n")
        }
    }

    // ---- real recorder ----

    private suspend fun TestScope.playReal(fixture: RecorderFixture, row: List<String>): List<String> {
        val history: MutableList<RecorderState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.recorder.state.collect { history += it }
        }
        val log: MutableList<String> = mutableListOf()
        for (step: String in row) {
            val outcome: String = when {
                step == "prepare" -> describe(fixture.recorder.prepare())
                step == "prepare:explicit" -> describe(fixture.recorder.prepare(explicitPath))
                step == "prepare:relative" -> describe(fixture.recorder.prepare("relative/x.m4a"))
                step == "prepare:blank" -> describe(fixture.recorder.prepare("  "))
                step == "start" -> describe(fixture.recorder.start())
                step == "pause" -> describe(fixture.recorder.pause())
                step == "resume" -> describe(fixture.recorder.resume())
                step == "stop" -> describe(fixture.recorder.stop())
                step == "cancel" -> describe(fixture.recorder.cancel())
                step == "release" -> { fixture.recorder.release(); "released" }
                step == "perm:deny" -> { fixture.engine.permissionGranted = false; "-" }
                step == "perm:grant" -> { fixture.engine.permissionGranted = true; "-" }
                step.startsWith("adv:") -> {
                    val by: Duration = step.removePrefix("adv:").toInt().seconds
                    fixture.timeSource += by
                    advanceTimeBy(by)
                    runCurrent()
                    "-"
                }
                step.startsWith("fail:") -> {
                    fixture.engine.failures[operationOf(step)] = IllegalStateException("scripted ${step}")
                    "-"
                }
                step.startsWith("sys:") -> {
                    fixture.engine.emit(EngineEvent.Interrupted(reasonOf(step)))
                    runCurrent()
                    "-"
                }
                step.startsWith("lost:") -> {
                    fixture.engine.failures[RecorderOperation.STOP] = IllegalStateException("scripted loss")
                    fixture.engine.emit(EngineEvent.Interrupted(reasonOf(step)))
                    runCurrent()
                    "-"
                }
                else -> error("unknown step $step")
            }
            fixture.engine.failures.clear()
            runCurrent()
            log += "$step => $outcome | ${describe(fixture.recorder.state.value)} | elapsed=${fixture.recorder.elapsed.value} | deleted=${fixture.fileSystem.deletedPaths.map(::norm)}"
        }
        log += "history=" + history.filterNot { it == RecorderState.Preparing }.map(::describe)
        return log
    }

    // ---- fake ----

    private suspend fun TestScope.playFake(row: List<String>): List<String> {
        val fake = FakeAudioRecorder()
        val history: MutableList<RecorderState> = mutableListOf()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.state.collect { history += it }
        }
        val log: MutableList<String> = mutableListOf()
        for (step: String in row) {
            val outcome: String = when {
                step == "prepare" -> describe(fake.prepare())
                step == "prepare:explicit" -> describe(fake.prepare(explicitPath))
                step == "prepare:relative" -> describe(fake.prepare("relative/x.m4a"))
                step == "prepare:blank" -> describe(fake.prepare("  "))
                step == "start" -> describe(fake.start())
                step == "pause" -> describe(fake.pause())
                step == "resume" -> describe(fake.resume())
                step == "stop" -> describe(fake.stop())
                step == "cancel" -> describe(fake.cancel())
                step == "release" -> { fake.release(); "released" }
                step == "perm:deny" -> { fake.permissionGranted = false; "-" }
                step == "perm:grant" -> { fake.permissionGranted = true; "-" }
                step.startsWith("adv:") -> { fake.advanceElapsed(step.removePrefix("adv:").toInt().seconds); "-" }
                step.startsWith("fail:") -> {
                    fake.failNextOperationWith = RecorderError.EngineFailure(operationOf(step))
                    "-"
                }
                step.startsWith("sys:") -> { fake.simulateInterruption(reasonOf(step)); "-" }
                step.startsWith("lost:") -> {
                    fake.simulateRecordingLost(reasonOf(step), IllegalStateException("scripted loss"))
                    "-"
                }
                else -> error("unknown step $step")
            }
            fake.failNextOperationWith = null
            log += "$step => $outcome | ${describe(fake.state.value)} | elapsed=${fake.elapsed.value} | deleted=${fake.deletedPaths.map(::norm)}"
        }
        log += "history=" + history.map(::describe)
        return log
    }

    // ---- shared vocabulary ----

    private fun operationOf(step: String): RecorderOperation = when (step.substringAfter(':')) {
        "prepare" -> RecorderOperation.PREPARE
        "start" -> RecorderOperation.START
        "pause" -> RecorderOperation.PAUSE
        "resume" -> RecorderOperation.RESUME
        "stop" -> RecorderOperation.STOP
        else -> error("unknown operation in $step")
    }

    private fun reasonOf(step: String): InterruptionReason = when (step.substringAfter(':')) {
        "session" -> InterruptionReason.AudioSessionInterrupted
        "storage" -> InterruptionReason.StorageLow
        "died" -> InterruptionReason.EngineDied()
        else -> error("unknown reason in $step")
    }

    /** The generated names differ between the two (the fake counts, the real one is clock and token based). */
    private fun norm(path: String?): String = when (path) {
        null -> "null"
        explicitPath -> "<explicit>"
        else -> "<generated>"
    }

    private fun describe(result: RecorderResult<*>): String = when (result) {
        is RecorderResult.Success -> when (val value: Any? = result.value) {
            is RecordedFile -> "ok(${norm(value.path)},${value.duration})"
            is String -> "ok(${norm(value)})"
            else -> "ok"
        }
        is RecorderResult.Failure -> "failure(${describe(result.error)})"
    }

    private fun describe(error: RecorderError): String = when (error) {
        is RecorderError.IllegalState -> "IllegalState(${describe(error.state)},${error.operation})"
        is RecorderError.AlreadyReleased -> "AlreadyReleased(${error.operation})"
        is RecorderError.EngineFailure -> "EngineFailure(${error.operation})"
        is RecorderError.RecordingLost -> "RecordingLost(${error.reason},cause=${error.cause != null})"
        else -> error::class.simpleName.toString()
    }

    private fun describe(state: RecorderState): String = when (state) {
        RecorderState.Idle -> "Idle"
        RecorderState.Preparing -> "Preparing"
        RecorderState.Released -> "Released"
        is RecorderState.Ready -> "Ready(${norm(state.outputPath)})"
        is RecorderState.Recording -> "Recording(${norm(state.outputPath)})"
        is RecorderState.Paused -> "Paused(${norm(state.outputPath)},${state.elapsed})"
        is RecorderState.Completed -> "Completed(${norm(state.recording.path)},${state.recording.duration})"
        is RecorderState.Interrupted ->
            "Interrupted(${norm(state.recording.path)},${state.recording.duration},${state.reason})"
        is RecorderState.Failed -> "Failed(${describe(state.error)},${norm(state.outputPath)})"
    }
}
