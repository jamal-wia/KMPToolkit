package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

private const val MIB: Long = 1024L * 1024L

/**
 * Hazards a recorder meets in a real app: a `release()` that races a `prepare()` on another
 * thread, a `stop()` caller that is cancelled while the container is being finalized, a recorder
 * abandoned mid-recording (the process died) whose explicit output file the next one is handed,
 * a level meter watched by a crowd of subscribers that come and go, and the same recorder shape
 * rebuilt over and over after failures.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RecorderAdversarialTest {

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun CoroutineScope.childCount(): Int = coroutineContext[Job]!!.children.count()

    // --- Release racing prepare, on real threads ---

    /** A thread-safe engine that notices a microphone left open and calls that overlapped. */
    private class GuardedEngine : RecorderEngine {
        val open: AtomicBoolean = AtomicBoolean(false)
        val releases: AtomicInt = AtomicInt(0)
        val starts: AtomicInt = AtomicInt(0)
        val overlaps: AtomicInt = AtomicInt(0)
        val releaseReturned: AtomicBoolean = AtomicBoolean(false)
        val startsAfterRelease: AtomicInt = AtomicInt(0)
        private val driving: AtomicInt = AtomicInt(0)
        private val listener: AtomicReference<((EngineEvent) -> Unit)?> = AtomicReference(null)

        private inline fun <T> driven(block: () -> T): T {
            if (driving.incrementAndFetch() > 1) overlaps.incrementAndFetch()
            try {
                return block()
            } finally {
                driving.decrementAndFetch()
            }
        }

        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true

        override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
            driven {
                val deadline: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 100.microseconds
                while (!deadline.hasPassedNow()) Unit
                open.store(true)
            }
        }

        override fun setEventListener(listener: ((EngineEvent) -> Unit)?): Unit = driven { this.listener.store(listener) }

        override fun start(): Unit = driven {
            if (releaseReturned.load()) startsAfterRelease.incrementAndFetch()
            starts.incrementAndFetch()
        }

        override fun pause(): Unit = driven { }
        override fun resume(): Unit = driven { }
        override fun peakDbfs(): Float? = null
        override fun stop(): Unit = driven { }

        override fun release(): Unit = driven {
            open.store(false)
            releases.incrementAndFetch()
        }
    }

    private class CountingFileSystem : RecordingFileSystem {
        override fun appPrivateDirectory(): String = "/data"
        override fun applicationIdentifier(): String = "app"
        override fun resolve(directory: String, name: String): String = "$directory/$name"
        override fun parentOf(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        override fun ensureWritableDirectory(path: String): Boolean = true
        override fun freeSpaceBytes(path: String): Long = Long.MAX_VALUE
        override fun exists(path: String): Boolean = false
        override fun delete(path: String) = Unit
    }

    @Test
    fun `release racing prepare on another thread never leaves the microphone open`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                repeat(RACE_ROUNDS) { round: Int ->
                    val engine = GuardedEngine()
                    val recorder = DefaultAudioRecorder(
                        engine = engine,
                        fileSystem = CountingFileSystem(),
                        config = AudioRecorderConfig(minimumFreeSpaceBytes = 0L),
                        workerContext = Dispatchers.Default,
                        epochClock = EpochClock { round.toLong() },
                    )
                    val ready = AtomicInt(0)
                    val go = AtomicBoolean(false)
                    var result: RecorderResult<String>? = null
                    val preparing: Job = launch(Dispatchers.Default) {
                        ready.incrementAndFetch()
                        while (!go.load()) yield()
                        result = recorder.prepare("/data/race.m4a")
                        // What a caller does with a successful prepare: start it, if it can.
                        if (result?.isSuccess == true) recorder.start()
                    }
                    val releasing: Job = launch(Dispatchers.Default) {
                        ready.incrementAndFetch()
                        while (!go.load()) yield()
                        repeat(round % 5) { yield() }
                        recorder.release()
                        engine.releaseReturned.store(true)
                    }
                    while (ready.load() < 2) yield()
                    go.store(true)
                    try {
                        withTimeout(10.seconds) { listOf(preparing, releasing).joinAll() }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        fail("round $round: hung, state ${recorder.state.value}")
                    }
                    withTimeout(10.seconds) { recorder.scope.childJobs().joinAll() }

                    assertEquals(RecorderState.Released, recorder.state.value, "round $round")
                    assertFalse(engine.open.load(), "round $round: the microphone is still open after release")
                    assertEquals(0, engine.startsAfterRelease.load(), "round $round: start after release")
                    assertEquals(0, engine.overlaps.load(), "round $round: engine driven from two threads")
                    assertTrue(engine.releases.load() >= 1, "round $round: the engine was never released")
                    assertTrue(engine.releases.load() <= 3, "round $round: released ${engine.releases.load()} times")
                    val outcome: RecorderResult<String>? = result
                    assertTrue(
                        outcome == null || outcome.isSuccess ||
                            outcome.errorOrNull() == RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
                        "round $round: prepare ended with ${outcome?.errorOrNull()}",
                    )
                }
            }
        }

    private fun CoroutineScope.childJobs(): List<Job> = coroutineContext[Job]!!.children.toList()

    // --- A stop() caller cancelled while the container is finalized ---

    @Test
    fun `a stop caller cancelled mid-finalization still ends in Completed and releases the engine`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 1.seconds)
            val releasesBefore: Int = fixture.engine.releaseCount
            lateinit var stopping: Job
            fixture.engine.onStop = { stopping.cancel() }
            stopping = launch { fixture.recorder.stop() }
            runCurrent()
            stopping.join()
            runCurrent()

            assertEquals(
                RecorderState.Completed(RecordedFile(path, 1.seconds)),
                fixture.recorder.state.value,
                "a finished stop must publish its outcome even when its caller is gone",
            )
            assertFalse(fixture.recorder.isFinalizingForTest(), "the finalization marker must be cleared")
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "engine released by the finalizer once")
            assertEquals(1, fixture.engine.calls.count { it == "stop" })

            // A second stop must answer, not hang, and a later release must still be allowed.
            val second: RecorderResult<RecordedFile> = withTimeout(5.seconds) { fixture.recorder.stop() }
            assertIs<RecorderError.IllegalState>(second.errorOrNull())
            fixture.recorder.release()
            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(0, fixture.scope.childCount())
        }

    @Test
    fun `a stop caller cancelled while paused and a second stop waiting both settle`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            passTime(fixture, 2.seconds)
            assertTrue(fixture.recorder.pause().isSuccess)
            lateinit var first: Job
            var secondResult: RecorderResult<RecordedFile>? = null
            fixture.engine.onStop = {
                // Lands while the container is being finalized, with the lock free.
                launch { secondResult = fixture.recorder.stop() }
                first.cancel()
            }
            first = launch { fixture.recorder.stop() }
            runCurrent()
            first.join()
            runCurrent()

            assertEquals(
                RecorderState.Completed(RecordedFile(path, 2.seconds)),
                fixture.recorder.state.value,
            )
            val waiter: RecorderResult<RecordedFile> = secondResult ?: fail("the second stop() never returned")
            assertTrue(
                waiter.isSuccess || waiter.errorOrNull() is RecorderError.IllegalState,
                "second stop ended with ${waiter.errorOrNull()}",
            )
            assertEquals(1, fixture.engine.calls.count { it == "stop" }, "the container was finalized twice")
            assertFalse(fixture.recorder.isFinalizingForTest())
        }

    private fun AudioRecorder.isFinalizingForTest(): Boolean = (this as DefaultAudioRecorder).isFinalizing

    // --- Process death: an abandoned recorder and a second one on the same explicit path ---

    private fun TestScope.newRecorder(
        fileSystem: FakeRecordingFileSystem,
        engine: FakeRecorderEngine,
    ): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = AudioRecorderConfig(),
        workerContext = StandardTestDispatcher(testScheduler),
        epochClock = EpochClock { FIXED_EPOCH_MILLIS },
        fileNameToken = FileNameToken { FIXED_FILE_NAME_TOKEN },
    )

    @Test
    fun `a recorder started over the file an abandoned recorder left keeps it on failure and cancellation`() =
        runTest {
            val disk = FakeRecordingFileSystem()
            val path = "/data/app/voice.m4a"
            val abandoned: DefaultAudioRecorder = newRecorder(disk, FakeRecorderEngine())
            val next: MutableList<DefaultAudioRecorder> = mutableListOf()
            try {
                // Recorder A is recording into the file and is never released: the process died.
                assertTrue(abandoned.prepare(path).isSuccess)
                disk.existingPaths += path
                assertTrue(abandoned.start().isSuccess)
                runCurrent()

                // B finds the file, and its preparation fails.
                val failingEngine = FakeRecorderEngine().apply {
                    failures[RecorderOperation.PREPARE] = IllegalStateException("device busy")
                }
                val failing: DefaultAudioRecorder = newRecorder(disk, failingEngine).also { next += it }
                assertIs<RecorderError.EngineFailure>(failing.prepare(path).errorOrNull())
                assertFalse(path in disk.deletedPaths, "a failed attempt deleted a file it did not create")

                // B is cancelled while its preparation is pending.
                val gatedEngine = FakeRecorderEngine().apply { prepareGate = CompletableDeferred() }
                val cancelled: DefaultAudioRecorder = newRecorder(disk, gatedEngine).also { next += it }
                val preparing: Job = launch { cancelled.prepare(path) }
                runCurrent()
                preparing.cancel()
                preparing.join()
                runCurrent()
                assertEquals(RecorderState.Idle, cancelled.state.value)
                assertFalse(path in disk.deletedPaths, "a cancelled attempt deleted a file it did not create")

                // B, finally, succeeds over the same path and records.
                val good: DefaultAudioRecorder = newRecorder(disk, FakeRecorderEngine()).also { next += it }
                assertEquals(path, good.prepare(path).getOrNull())
                assertTrue(good.start().isSuccess)
                advanceTimeBy(1.seconds)
                runCurrent()
                assertIs<RecorderState.Completed>(good.stop().let { good.state.value })
                assertFalse(path in disk.deletedPaths, "the new recording's file was deleted")
                assertIs<RecorderState.Recording>(abandoned.state.value, "the abandoned recorder is independent")
            } finally {
                next.forEach { it.release() }
                abandoned.release()
                runCurrent()
            }
        }

    // --- Level subscribers coming and going ---

    @Test
    fun `five hundred level subscribers do not change how often the engine is metered and none outlive release`() =
        runRecorderTest { fixture ->
            fixture.engine.defaultPeak = -20f
            fixture.recording()
            val base: Job = backgroundScope.launch { fixture.recorder.level.collect { } }
            runCurrent()

            fun peakCallsPerSecond(): Int {
                val before: Int = fixture.engine.peakCalls
                advanceTimeBy(1.seconds)
                runCurrent()
                return fixture.engine.peakCalls - before
            }

            val baseline: Int = peakCallsPerSecond()
            assertTrue(baseline > 0, "metering must be running with a subscriber")

            // A crowd of both kinds of subscriber arrives at once.
            val crowd: List<Job> = List(SUBSCRIBERS) { index: Int ->
                backgroundScope.launch {
                    if (index % 2 == 0) fixture.recorder.level.collect { } else fixture.recorder.levelSamples.collect { }
                }
            }
            runCurrent()
            val during: Int = peakCallsPerSecond()
            assertTrue(
                during in (baseline - 1)..(baseline + 1),
                "the meter rate scaled with subscribers: $baseline per second alone, $during with $SUBSCRIBERS more",
            )

            crowd.forEach { it.cancel() }
            runCurrent()
            val after: Int = peakCallsPerSecond()
            assertTrue(after in (baseline - 1)..(baseline + 1), "rate after the crowd left: $after vs $baseline")

            // Churn: each subscriber arrives and leaves before the next one.
            repeat(SUBSCRIBERS) { index: Int ->
                val job: Job = backgroundScope.launch { fixture.recorder.levelSamples.collect { } }
                runCurrent()
                job.cancel()
                runCurrent()
                if (index % 100 == 0) assertTrue(fixture.scope.childCount() <= 2, "children grew at $index")
            }
            val churned: Int = peakCallsPerSecond()
            assertTrue(churned in (baseline - 1)..(baseline + 1), "rate after the churn: $churned vs $baseline")

            fixture.recorder.release()
            runCurrent()
            val callsAtRelease: Int = fixture.engine.peakCalls
            val rateAfterRelease: Int = peakCallsPerSecond()
            assertEquals(0, rateAfterRelease, "the meter kept sampling after release")
            assertEquals(callsAtRelease, fixture.engine.peakCalls)
            assertEquals(0f, fixture.recorder.level.value)
            assertEquals(0, fixture.scope.childCount(), "jobs left in the recorder's scope")
            base.cancel()
        }

    // --- Rebuilding after failures ---

    @Test
    fun `fifty failing prepares and releases leave every recorder idle or released with no jobs`() =
        runTest(timeout = 60.seconds) {
            repeat(CYCLES) { cycle: Int ->
                val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
                when (cycle % 4) {
                    0 -> fixture.engine.permissionGranted = false
                    1 -> fixture.engine.unsupportedFormats = setOf(AudioFormat.M4A)
                    2 -> fixture.fileSystem.ensureWritableDirectoryFailure = IllegalStateException("no directory $cycle")
                    else -> fixture.engine.failures[RecorderOperation.PREPARE] = IllegalStateException("busy $cycle")
                }
                val failed: RecorderResult<String> = fixture.recorder.prepare()
                assertTrue(failed.errorOrNull() != null, "cycle $cycle: the injected failure was not reported")
                assertIs<RecorderState.Failed>(fixture.recorder.state.value, "cycle $cycle: stuck after failure")

                // The failure is gone: the same recorder prepares and records again.
                fixture.engine.permissionGranted = true
                fixture.engine.unsupportedFormats = emptySet()
                fixture.fileSystem.ensureWritableDirectoryFailure = null
                fixture.engine.failures.clear()
                assertTrue(fixture.recorder.prepare().isSuccess, "cycle $cycle: no recovery after failure")
                assertTrue(fixture.recorder.start().isSuccess, "cycle $cycle")
                runCurrent()
                assertTrue(fixture.scope.childCount() > 0, "cycle $cycle: nothing runs while recording")

                fixture.recorder.release()
                runCurrent()
                assertEquals(RecorderState.Released, fixture.recorder.state.value, "cycle $cycle")
                assertEquals(0, fixture.scope.childCount(), "cycle $cycle: jobs left after release")
                assertFalse(fixture.scope.coroutineContext[Job]!!.isActive, "cycle $cycle: scope still active")
                assertTrue(fixture.engine.releaseCount >= 1, "cycle $cycle: engine never released")
            }
        }

    @Test
    fun `a cancelled prepare leaves the recorder idle and able to prepare again fifty times`() =
        runTest(timeout = 60.seconds) {
            val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
            try {
                repeat(CYCLES) { cycle: Int ->
                    fixture.engine.prepareGate = CompletableDeferred()
                    val preparing: Job = launch { fixture.recorder.prepare() }
                    runCurrent()
                    assertIs<RecorderState.Preparing>(fixture.recorder.state.value, "cycle $cycle")
                    preparing.cancel()
                    preparing.join()
                    runCurrent()
                    assertEquals(RecorderState.Idle, fixture.recorder.state.value, "cycle $cycle: stuck after cancel")
                }
                fixture.engine.prepareGate = null
                assertTrue(fixture.recorder.prepare().isSuccess)
                assertEquals(0, fixture.scope.childCount())
            } finally {
                fixture.recorder.release()
            }
        }

    // --- Storage-low poll against pause/resume churn ---

    @Test
    fun `a low volume seen only while paused never ends the recording across a hundred pause cycles`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            repeat(CYCLES * 2) { cycle: Int ->
                fixture.fileSystem.freeSpace = 1 * MIB
                assertTrue(fixture.recorder.pause().isSuccess, "cycle $cycle")
                passTime(fixture, 5.seconds)
                fixture.fileSystem.freeSpace = 100 * MIB
                assertTrue(fixture.recorder.resume().isSuccess, "cycle $cycle")
                passTime(fixture, 3.seconds)
                assertEquals(
                    RecorderState.Recording(path),
                    fixture.recorder.state.value,
                    "cycle $cycle: a paused recording was ended by a low reading",
                )
            }
        }

    private companion object {
        const val RACE_ROUNDS: Int = 200
        const val SUBSCRIBERS: Int = 500
        const val CYCLES: Int = 50
    }
}
