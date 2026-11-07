package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Lifecycle chaos round 2 for the recorder: a `release()` that lands while a `stop()` is being
 * finalized, `cancel()` and a system event racing `stop()`, a `prepare()` that waits for a
 * finalization and is cancelled while it waits, two releases at once, a background `pause()` racing
 * a screen's `release()`, a released recorder whose late `prepare` tail meets the next screen's file,
 * and a `release()` issued from inside a state collector.
 *
 * Real-thread tests use a barrier and seeded jitter (the seed is in each failure message) and bound
 * every wait, including the engine's own blocking calls, in real time.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class RecorderLifecycleChaosTest {

    /**
     * Thread-safe engine. [stopOpen] / [prepareOpen] hold `stop()` / `prepare()` until opened (bounded
     * at ten seconds so a test bug cannot hang the run), and every call is checked for overlap with
     * another, since an engine is never driven from two threads at once.
     */
    private class ChaosEngine(private val onPrepared: (String) -> Unit = {}) : RecorderEngine {
        val stopOpen: AtomicBoolean = AtomicBoolean(true)
        val prepareOpen: AtomicBoolean = AtomicBoolean(true)
        val inStop: AtomicBoolean = AtomicBoolean(false)
        val inPrepare: AtomicBoolean = AtomicBoolean(false)
        val releases: AtomicInt = AtomicInt(0)
        val stops: AtomicInt = AtomicInt(0)
        val prepares: AtomicInt = AtomicInt(0)
        val starts: AtomicInt = AtomicInt(0)
        val prepareOverlaps: AtomicInt = AtomicInt(0)
        private val preparing: AtomicInt = AtomicInt(0)
        val overlaps: AtomicInt = AtomicInt(0)
        val callsAfterRelease: AtomicInt = AtomicInt(0)
        val released: AtomicBoolean = AtomicBoolean(false)
        private val driving: AtomicInt = AtomicInt(0)
        private val listener: AtomicReference<((EngineEvent) -> Unit)?> = AtomicReference(null)

        fun currentListener(): ((EngineEvent) -> Unit)? = listener.load()

        private inline fun <T> driven(block: () -> T): T {
            if (driving.incrementAndFetch() > 1) overlaps.incrementAndFetch()
            try {
                return block()
            } finally {
                driving.decrementAndFetch()
            }
        }

        private fun spinUntil(open: AtomicBoolean) {
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (!open.load() && !deadline.hasPassedNow()) Unit
        }

        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true

        override suspend fun prepare(outputPath: String, config: AudioRecorderConfig, maxFileSizeBytes: Long?) {
            inPrepare.store(true)
            if (preparing.incrementAndFetch() > 1) prepareOverlaps.incrementAndFetch()
            try {
                val deadline = TimeSource.Monotonic.markNow() + 10.seconds
                while (!prepareOpen.load() && !deadline.hasPassedNow()) delay(1L)
                driven { released.store(false) }
                prepares.incrementAndFetch()
                onPrepared(outputPath)
            } finally {
                preparing.decrementAndFetch()
                inPrepare.store(false)
            }
        }

        override fun setEventListener(listener: ((EngineEvent) -> Unit)?): Unit = driven { this.listener.store(listener) }

        override fun start(): Unit = driven {
            starts.incrementAndFetch()
            if (released.load()) callsAfterRelease.incrementAndFetch()
        }
        override fun pause(): Unit = driven { if (released.load()) callsAfterRelease.incrementAndFetch() }
        override fun resume(): Unit = driven { if (released.load()) callsAfterRelease.incrementAndFetch() }
        override fun peakDbfs(): Float? = null

        override fun stop(): Unit = driven {
            inStop.store(true)
            try {
                stops.incrementAndFetch()
                spinUntil(stopOpen)
            } finally {
                inStop.store(false)
            }
        }

        override fun release(): Unit = driven {
            releases.incrementAndFetch()
            released.store(true)
        }
    }

    /** A filesystem that remembers which paths exist and which were deleted, safe across threads. */
    private class ChaosFileSystem : RecordingFileSystem {
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
        worker: kotlin.coroutines.CoroutineContext = Dispatchers.Default,
    ): DefaultAudioRecorder = DefaultAudioRecorder(
        engine = engine,
        fileSystem = fileSystem,
        config = AudioRecorderConfig(minimumFreeSpaceBytes = 0L),
        workerContext = worker,
        epochClock = EpochClock { 1L },
    )

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            delay(1L)
        }
    }

    private suspend fun awaitSettled(recorder: DefaultAudioRecorder, label: String) {
        awaitTrue("$label: finalization to end") { !recorder.isFinalizing }
    }

    private suspend fun awaitOne(engine: ChaosEngine, label: String) {
        awaitTrue("$label: engine released") { engine.releases.load() >= 1 }
    }

    // --- release() while stop() is finalizing ---

    @Test
    fun `a release landing while stop is finalizing leaves the engine to the finalizer and keeps the file`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(ROUNDS_SLOW) { round: Int ->
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        val path = "/data/release-in-stop-$round.m4a"
                        assertTrue(recorder.prepare(path).isSuccess, "round $round")
                        assertTrue(recorder.start().isSuccess, "round $round")
                        val before: Int = engine.releases.load()
                        engine.stopOpen.store(false)

                        var stopResult: RecorderResult<RecordedFile>? = null
                        val stopping: Job = launch(Dispatchers.Default) { stopResult = recorder.stop() }
                        awaitTrue("round $round: stop reached the engine") { engine.inStop.load() }

                        recorder.release()
                        assertEquals(RecorderState.Released, recorder.state.value, "round $round")
                        assertEquals(before, engine.releases.load(), "round $round: release() freed an engine a stop was still driving")
                        engine.stopOpen.store(true)
                        try {
                            withTimeout(10.seconds) { stopping.join() }
                        } catch (timeout: CancellationException) {
                            fail("round $round: stop never returned after the engine finished")
                        }
                        awaitSettled(recorder, "round $round")

                        assertEquals(before + 1, engine.releases.load(), "round $round: engine releases")
                        assertEquals(0, engine.overlaps.load(), "round $round: the engine was driven from two threads")
                        assertEquals(RecorderState.Released, recorder.state.value, "round $round: Released is terminal")
                        assertTrue(stopResult?.isSuccess == true, "round $round: the finalized stop was reported as $stopResult")
                        assertTrue(fs.deletions().isEmpty(), "round $round: a recording was deleted: ${fs.deletions()}")
                    }
                }
            }
        }

    // --- cancel() and a system event against stop(), on real threads ---

    @Test
    fun `cancel and stop racing each other agree on one outcome for the file and the engine`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ROUNDS) { round: Int ->
                        val seed: Int = CANCEL_STOP_SEED + round
                        val random = Random(seed)
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        val path = "/data/cancel-stop-$round.m4a"
                        assertTrue(recorder.prepare(path).isSuccess, "seed $seed")
                        assertTrue(recorder.start().isSuccess, "seed $seed")
                        val before: Int = engine.releases.load()

                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val stopJitter: Int = random.nextInt(0, 3)
                        val cancelJitter: Int = random.nextInt(0, 3)
                        var stopResult: RecorderResult<RecordedFile>? = null
                        var cancelResult: RecorderResult<Unit>? = null
                        val stopping: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(stopJitter) { yield() }
                            stopResult = recorder.stop()
                        }
                        val cancelling: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(cancelJitter) { yield() }
                            cancelResult = recorder.cancel()
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        try {
                            withTimeout(10.seconds) { listOf(stopping, cancelling).joinAll() }
                        } catch (timeout: CancellationException) {
                            fail("seed $seed: hung, state ${recorder.state.value}")
                        }
                        awaitSettled(recorder, "seed $seed")

                        val state: RecorderState = recorder.state.value
                        when (state) {
                            is RecorderState.Completed -> {
                                assertTrue(stopResult?.isSuccess == true, "seed $seed: Completed but stop said $stopResult")
                                assertFalse(cancelResult?.isSuccess == true, "seed $seed: both stop and cancel succeeded")
                                assertTrue(fs.deletions().isEmpty(), "seed $seed: a stopped recording was deleted")
                            }

                            RecorderState.Idle -> {
                                assertTrue(cancelResult?.isSuccess == true, "seed $seed: Idle but cancel said $cancelResult")
                                assertFalse(stopResult?.isSuccess == true, "seed $seed: both stop and cancel succeeded")
                                assertEquals(listOf(path), fs.deletions(), "seed $seed: the cancelled file must be deleted once")
                            }

                            else -> fail("seed $seed: unexpected state $state")
                        }
                        assertEquals(before + 1, engine.releases.load(), "seed $seed: engine releases")
                        assertEquals(0, engine.overlaps.load(), "seed $seed: engine driven from two threads")
                        recorder.release()
                    }
                }
            }
        }

    @Test
    fun `a system event racing stop never turns the stop into a failure or loses the file`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ROUNDS) { round: Int ->
                        val seed: Int = EVENT_STOP_SEED + round
                        val random = Random(seed)
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        val path = "/data/event-stop-$round.m4a"
                        assertTrue(recorder.prepare(path).isSuccess, "seed $seed")
                        assertTrue(recorder.start().isSuccess, "seed $seed")
                        val before: Int = engine.releases.load()
                        val platformListener = requireNotNull(engine.currentListener())

                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val eventJitter: Int = random.nextInt(0, 3)
                        val stopJitter: Int = random.nextInt(0, 3)
                        var stopResult: RecorderResult<RecordedFile>? = null
                        val stopping: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(stopJitter) { yield() }
                            stopResult = recorder.stop()
                        }
                        val event: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(eventJitter) { yield() }
                            platformListener(EngineEvent.Interrupted(InterruptionReason.EngineDied(100)))
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        try {
                            withTimeout(10.seconds) { listOf(stopping, event).joinAll() }
                        } catch (timeout: CancellationException) {
                            fail("seed $seed: hung, state ${recorder.state.value}")
                        }
                        awaitSettled(recorder, "seed $seed")

                        val result: RecorderResult<RecordedFile>? = stopResult
                        assertTrue(result?.isSuccess == true, "seed $seed: stop reported $result")
                        assertEquals(path, (result as RecorderResult.Success).value.path, "seed $seed")
                        val state: RecorderState = recorder.state.value
                        assertTrue(
                            state is RecorderState.Completed || state is RecorderState.Interrupted,
                            "seed $seed: final state $state",
                        )
                        assertEquals(before + 1, engine.releases.load(), "seed $seed: engine releases")
                        assertEquals(1, engine.stops.load(), "seed $seed: the container was finalized ${engine.stops.load()} times")
                        assertEquals(0, engine.overlaps.load(), "seed $seed: engine driven from two threads")
                        assertTrue(fs.deletions().isEmpty(), "seed $seed: the recording was deleted")
                        recorder.release()
                    }
                }
            }
        }

    // --- prepare() waiting for a finalization, cancelled while it waits ---

    @Test
    fun `a prepare cancelled while it waits for a stop to finalize changes nothing and a later prepare works`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(ROUNDS_SLOW) { round: Int ->
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        val path = "/data/wait-$round.m4a"
                        assertTrue(recorder.prepare(path).isSuccess, "round $round")
                        assertTrue(recorder.start().isSuccess, "round $round")
                        engine.stopOpen.store(false)
                        val stopping: Job = launch(Dispatchers.Default) { recorder.stop() }
                        awaitTrue("round $round: stop reached the engine") { engine.inStop.load() }

                        val waiting: Job = launch(Dispatchers.Default) { recorder.prepare("/data/next-$round.m4a") }
                        delay(2L)
                        waiting.cancel()
                        waiting.join()
                        engine.stopOpen.store(true)
                        stopping.join()
                        awaitSettled(recorder, "round $round")

                        assertIs<RecorderState.Completed>(recorder.state.value, "round $round: the cancelled prepare changed the state")
                        assertTrue(recorder.prepare("/data/next-$round.m4a").isSuccess, "round $round: a later prepare failed")
                        assertIs<RecorderState.Ready>(recorder.state.value, "round $round")
                        assertEquals(0, engine.overlaps.load(), "round $round: engine driven from two threads")
                        assertTrue(fs.deletions().isEmpty(), "round $round: deleted ${fs.deletions()}")
                        recorder.release()
                    }
                }
            }
        }

    @Test
    fun `a release right behind a system interruption frees the engine once and keeps the recording`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(ROUNDS_SLOW) { round: Int ->
                        val seed: Int = EVENT_RELEASE_SEED + round
                        val random = Random(seed)
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        assertTrue(recorder.prepare("/data/event-release-$round.m4a").isSuccess, "seed $seed")
                        assertTrue(recorder.start().isSuccess, "seed $seed")
                        val before: Int = engine.releases.load()
                        val platformListener = requireNotNull(engine.currentListener())

                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val eventJitter: Int = random.nextInt(0, 3)
                        val releaseJitter: Int = random.nextInt(0, 3)
                        val event: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(eventJitter) { yield() }
                            platformListener(EngineEvent.Interrupted(InterruptionReason.EngineDied(100)))
                        }
                        val releasing: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(releaseJitter) { yield() }
                            recorder.release()
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { listOf(event, releasing).joinAll() }
                        awaitSettled(recorder, "seed $seed")
                        awaitTrue("seed $seed: engine released") { engine.releases.load() > before }

                        assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
                        // A second, redundant release of an engine whose finalizer already freed it is
                        // allowed (the engine contract makes release idempotent); driving it from two
                        // threads at once, or finalizing twice, is not.
                        assertTrue(engine.releases.load() in (before + 1)..(before + 2), "seed $seed: engine releases ${engine.releases.load()} after $before")
                        assertEquals(0, engine.overlaps.load(), "seed $seed: engine driven from two threads")
                        assertTrue(engine.stops.load() <= 1, "seed $seed: finalized ${engine.stops.load()} times")
                        assertTrue(fs.deletions().isEmpty(), "seed $seed: the recording was deleted")
                    }
                }
            }
        }

    @Test
    fun `a release behind a system interruption that is still finalizing leaves the engine to the finalizer`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(ROUNDS_SLOW) { round: Int ->
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        assertTrue(recorder.prepare("/data/held-$round.m4a").isSuccess, "round $round")
                        assertTrue(recorder.start().isSuccess, "round $round")
                        val before: Int = engine.releases.load()
                        val platformListener = requireNotNull(engine.currentListener())
                        engine.stopOpen.store(false)

                        platformListener(EngineEvent.Interrupted(InterruptionReason.EngineDied(100)))
                        awaitTrue("round $round: the interruption reached the engine") { engine.inStop.load() }
                        recorder.release()
                        assertEquals(RecorderState.Released, recorder.state.value, "round $round")
                        assertEquals(before, engine.releases.load(), "round $round: release() freed an engine an interruption was still finalizing")
                        engine.stopOpen.store(true)
                        awaitSettled(recorder, "round $round")
                        awaitTrue("round $round: engine released") { engine.releases.load() > before }

                        assertEquals(RecorderState.Released, recorder.state.value, "round $round: Released is terminal")
                        assertEquals(before + 1, engine.releases.load(), "round $round: engine releases")
                        assertEquals(0, engine.overlaps.load(), "round $round: engine driven from two threads")
                        assertTrue(fs.deletions().isEmpty(), "round $round: the recording was deleted")
                    }
                }
            }
        }

    @Test
    fun `a silence that never lasts a whole debounce across background cycles never ends the recording but one that does always does`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            repeat(100) { cycle: Int ->
                fixture.engine.emit(EngineEvent.InputSilenced(true))
                passTime(fixture, 300.milliseconds)
                // The app goes to the background mid-silence; nothing is captured while paused.
                assertTrue(fixture.recorder.pause().isSuccess, "cycle $cycle")
                passTime(fixture, 5.seconds)
                assertIs<RecorderState.Paused>(fixture.recorder.state.value, "cycle $cycle: a silence ended a paused recording")
                assertTrue(fixture.recorder.resume().isSuccess, "cycle $cycle")
                passTime(fixture, 100.milliseconds)
                fixture.engine.emit(EngineEvent.InputSilenced(false))
                passTime(fixture, 1.seconds)
                assertEquals(RecorderState.Recording(path), fixture.recorder.state.value, "cycle $cycle: a brief silence ended the recording")
            }
            fixture.engine.emit(EngineEvent.InputSilenced(true))
            passTime(fixture, 500.milliseconds)
            runCurrent()
            val ended: RecorderState = fixture.recorder.state.value
            assertIs<RecorderState.Interrupted>(ended, "a silence that lasted a whole debounce went unnoticed")
            assertEquals(InterruptionReason.MicrophoneSilenced, ended.reason)
        }

    @Test
    fun `a stop whose caller is cancelled while it waits for another finalization still gets its turn`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(ROUNDS_SLOW) { round: Int ->
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        val path = "/data/wait-stop-$round.m4a"
                        assertTrue(recorder.prepare(path).isSuccess, "round $round")
                        assertTrue(recorder.start().isSuccess, "round $round")
                        engine.stopOpen.store(false)
                        val first: Job = launch(Dispatchers.Default) { recorder.stop() }
                        awaitTrue("round $round: stop reached the engine") { engine.inStop.load() }
                        val waiting: Job = launch(Dispatchers.Default) { recorder.stop() }
                        delay(2L)
                        waiting.cancel()
                        waiting.join()
                        engine.stopOpen.store(true)
                        first.join()
                        awaitSettled(recorder, "round $round")

                        assertIs<RecorderState.Completed>(recorder.state.value, "round $round")
                        assertEquals(1, engine.stops.load(), "round $round: the container was finalized ${engine.stops.load()} times")
                        assertIs<RecorderError.IllegalState>(recorder.stop().errorOrNull(), "round $round: a stop after Completed")
                        assertTrue(fs.deletions().isEmpty(), "round $round")
                        recorder.release()
                    }
                }
            }
        }

    @Test
    fun `a level collector that releases the recorder on the first sound frees the engine once`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val before: Int = fixture.engine.releaseCount
            fixture.engine.defaultPeak = -6f
            val collector: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.recorder.level.collect { level: Float -> if (level > 0f) fixture.recorder.release() }
            }
            runCurrent()
            passTime(fixture, 2.seconds)

            assertEquals(RecorderState.Released, fixture.recorder.state.value)
            assertEquals(before + 1, fixture.engine.releaseCount, "engine releases")
            assertTrue(fixture.engine.callsDuringPeak.isEmpty(), "engine driven inside a sample: ${fixture.engine.callsDuringPeak}")
            assertEquals(0f, fixture.recorder.level.value, "level after release")
            val samples: Int = fixture.engine.peakCalls
            passTime(fixture, 5.seconds)
            assertEquals(samples, fixture.engine.peakCalls, "the meter kept sampling")
            assertEquals(0, fixture.scope.coroutineContext[Job]!!.children.count())
            collector.cancel()
        }

    @Test
    fun `two prepares and two starts at once reach the engine once each`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ROUNDS) { round: Int ->
                        val engine = ChaosEngine()
                        val recorder: DefaultAudioRecorder = recorder(engine, ChaosFileSystem())
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val results: AtomicReference<List<Boolean>> = AtomicReference(emptyList())
                        suspend fun together(block: suspend () -> Boolean) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            val ok: Boolean = block()
                            while (true) {
                                val current: List<Boolean> = results.load()
                                if (results.compareAndSet(current, current + ok)) return
                            }
                        }
                        val preparers: List<Job> = List(2) {
                            launch(Dispatchers.Default) { together { recorder.prepare("/data/two-$round.m4a").isSuccess } }
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { preparers.joinAll() }
                        // Back to back is legal (Ready may be prepared again); at the same time is not.
                        assertTrue(results.load().any { it }, "round $round: no prepare succeeded: ${results.load()}")
                        assertEquals(results.load().count { it }, engine.prepares.load(), "round $round: engine.prepare ran ${engine.prepares.load()} times for ${results.load()}")
                        assertEquals(0, engine.prepareOverlaps.load(), "round $round: two engine.prepare calls overlapped")

                        ready.store(0)
                        go.store(false)
                        results.store(emptyList())
                        val starters: List<Job> = List(2) {
                            launch(Dispatchers.Default) { together { recorder.start().isSuccess } }
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { starters.joinAll() }
                        assertEquals(1, results.load().count { it }, "round $round: starts that succeeded: ${results.load()}")
                        assertEquals(1, engine.starts.load(), "round $round: engine.start ran ${engine.starts.load()} times")
                        assertEquals(0, engine.overlaps.load(), "round $round: engine driven from two threads")
                        recorder.release()
                    }
                }
            }
        }

    @Test
    fun `a release during preparation and the cancellation of its caller in either order leave one clean teardown`() =
        runTest(timeout = 30.seconds) {
            for (order in listOf("release then cancel", "cancel then release", "release then finish")) {
                val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
                fixture.engine.prepareGate = kotlinx.coroutines.CompletableDeferred()
                var result: RecorderResult<String>? = null
                val preparing: Job = launch { result = fixture.recorder.prepare("/data/app/rotating.m4a") }
                runCurrent()
                assertIs<RecorderState.Preparing>(fixture.recorder.state.value, order)
                when (order) {
                    "release then cancel" -> {
                        fixture.recorder.release()
                        preparing.cancel()
                    }

                    "cancel then release" -> {
                        preparing.cancel()
                        fixture.recorder.release()
                    }

                    else -> {
                        fixture.recorder.release()
                        fixture.engine.prepareGate?.complete(Unit)
                    }
                }
                runCurrent()
                preparing.join()
                runCurrent()

                assertEquals(RecorderState.Released, fixture.recorder.state.value, "$order: Released is terminal")
                assertTrue(fixture.engine.releaseCount >= 1, "$order: the microphone was left open")
                assertEquals(0, fixture.engine.releasesDuringPrepare, "$order: the engine was released while its prepare was running")
                assertTrue(fixture.fileSystem.deletedPaths.size <= 1, "$order: deleted ${fixture.fileSystem.deletedPaths}")
                assertEquals(0, fixture.scope.coroutineContext[Job]!!.children.count(), "$order: jobs left")
                if (order == "release then finish") {
                    assertEquals(
                        RecorderError.AlreadyReleased(RecorderOperation.PREPARE),
                        result?.errorOrNull(),
                        "$order: what the caller was told",
                    )
                }
            }
        }

    // --- Two releases at once, and a background pause against a screen's release ---

    @Test
    fun `two releases at once finalize a recording exactly once`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ROUNDS) { round: Int ->
                        val engine = ChaosEngine()
                        val fs = ChaosFileSystem()
                        val recorder: DefaultAudioRecorder = recorder(engine, fs)
                        assertTrue(recorder.prepare("/data/double-$round.m4a").isSuccess)
                        assertTrue(recorder.start().isSuccess)
                        val before: Int = engine.releases.load()
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val releasers: List<Job> = List(2) {
                            launch(Dispatchers.Default) {
                                ready.incrementAndFetch()
                                while (!go.load()) yield()
                                recorder.release()
                            }
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { releasers.joinAll() }
                        assertEquals(RecorderState.Released, recorder.state.value, "round $round")
                        assertEquals(1, engine.stops.load(), "round $round: stopped ${engine.stops.load()} times")
                        assertEquals(before + 1, engine.releases.load(), "round $round: engine releases")
                        assertEquals(0, engine.overlaps.load(), "round $round: engine driven from two threads")
                        assertTrue(fs.deletions().isEmpty(), "round $round: the recording was deleted")
                    }
                }
            }
        }

    @Test
    fun `a background pause racing the screen's release never touches the engine after release returned`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ROUNDS) { round: Int ->
                        val seed: Int = PAUSE_RELEASE_SEED + round
                        val random = Random(seed)
                        val engine = ChaosEngine()
                        val recorder: DefaultAudioRecorder = recorder(engine, ChaosFileSystem())
                        assertTrue(recorder.prepare("/data/bg-$round.m4a").isSuccess)
                        assertTrue(recorder.start().isSuccess)
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val lifecycle: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(random.nextInt(0, 4)) { recorder.pause(); recorder.resume() }
                        }
                        val releasing: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(random.nextInt(0, 4)) { yield() }
                            recorder.release()
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { listOf(lifecycle, releasing).joinAll() }
                        // Anything the lifecycle thread asks after release() returned is refused.
                        assertFalse(recorder.pause().isSuccess, "seed $seed")
                        assertFalse(recorder.resume().isSuccess, "seed $seed")
                        assertEquals(RecorderState.Released, recorder.state.value, "seed $seed")
                        assertEquals(0, engine.callsAfterRelease.load(), "seed $seed: engine driven after release")
                        assertEquals(0, engine.overlaps.load(), "seed $seed: engine driven from two threads")
                    }
                }
            }
        }

    // --- Virtual-time chaos ---

    private fun TestScope.passTime(fixture: RecorderFixture, duration: Duration) {
        fixture.timeSource += duration
        advanceTimeBy(duration)
        runCurrent()
    }

    @Test
    fun `three hundred background and foreground cycles add up to exactly the time spent recording`() =
        runRecorderTest { fixture ->
            fixture.recording()
            val watcher: Job = backgroundScope.launch { fixture.recorder.elapsed.collect { } }
            repeat(300) { cycle: Int ->
                passTime(fixture, 700.milliseconds)
                assertTrue(fixture.recorder.pause().isSuccess, "cycle $cycle")
                assertEquals((cycle + 1) * 700L, fixture.recorder.elapsed.value.inWholeMilliseconds, "cycle $cycle: elapsed after pause")
                // The app sits in the background; nothing is captured and nothing counts.
                passTime(fixture, 5.seconds)
                assertEquals((cycle + 1) * 700L, fixture.recorder.elapsed.value.inWholeMilliseconds, "cycle $cycle: elapsed drifted while paused")
                assertTrue(fixture.recorder.resume().isSuccess, "cycle $cycle")
            }
            assertTrue(fixture.scope.coroutineContext[Job]!!.children.count() <= 2, "jobs piled up")
            watcher.cancel()
        }

    @Test
    fun `a recorder reused fifty times ignores every event of an earlier session`() =
        runRecorderTest { fixture ->
            repeat(50) { cycle: Int ->
                fixture.recording("/data/app/reuse.m4a")
                val staleIndex: Int = fixture.engine.registeredListeners.lastIndex - 1
                if (staleIndex >= 0) {
                    fixture.engine.emitToStaleListener(EngineEvent.Interrupted(InterruptionReason.EngineDied(7)), staleIndex)
                    fixture.engine.emitToStaleListener(EngineEvent.InputSilenced(true), staleIndex)
                }
                runCurrent()
                advanceTimeBy(1.seconds)
                runCurrent()
                assertEquals(
                    RecorderState.Recording("/data/app/reuse.m4a"),
                    fixture.recorder.state.value,
                    "cycle $cycle: an earlier session's event ended this one",
                )
                assertTrue(fixture.recorder.stop().isSuccess, "cycle $cycle")
            }
        }

    @Test
    fun `a cancel whose caller is cancelled inside the engine still deletes the file and releases once`() =
        runRecorderTest { fixture ->
            val path: String = fixture.recording()
            val releasesBefore: Int = fixture.engine.releaseCount
            lateinit var cancelling: Job
            fixture.engine.onStop = { cancelling.cancel() }
            cancelling = launch { fixture.recorder.cancel() }
            runCurrent()
            cancelling.join()
            runCurrent()

            assertEquals(RecorderState.Idle, fixture.recorder.state.value, "the recorder is stuck after a cancelled cancel")
            assertEquals(listOf(path), fixture.fileSystem.deletedPaths, "the cancelled recording must be deleted once")
            assertEquals(releasesBefore + 1, fixture.engine.releaseCount, "engine releases")
            fixture.engine.onStop = {}
            assertTrue(fixture.recorder.prepare().isSuccess, "a new recording after the cancelled cancel")
        }

    @Test
    fun `a collector that releases the recorder when it sees the end of a recording frees the engine once`() =
        runTest(timeout = 30.seconds) {
            for (ending in listOf("stop", "interruption")) {
                val fixture = RecorderFixture(AudioRecorderConfig(), StandardTestDispatcher(testScheduler))
                val seen: MutableList<RecorderState> = mutableListOf()
                val collector: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.recorder.state.collect { state: RecorderState ->
                        seen += state
                        if (state is RecorderState.Completed || state is RecorderState.Interrupted) fixture.recorder.release()
                    }
                }
                fixture.recording()
                val before: Int = fixture.engine.releaseCount
                if (ending == "stop") {
                    fixture.recorder.stop()
                } else {
                    fixture.engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied(1)))
                }
                runCurrent()
                advanceTimeBy(1.seconds)
                runCurrent()

                assertEquals(RecorderState.Released, fixture.recorder.state.value, "$ending: states seen $seen")
                assertEquals(before + 1, fixture.engine.releaseCount, "$ending: engine releases; states seen $seen")
                assertEquals(0, fixture.scope.coroutineContext[Job]!!.children.count(), "$ending: jobs left")
                collector.cancel()
            }
        }

    private companion object {
        const val ROUNDS_SLOW: Int = 100
        const val RACE_ROUNDS: Int = 1000
        const val CANCEL_STOP_SEED: Int = 0xC57
        const val EVENT_STOP_SEED: Int = 0xE57
        const val PAUSE_RELEASE_SEED: Int = 0x9E5
        const val EVENT_RELEASE_SEED: Int = 0xE12
    }
}
