package io.github.jamal_wia.kmptoolkit.audio.recorder

import io.github.jamal_wia.kmptoolkit.core.ReentrantLockHandle
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.core.newReentrantLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Concurrency storm for the recorder: callers, engine callbacks, level collectors and slow
 * finalizations on real threads, plus the path-ownership fence meeting late prepare tails.
 *
 * Every wait is bounded in real time (engine gates give up after ten seconds, every test body runs
 * under `withTimeout`), the jitter is seeded and the seed is in each failure message.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)
class RecorderStormTest {

    /** Thread-safe engine with gates for `prepare`, `stop` and `release`, and a model of "the microphone is open". */
    private class StormEngine(
        private val onPrepared: (String) -> Unit = {},
        private val busyMicros: Long = 0L,
    ) : RecorderEngine {
        val prepareOpen: AtomicBoolean = AtomicBoolean(true)
        val stopOpen: AtomicBoolean = AtomicBoolean(true)
        val releaseOpen: AtomicBoolean = AtomicBoolean(true)
        val failPrepare: AtomicBoolean = AtomicBoolean(false)
        val inPrepare: AtomicBoolean = AtomicBoolean(false)
        val inStop: AtomicBoolean = AtomicBoolean(false)
        val inRelease: AtomicBoolean = AtomicBoolean(false)

        /** Whether a native handle is currently held. */
        val open: AtomicBoolean = AtomicBoolean(false)
        val overlaps: AtomicInt = AtomicInt(0)
        val callsWithoutHandle: AtomicInt = AtomicInt(0)
        val releases: AtomicInt = AtomicInt(0)
        val stops: AtomicInt = AtomicInt(0)
        val totalCalls: AtomicInt = AtomicInt(0)
        private val driving: AtomicInt = AtomicInt(0)
        private val listener: AtomicReference<((EngineEvent) -> Unit)?> = AtomicReference(null)
        private val seen: AtomicReference<List<(EngineEvent) -> Unit>> = AtomicReference(emptyList())

        fun currentListener(): ((EngineEvent) -> Unit)? = listener.load()
        fun anyListener(random: Random): ((EngineEvent) -> Unit)? {
            val all: List<(EngineEvent) -> Unit> = seen.load()
            return if (all.isEmpty()) null else all[random.nextInt(all.size)]
        }

        private inline fun <T> driven(block: () -> T): T {
            totalCalls.incrementAndFetch()
            if (driving.incrementAndFetch() > 1) overlaps.incrementAndFetch()
            try {
                return block()
            } finally {
                driving.decrementAndFetch()
            }
        }

        /** A slow platform call: holds the calling thread for [busyMicros], so an overlap has a window to land in. */
        private fun busy() {
            if (busyMicros == 0L) return
            val end = TimeSource.Monotonic.markNow() + busyMicros.times(1_000L).toDuration(DurationUnit.NANOSECONDS)
            while (!end.hasPassedNow()) Unit
        }

        private fun spinUntil(gate: AtomicBoolean) {
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (!gate.load() && !deadline.hasPassedNow()) Unit
        }

        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true

        override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
            inPrepare.store(true)
            totalCalls.incrementAndFetch()
            if (driving.incrementAndFetch() > 1) overlaps.incrementAndFetch()
            try {
                val deadline = TimeSource.Monotonic.markNow() + 10.seconds
                while (!prepareOpen.load() && !deadline.hasPassedNow()) delay(1L)
                busy()
                onPrepared(outputPath)
                if (failPrepare.load()) throw IllegalStateException("scripted prepare failure")
                open.store(true)
            } finally {
                driving.decrementAndFetch()
                inPrepare.store(false)
            }
        }

        override fun setEventListener(listener: ((EngineEvent) -> Unit)?): Unit = driven {
            this.listener.store(listener)
            if (listener != null) {
                while (true) {
                    val current: List<(EngineEvent) -> Unit> = seen.load()
                    if (seen.compareAndSet(current, current + listener)) break
                }
            }
        }

