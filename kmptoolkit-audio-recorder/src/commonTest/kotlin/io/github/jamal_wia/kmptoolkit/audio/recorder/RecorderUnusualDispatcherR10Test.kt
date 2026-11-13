package io.github.jamal_wia.kmptoolkit.audio.recorder
// path-hygiene-file: logical-paths the recorder tests run on a fake RecordingFileSystem whose paths are logical '/'-separated names

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext

/**
 * Round 10, hostile consumer: the calls of one recording come from three different dispatchers (a pool thread,
 * an unconfined one and a single-thread view of the pool) instead of the caller's own. The state machine is
 * documented as safe from any thread, so the answers and the engine's call order must be exactly those of a
 * recording driven from one place. The periodic work is switched off (infinite intervals, no free-space
 * watchdog) so no virtual-time ticker spins while the real threads work; virtual time drives the stand-in
 * worker, the real threads only issue the calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderUnusualDispatcherR10Test {

    private val quiet: AudioRecorderConfig = AudioRecorderConfig(
        durationUpdateInterval = Duration.INFINITE,
        levelUpdateInterval = Duration.INFINITE,
        minimumFreeSpaceBytes = 0L,
    )

    @Test
    fun `the calls of one recording from three different dispatchers give the answers of a single caller`() {
        val dispatchers: List<CoroutineDispatcher> = listOf(
            Dispatchers.Default,
            Dispatchers.Unconfined,
            Dispatchers.Default.limitedParallelism(1),
        )
        // the reference: the same sequence from the test's own context
        var reference: List<String> = emptyList()
        runRecorderTest(quiet) { fixture ->
            fixture.recorder.prepare()
            fixture.recorder.start()
            fixture.recorder.pause()
            fixture.recorder.resume()
            fixture.recorder.stop()
            fixture.recorder.pause()
            fixture.recorder.release()
            runCurrent()
            reference = fixture.engine.calls.dropWhile { !it.startsWith("prepare(") }.filterNot { it == "hasPermission" }
        }
        // each operation of the sequence runs on the next dispatcher, over several rotations
        for (rotation: Int in 0 until 3) {
            runRecorderTest(quiet) { fixture ->
                var next: Int = rotation
                suspend fun <T> onNext(block: suspend () -> T): T = withContext(dispatchers[next++ % dispatchers.size]) { block() }

                val path: String = onNext { fixture.recorder.prepare() }.getOrNull() ?: error("prepare failed")
                assertEquals(RecorderResult.Success(Unit), onNext { fixture.recorder.start() }, "rotation $rotation start")
                assertEquals(RecorderResult.Success(Unit), onNext { fixture.recorder.pause() }, "rotation $rotation pause")
                assertEquals(RecorderResult.Success(Unit), onNext { fixture.recorder.resume() }, "rotation $rotation resume")
                val stopped: RecorderResult<RecordedFile> = onNext { fixture.recorder.stop() }
                assertEquals(RecorderResult.Success(RecordedFile(path, Duration.ZERO)), stopped, "rotation $rotation stop")
                // an illegal call from yet another dispatcher is the same typed refusal
                val refused: RecorderResult<Unit> = onNext { fixture.recorder.pause() }
                assertEquals(
                    RecorderResult.Failure(RecorderError.IllegalState(RecorderState.Completed(RecordedFile(path, Duration.ZERO)), RecorderOperation.PAUSE)),
                    refused,
                    "rotation $rotation illegal pause",
                )
                onNext { fixture.recorder.release() }
                runCurrent()

                assertEquals(RecorderState.Released, fixture.recorder.state.value)
                val sincePrepare: List<String> = fixture.engine.calls.dropWhile { !it.startsWith("prepare(") }
                assertEquals(reference, sincePrepare.filterNot { it == "hasPermission" }, "rotation $rotation engine call order")
            }
        }
    }
}