        override fun start(): Unit = driven { if (!open.load()) callsWithoutHandle.incrementAndFetch() }
        override fun pause(): Unit = driven { if (!open.load()) callsWithoutHandle.incrementAndFetch() }
        override fun resume(): Unit = driven { if (!open.load()) callsWithoutHandle.incrementAndFetch() }
        override fun peakDbfs(): Float? = driven {
            busy()
            if (open.load()) -20f else null
        }

        override fun stop(): Unit = driven {
            if (!open.load()) callsWithoutHandle.incrementAndFetch()
            stops.incrementAndFetch()
            inStop.store(true)
            try {
                busy()
                spinUntil(stopOpen)
            } finally {
                inStop.store(false)
            }
        }

        override fun release(): Unit = driven {
            releases.incrementAndFetch()
            inRelease.store(true)
            try {
                busy()
                spinUntil(releaseOpen)
            } finally {
                open.store(false)
                inRelease.store(false)
            }
        }
    }

    /** A file system that remembers which paths exist and which were deleted, safe across threads. */
    private class StormFs : RecordingFileSystem {
        private val existing: AtomicReference<Set<String>> = AtomicReference(emptySet())
        private val deleted: AtomicReference<List<String>> = AtomicReference(emptyList())

        fun create(path: String) {
            while (true) {
                val current: Set<String> = existing.load()
                if (existing.compareAndSet(current, current + path)) return
            }
        }

        fun has(path: String): Boolean = path in existing.load()
        fun deletions(): List<String> = deleted.load()

        override fun appPrivateDirectory(): String = "/data"
        override fun applicationIdentifier(): String = "app"
        override fun resolve(directory: String, name: String): String = "$directory/$name"
        override fun parentOf(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        override fun ensureWritableDirectory(path: String): Boolean = true
        override fun freeSpaceBytes(path: String): Long = Long.MAX_VALUE
        override fun exists(path: String): Boolean = has(path)

        override fun delete(path: String) {
            while (true) {
                val current: List<String> = deleted.load()
                if (deleted.compareAndSet(current, current + path)) break
            }
            while (true) {
                val current: Set<String> = existing.load()
                if (existing.compareAndSet(current, current - path)) return
            }
        }
    }

    private fun recorder(
        engine: RecorderEngine,
        fileSystem: RecordingFileSystem,
        config: AudioRecorderConfig = AudioRecorderConfig(minimumFreeSpaceBytes = 0L),
        worker: CoroutineContext = Dispatchers.Default,
    ): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = config,
        workerContext = worker,
        epochClock = EpochClock { 1L },
        silenceDebounce = 3.milliseconds,
    )

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            delay(1L)
        }
    }

    private suspend fun jitter(random: Random) {
        when (random.nextInt(3)) {
            0 -> repeat(random.nextInt(4_000)) { Unit }
            1 -> delay(random.nextLong(0L, 4L))
            else -> Unit
        }
    }

    private fun storm(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(45.seconds) { body() } }
    }

    private suspend fun recordAndFinish(recorder: DefaultAudioRecorder, path: String, label: String) {
        assertTrue(recorder.prepare(path).isSuccess, "$label: prepare")
        assertTrue(recorder.start().isSuccess, "$label: start")
        assertTrue(recorder.stop().isSuccess, "$label: stop")
    }

    // --- the ownership fence against three recorders and every kind of late tail ---

    @Test
    fun `late tails of two earlier recorders never delete the file the latest recorder finished`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 31L * round + 7L
            val random = Random(seed)
            val fs = StormFs()
            val path = "/data/storm-$round.m4a"
            val engines: List<StormEngine> = List(2) { StormEngine(onPrepared = { fs.create(it) }) }
            val recorders: List<DefaultAudioRecorder> = engines.map { recorder(it, fs) }
            // 0 = released mid-prepare, 1 = caller cancelled mid-prepare, 2 = the engine fails after opening.
            val kinds: List<Int> = List(2) { random.nextInt(3) }
            val jobs: MutableList<Job> = mutableListOf()
            for (i in 0..1) {
                engines[i].prepareOpen.store(false)
                if (kinds[i] == 2) engines[i].failPrepare.store(true)
                jobs += launch(Dispatchers.Default) { recorders[i].prepare(path) }
                awaitTrue("seed $seed: tail $i inside engine.prepare") { engines[i].inPrepare.load() }
            }
            val openers: List<Job> = (0..1).shuffled(random).map { i: Int ->
                val own = Random(seed + 100L + i)
                launch(Dispatchers.Default) {
                    jitter(own)
                    when (kinds[i]) {
                        0 -> {
                            recorders[i].release()
                            engines[i].prepareOpen.store(true)
                        }
                        1 -> jobs[i].cancel()
                        else -> engines[i].prepareOpen.store(true)
                    }
                }
            }
            val latestEngine = StormEngine(onPrepared = { fs.create(it) })
            val latest: DefaultAudioRecorder = recorder(latestEngine, fs)
            recordAndFinish(latest, path, "seed $seed: latest")
            openers.joinAll()
            jobs.joinAll()
            for (i in 0..1) awaitTrue("seed $seed: tail $i released its engine") { engines[i].releases.load() >= 1 }
            assertTrue(fs.has(path), "seed $seed kinds $kinds: a late tail deleted the latest recorder's file; deletions ${fs.deletions()}")
            assertIs<RecorderState.Completed>(latest.state.value, "seed $seed")
            // The registry is not left holding the path in a deleting state.
            withTimeout(5.seconds) {
                val probe = OutputPathClaim()
                OutputPathOwners.claim(path, probe)
                OutputPathOwners.release(probe)
            }
            recorders.forEach { it.release() }
            latest.release()
        }
    }

    // --- deletion sites the fence does not cover ---

    @Test
    fun `a released recorders finalizing cancel never deletes a file the next recorder now owns`() = storm {
        val fs = StormFs()
        val path = "/data/cancel-tail.m4a"
        val engineA = StormEngine(onPrepared = { fs.create(it) })
        val recorderA: DefaultAudioRecorder = recorder(engineA, fs)
        assertTrue(recorderA.prepare(path).isSuccess)
        assertTrue(recorderA.start().isSuccess)
        engineA.stopOpen.store(false)
        val cancelling: Job = launch(Dispatchers.Default) { recorderA.cancel() }
        awaitTrue("A inside engine.stop") { engineA.inStop.load() }
        // The screen goes away while its discarded take is still being finalized.
        recorderA.release()

        val engineB = StormEngine(onPrepared = { fs.create(it) })
        val recorderB: DefaultAudioRecorder = recorder(engineB, fs)
        recordAndFinish(recorderB, path, "B")
        assertTrue(fs.has(path))

        engineA.stopOpen.store(true)
        withTimeout(10.seconds) { cancelling.join() }
        awaitTrue("A finalizer done") { !recorderA.isFinalizing }
        assertTrue(fs.has(path), "the released recorder's cancel tail deleted the next recorder's recording; deletions ${fs.deletions()}")
        recorderB.release()
    }

    @Test
    fun `a released recorders system-event cleanup never deletes a file the next recorder now owns`() = storm {
        val fs = StormFs()
        val path = "/data/event-tail.m4a"
        val engineA = StormEngine(onPrepared = { fs.create(it) })
        val recorderA: DefaultAudioRecorder = recorder(engineA, fs)
        assertTrue(recorderA.prepare(path).isSuccess)
        engineA.releaseOpen.store(false)
        // The system ends a recorder that was only prepared: its empty file is cleaned up off the lock.
        engineA.currentListener()!!.invoke(EngineEvent.Interrupted(InterruptionReason.StorageLow))
        awaitTrue("A cleanup inside engine.release") { engineA.inRelease.load() }
        recorderA.release()

        val engineB = StormEngine(onPrepared = { fs.create(it) })
        val recorderB: DefaultAudioRecorder = recorder(engineB, fs)
        recordAndFinish(recorderB, path, "B")
        assertTrue(fs.has(path))

        engineA.releaseOpen.store(true)
        awaitTrue("A cleanup done") { !recorderA.isFinalizing }
        assertTrue(fs.has(path), "the released recorder's cleanup deleted the next recorder's recording; deletions ${fs.deletions()}")
        recorderB.release()
    }

    @Test
    fun `releasing a recorder that is only prepared never deletes the file a newer recorder took over`() = storm {
        val fs = StormFs()
        val path = "/data/screen-swap.m4a"
        val engineA = StormEngine(onPrepared = { fs.create(it) })
        val recorderA: DefaultAudioRecorder = recorder(engineA, fs)
        assertTrue(recorderA.prepare(path).isSuccess)

        // The next screen is created before the old one is destroyed and records to the same path.
        val engineB = StormEngine(onPrepared = { fs.create(it) })
        val recorderB: DefaultAudioRecorder = recorder(engineB, fs)
        recordAndFinish(recorderB, path, "B")

        recorderA.release()
        assertTrue(fs.has(path), "releasing the old Ready recorder deleted the newer recorder's recording; deletions ${fs.deletions()}")
        recorderB.release()
    }

    // --- many callers on real threads ---

    // --- many callers on real threads ---

    @Test
    fun `eight callers racing stop and cancel finalize the recording exactly once`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 977L * round + 3L
            val random = Random(seed)
            val fs = StormFs()
            val path = "/data/race-$round.m4a"
            val engine = StormEngine(onPrepared = { fs.create(it) })
            val recorder: DefaultAudioRecorder = recorder(engine, fs)
            assertTrue(recorder.prepare(path).isSuccess, "seed $seed")
            assertTrue(recorder.start().isSuccess, "seed $seed")
            engine.stopOpen.store(false)
            val callers = (0 until 8).map { i: Int ->
                async(Dispatchers.Default) {
                    if (i % 2 == 0) recorder.stop().isSuccess else recorder.cancel().isSuccess
                }
            }
            awaitTrue("seed $seed: finalization started") { engine.inStop.load() }
            jitter(random)
            engine.stopOpen.store(true)
            val outcomes: List<Boolean> = withTimeout(10.seconds) { callers.awaitAll() }
            assertEquals(1, outcomes.count { it }, "seed $seed: successes $outcomes")
            assertEquals(1, engine.stops.load(), "seed $seed: engine.stop calls")
            val state: RecorderState = recorder.state.value
            assertTrue(
                state is RecorderState.Completed || state == RecorderState.Idle,
                "seed $seed: state $state",
            )
            assertEquals(0, engine.overlaps.load(), "seed $seed: overlapping engine calls")
            recorder.release()
            awaitTrue("seed $seed: handle freed") { !engine.open.load() }
        }
    }

    @Test
    fun `eight callers preparing at once leave exactly one file and one open handle`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 1_299_709L * round + 1L
            val fs = StormFs()
            val engine = StormEngine(onPrepared = { fs.create(it) }, busyMicros = 400L)
            val recorder: DefaultAudioRecorder = recorder(engine, fs)
            val paths: List<String> = List(8) { "/data/prep-$round-$it.m4a" }
            val results = paths.mapIndexed { i: Int, path: String ->
                val own = Random(seed + i)
                async(Dispatchers.Default) {
                    jitter(own)
                    recorder.prepare(path).isSuccess
                }
            }.awaitAll()
            assertTrue(results.any { it }, "seed $seed: no prepare succeeded: $results")
            val state: RecorderState = recorder.state.value
            val ready: RecorderState.Ready = assertIs<RecorderState.Ready>(state, "seed $seed: $state")
            assertEquals(0, engine.overlaps.load(), "seed $seed: engine.prepare overlapped another engine call")
            // Every prepare that was superseded by a later one cleaned up its own file.
            val left: List<String> = paths.filter { fs.has(it) }
            assertEquals(listOf(ready.outputPath), left, "seed $seed: files left behind: $left (deletions ${fs.deletions()})")
            assertTrue(engine.open.load(), "seed $seed: the recorder is Ready but holds no handle")
            recorder.release()
            awaitTrue("seed $seed: handle freed") { !engine.open.load() }
            assertFalse(fs.has(ready.outputPath), "seed $seed: release left the Ready file behind")
        }
    }

    @Test
    fun `a prepare cancelled or released at a random instant leaves either a Ready recorder or no handle and no file`() = storm {
        repeat(ROUNDS * 5) { round: Int ->
            val seed: Long = 86_028_121L * round + 9L
            val own = Random(seed)
            val fs = StormFs()
            val engine = StormEngine(onPrepared = { fs.create(it) }, busyMicros = 300L)
            val recorder: DefaultAudioRecorder = recorder(engine, fs)
            val path = "/data/instant-$round.m4a"
            val released: Boolean = round % 2 == 1
            val call = async(Dispatchers.Default) { recorder.prepare(path) }
            jitter(own)
            if (released) recorder.release() else call.cancel()
            val outcome: Result<RecorderResult<String>> = runCatching { call.await() }
            val prepared: Boolean = outcome.getOrNull()?.isSuccess == true
            awaitTrue("seed $seed: finalization settles") { !recorder.isFinalizing }
            if (released) {
                // Whatever the instant, a released recorder holds no handle and leaves no file.
                awaitTrue("seed $seed: handle freed") { !engine.open.load() }
                assertFalse(fs.has(path), "seed $seed: released, yet the file is still there (prepared=$prepared)")
                assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
            } else if (prepared) {
                assertIs<RecorderState.Ready>(recorder.state.value, "seed $seed")
                assertTrue(engine.open.load() && fs.has(path), "seed $seed: Ready without handle or file")
            } else {
                assertEquals(RecorderState.Idle, recorder.state.value, "seed $seed: ${outcome}")
                assertFalse(engine.open.load(), "seed $seed: a cancelled prepare left the handle open")
                assertFalse(fs.has(path), "seed $seed: a cancelled prepare left its file; deletions ${fs.deletions()}")
                // Usable again.
                assertTrue(recorder.prepare("/data/instant-$round-again.m4a").isSuccess, "seed $seed: unusable after the cancel")
            }
            recorder.release()
            awaitTrue("seed $seed: handle freed at the end") { !engine.open.load() }
        }
    }

    @Test
    fun `a stop or cancel whose caller is cancelled or released at a random instant still settles completely`() = storm {
        repeat(ROUNDS * 5) { round: Int ->
            val seed: Long = 49_979_687L * round + 5L
            val own = Random(seed)
            val fs = StormFs()
            val engine = StormEngine(onPrepared = { fs.create(it) }, busyMicros = 300L)
            val recorder: DefaultAudioRecorder = recorder(engine, fs)
            val path = "/data/settle-$round.m4a"
            assertTrue(recorder.prepare(path).isSuccess, "seed $seed")
            assertTrue(recorder.start().isSuccess, "seed $seed")
            // 0 = stop, caller cancelled; 1 = cancel, caller cancelled; 2 = stop, recorder released.
            val kind: Int = round % 3
            val call = async(Dispatchers.Default) {
                if (kind == 1) recorder.cancel() else recorder.stop()
            }
            jitter(own)
            if (kind == 2) recorder.release() else call.cancel()
            runCatching { call.await() }
            awaitTrue("seed $seed: finalization settles") { !recorder.isFinalizing }
            val state: RecorderState = recorder.state.value
            when (kind) {
                0 -> {
                    assertTrue(fs.has(path), "seed $seed: a stopped recording must be kept")
                    if (state is RecorderState.Completed) {
                        awaitTrue("seed $seed: handle freed") { !engine.open.load() }
                    } else {
                        // The caller was cancelled before its body ever ran: nothing happened at all.
                        assertIs<RecorderState.Recording>(state, "seed $seed")
                    }
                }
                1 -> if (state == RecorderState.Idle) {
                    awaitTrue("seed $seed: handle freed") { !engine.open.load() }
                    assertFalse(fs.has(path), "seed $seed: a cancelled recording must be deleted")
                } else {
                    assertIs<RecorderState.Recording>(state, "seed $seed")
                }
                else -> {
                    assertEquals(RecorderState.Released, state, "seed $seed")
                    awaitTrue("seed $seed: handle freed after release") { !engine.open.load() }
                    assertTrue(fs.has(path), "seed $seed: release must keep a recorded file; deletions ${fs.deletions()}")
                }
            }
            recorder.release()
            awaitTrue("seed $seed: handle freed at the end") { !engine.open.load() }
            assertEquals(0, engine.overlaps.load(), "seed $seed: overlapping engine calls")
        }
    }

    @Test
    fun `metering stops once the last collector leaves however the collectors came and went`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 32_452_843L * round + 13L
            val fs = StormFs()
            val engine = StormEngine(onPrepared = { fs.create(it) }, busyMicros = 100L)
            val config = AudioRecorderConfig(minimumFreeSpaceBytes = 0L, levelUpdateInterval = 1.milliseconds)
            val recorder: DefaultAudioRecorder = recorder(engine, fs, config)
            recordingStart(recorder, "/data/meter-$round.m4a", seed)
            val churn: List<Job> = (0 until 6).map { w: Int ->
                val own = Random(seed + w)
                launch(Dispatchers.Default) {
                    repeat(60) {
                        val watcher: Job = launch(Dispatchers.Default) {
                            if (own.nextBoolean()) recorder.level.collect { } else recorder.levelSamples.collect { }
                        }
                        jitter(own)
                        watcher.cancel()
                    }
                }
            }
            churn.joinAll()
            // Everyone left. Whatever sample was in flight finishes; after that the engine is not asked again.
            delay(30L)
            val before: Int = engine.totalCalls.load()
            delay(40L)
            assertEquals(before, engine.totalCalls.load(), "seed $seed: the meter kept sampling with nobody collecting")
            assertEquals(0f, recorder.level.value, "seed $seed: level did not return to zero")
            // And it comes back for the next collector.
            val sampled: Float? = withTimeoutOrNull(2.seconds) { recorder.levelSamples.first() }
            assertTrue(sampled != null, "seed $seed: the meter never restarted for a new collector")
            assertTrue(recorder.stop().isSuccess, "seed $seed")
            recorder.release()
        }
    }

    private suspend fun recordingStart(recorder: DefaultAudioRecorder, path: String, seed: Long) {
        assertTrue(recorder.prepare(path).isSuccess, "seed $seed: prepare")
        assertTrue(recorder.start().isSuccess, "seed $seed: start")
    }

    /**
     * An engine whose every call runs under a lock of its own and which reports from inside its calls
     * and from a platform thread under that same lock: the recorder's lock and this one are taken in
     * opposite orders by the two sides.
     */
    private class LockedEngine : RecorderEngine {
        val lock: ReentrantLockHandle = newReentrantLock()
        private val listener: AtomicReference<((EngineEvent) -> Unit)?> = AtomicReference(null)
        val open: AtomicBoolean = AtomicBoolean(false)

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }

        fun reportFromPlatformThread(silenced: Boolean) {
            locked { listener.load()?.invoke(EngineEvent.InputSilenced(silenced)) }
        }

        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true
        override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
            locked { open.store(true) }
        }
        override fun setEventListener(listener: ((EngineEvent) -> Unit)?) = locked { this.listener.store(listener) }
        override fun start() {
            locked { listener.load()?.invoke(EngineEvent.InputSilenced(false)) }
        }
        override fun pause() = locked { repeat(50) { Unit } }
        override fun resume() {
            locked { listener.load()?.invoke(EngineEvent.InputSilenced(false)) }
        }
        override fun peakDbfs(): Float? = locked { -20f }
        override fun stop() = locked { repeat(200) { Unit } }
        override fun release() = locked { open.store(false) }
    }

    @Test
    fun `an engine reporting under its own lock while the recorder holds its lock never deadlocks`() = storm {
        repeat(STORM_ROUNDS) { round: Int ->
            val seed: Long = 373_587_883L * round + 3L
            val engine = LockedEngine()
            val config = AudioRecorderConfig(
                minimumFreeSpaceBytes = 0L,
                durationUpdateInterval = 1.milliseconds,
                levelUpdateInterval = 1.milliseconds,
            )
            val fs = StormFs()
            val recorder: DefaultAudioRecorder = recorder(engine, fs, config)
            val done = AtomicBoolean(false)
            val platform: List<Job> = (0..1).map { w: Int ->
                val own = Random(seed + w)
                launch(Dispatchers.Default) {
                    while (!done.load()) {
                        engine.reportFromPlatformThread(own.nextBoolean())
                        if (own.nextInt(3) == 0) delay(1L)
                    }
                }
            }
            val collector: Job = launch(Dispatchers.Default) { recorder.level.collect { } }
            val own = Random(seed)
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                repeat(OPS) {
                    when (own.nextInt(6)) {
                        0 -> recorder.prepare("/data/locked-$round.m4a")
                        1 -> recorder.start()
                        2 -> recorder.pause()
                        3 -> recorder.resume()
                        4 -> recorder.stop()
                        else -> recorder.cancel()
                    }
                }
            }
            done.store(true)
            platform.joinAll()
            collector.cancel()
            recorder.release()
            assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
            awaitTrue("seed $seed: handle freed") { !engine.open.load() }
        }
    }

    @Test
    fun `engine events and level collectors on other threads never overlap the engine or outlive release`() = storm {
        eventStorm(callers = 1)
    }

    @Test
    fun `three callers hammering one recorder while events arrive never overlap the engine or outlive release`() = storm {
        eventStorm(callers = 3)
    }

    @Test
    fun `one thread running the caller and the worker never wedges under events and collectors`() = storm {
        eventStorm(callers = 1, serial = true)
    }

    private suspend fun CoroutineScope.eventStorm(callers: Int, serial: Boolean = false) {
        repeat(STORM_ROUNDS) { round: Int ->
            val seed: Long = 7_919L * round + 11L + callers
            val fs = StormFs()
            val engine = StormEngine(onPrepared = { fs.create(it) }, busyMicros = 600L)
            val config = AudioRecorderConfig(
                minimumFreeSpaceBytes = 0L,
                durationUpdateInterval = 1.milliseconds,
                levelUpdateInterval = 1.milliseconds,
            )
            val serialContext: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
            val recorder: DefaultAudioRecorder =
                if (serial) recorder(engine, fs, config, serialContext) else recorder(engine, fs, config)
            val done = AtomicBoolean(false)
            val leaked = AtomicInt(0)

            val eventThreads: List<Job> = (0..1).map { w: Int ->
                val own = Random(seed + w)
                launch(Dispatchers.Default) {
                    while (!done.load()) {
                        val target: ((EngineEvent) -> Unit)? =
                            if (own.nextInt(3) == 0) engine.anyListener(own) else engine.currentListener()
                        val event: EngineEvent = when (own.nextInt(40)) {
                            0 -> EngineEvent.Interrupted(InterruptionReason.StorageLow)
                            1 -> EngineEvent.Interrupted(InterruptionReason.EngineDied(own.nextInt(5)))
                            2 -> EngineEvent.Interrupted(InterruptionReason.AudioSessionInterrupted)
                            else -> EngineEvent.InputSilenced(own.nextBoolean())
                        }
                        target?.invoke(event)
                        if (own.nextInt(4) == 0) delay(1L) else repeat(own.nextInt(300)) { Unit }
                    }
                }
            }
            val collectors: List<Job> = (0..1).map { w: Int ->
                val own = Random(seed + 10L + w)
                launch(Dispatchers.Default) {
                    while (!done.load()) {
                        withTimeoutOrNull(own.nextLong(1L, 5L)) {
                            if (w == 0) recorder.level.collect { } else recorder.levelSamples.collect { }
                        }
                    }
                }
            }
            val watcher: Job = launch(Dispatchers.Default) {
                var released = false
                recorder.state.collect { state: RecorderState ->
                    if (state == RecorderState.Released) released = true else if (released) leaked.incrementAndFetch()
                }
            }

            // With several callers, one more consumer restarts a recording the system ended and cleans up
            // after a failure, from the collector's own thread.
            val reactor: Job? = if (callers == 1) null else launch(Dispatchers.Default) {
                recorder.state.collect { state: RecorderState ->
                    when (state) {
                        is RecorderState.Interrupted -> {
                            recorder.prepare("/data/reactor-$round.m4a")
                            recorder.start()
                        }
                        is RecorderState.Failed -> if (state.outputPath != null) recorder.cancel()
                        else -> Unit
                    }
                }
            }

            // One caller is a single-threaded dispatcher, the documented way to drive a recorder;
            // several callers go beyond that contract, which the lock is still meant to survive.
            val caller: CoroutineDispatcher =
                if (serial) serialContext else if (callers == 1) Dispatchers.Default.limitedParallelism(1) else Dispatchers.Default
            (0 until callers).map { c: Int ->
                launch(caller) {
                    val own = Random(seed + 1_000L * c)
                    repeat(OPS) { op: Int ->
                        when (own.nextInt(8)) {
                            0, 1 -> recorder.prepare("/data/storm-$round-${own.nextInt(3)}.m4a")
                            2 -> recorder.start()
                            3 -> recorder.pause()
                            4 -> recorder.resume()
                            5 -> recorder.stop()
                            6 -> recorder.cancel()
                            else -> delay(own.nextLong(0L, 3L))
                        }
                        if (op % 25 == 0) awaitTrue("seed $seed: finalization settles") { !recorder.isFinalizing }
                    }
                }
            }.joinAll()
            reactor?.cancelAndJoin()
            // The platform goes quiet before the recovery check: a system event arriving during it
            // would legitimately end the recording the check is trying to make.
            done.store(true)
            (eventThreads + collectors).joinAll()
            withContext(caller) {
                // Not wedged: whatever state the storm left, the recorder can still record.
                awaitTrue("seed $seed: finalization settles at the end") { !recorder.isFinalizing }
                val state: RecorderState = recorder.state.value
                if (state is RecorderState.Ready || state is RecorderState.Recording || state is RecorderState.Paused) {
                    recorder.cancel()
                }
                recordAndFinish(recorder, "/data/storm-$round-final.m4a", "seed $seed: after the storm")
            }
            assertEquals(0, engine.overlaps.load(), "seed $seed: the engine was driven from two threads at once")
            assertEquals(0, engine.callsWithoutHandle.load(), "seed $seed: engine driven with no handle")

            recorder.release()
            awaitTrue("seed $seed: finalization settles after release") { !recorder.isFinalizing }
            assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
            awaitTrue("seed $seed: the microphone is closed after release") { !engine.open.load() }
            val callsAtRest: Int = engine.totalCalls.load()
            delay(30L)
            assertEquals(callsAtRest, engine.totalCalls.load(), "seed $seed: the engine was driven after release")
            watcher.cancel()
            assertEquals(0, leaked.load(), "seed $seed: a state other than Released was published after Released")
            assertFalse(recorder.isFinalizing)
        }
    }

    private companion object {
        const val ROUNDS: Int = 40
        const val STORM_ROUNDS: Int = 12
        const val OPS: Int = 150
    }
}
