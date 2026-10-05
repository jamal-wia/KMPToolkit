package io.github.jamal_wia.kmptoolkit.audio.recorder

import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * The promise that a system event, arriving on a platform thread of its own, never corrupts the
 * recorder: against real threads (`Dispatchers.Default`) and an engine whose `stop()` is slow
 * enough to hold the race window open, an event races each of the caller's operations. Whatever
 * wins, the session ends with exactly one outcome, the platform recorder is finalized at most
 * once, and it is never driven from two threads at the same time.
 *
 * The same pattern as `VideoPlayerThreadingTest` in `kmptoolkit-video-player`.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecorderThreadingTest {

    /** An engine that counts its calls and notices two of them overlapping. */
    private class ThreadSafeEngine : RecorderEngine {
        val stops: AtomicInt = AtomicInt(0)
        val releases: AtomicInt = AtomicInt(0)
        val overlaps: AtomicInt = AtomicInt(0)
        private val driving: AtomicInt = AtomicInt(0)

        @Volatile
        private var listener: ((EngineEvent) -> Unit)? = null

        private inline fun <T> driven(block: () -> T): T {
            if (driving.incrementAndFetch() > 1) overlaps.incrementAndFetch()
            try {
                return block()
            } finally {
                driving.decrementAndFetch()
            }
        }

        fun emit(event: EngineEvent) {
            listener?.invoke(event)
        }

        override fun hasRecordAudioPermission(): Boolean = true
        override fun supportsFormat(format: AudioFormat): Boolean = true

        override suspend fun prepare(
            outputPath: String,
            config: AudioRecorderConfig,
            maxFileSizeBytes: Long?,
        ): Unit = driven { }

        override fun setEventListener(listener: ((EngineEvent) -> Unit)?) {
            this.listener = listener
        }

        override fun start(): Unit = driven { }
        override fun pause(): Unit = driven { }
        override fun resume(): Unit = driven { }
        override fun peakDbfs(): Float? = null

        override fun stop(): Unit = driven {
            stops.incrementAndFetch()
            // Long enough that the other side of the race lands while this thread is in here.
            val deadline: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 300.microseconds
            while (!deadline.hasPassedNow()) Unit
        }

        override fun release() = driven { releases.incrementAndFetch(); Unit }
    }

    private class ThreadSafeFileSystem : RecordingFileSystem {
        val deletes: AtomicInt = AtomicInt(0)
        override fun appPrivateDirectory(): String = "/data"
        override fun applicationIdentifier(): String = "app"
        override fun resolve(directory: String, name: String): String = "$directory/$name"
        override fun parentOf(path: String): String? = path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
        override fun ensureWritableDirectory(path: String): Boolean = true
        override fun freeSpaceBytes(path: String): Long = Long.MAX_VALUE
        override fun delete(path: String) {
            deletes.incrementAndFetch()
        }
    }

    private enum class Race { STOP, CANCEL, RELEASE, PAUSE_RESUME, PAUSE_STOP, NOTHING }

    private val reasons: List<InterruptionReason> = listOf(
        InterruptionReason.AudioSessionInterrupted,
        InterruptionReason.StorageLow,
        InterruptionReason.EngineDied(1),
    )

    @Test
    fun `an event racing every operation ends the session once and drives the engine from one thread`() =
        runTest {
            val iterations = 150
            withContext(Dispatchers.Default) {
                for (round: Int in 0 until iterations) {
                    for (race: Race in Race.entries) {
                        runOne(race, reasons[round % reasons.size], round)
                    }
                }
            }
        }

    private suspend fun runOne(race: Race, reason: InterruptionReason, round: Int) {
        val engine = ThreadSafeEngine()
        val recorder: AudioRecorder = DefaultAudioRecorder(
            engine = engine,
            fileSystem = ThreadSafeFileSystem(),
            config = AudioRecorderConfig(minimumFreeSpaceBytes = 0L),
            workerContext = Dispatchers.Default,
            epochClock = EpochClock { round.toLong() },
        )
        try {
            assertTrue(recorder.prepare("/data/a.m4a").isSuccess)
            assertTrue(recorder.start().isSuccess)

            val racers: MutableList<kotlinx.coroutines.Job> = mutableListOf()
            val scope = CoroutineScope(Dispatchers.Default)
            racers += scope.launch { engine.emit(EngineEvent.Interrupted(reason)) }
            racers += scope.launch {
                when (race) {
                    Race.STOP -> recorder.stop()
                    Race.CANCEL -> recorder.cancel()
                    Race.RELEASE -> recorder.release()
                    Race.PAUSE_RESUME -> {
                        recorder.pause()
                        recorder.resume()
                    }
                    Race.PAUSE_STOP -> {
                        recorder.pause()
                        recorder.stop()
                    }
                    Race.NOTHING -> Unit
                }
            }
            // A second event from a third thread: only the first thing that ends a session counts.
            racers += scope.launch { engine.emit(EngineEvent.Interrupted(InterruptionReason.EngineDied())) }
            racers.joinAll()

            val settled: RecorderState = awaitSettled(recorder, race)

            assertTrue(engine.stops.load() <= 1, "$race: the engine was finalized ${engine.stops.load()} times")
            assertEquals(0, engine.overlaps.load(), "$race: the engine was driven from two threads at once")
            assertTrue(
                settled is RecorderState.Interrupted ||
                    settled is RecorderState.Completed ||
                    settled is RecorderState.Failed ||
                    settled is RecorderState.Idle ||
                    settled is RecorderState.Released,
                "$race: ended in $settled",
            )
            // Whatever ended it, the platform recorder was freed — by the finalizer, never twice
            // concurrently, and not left holding the microphone.
            awaitUntil("$race: the engine to be released") { engine.releases.load() >= 1 }
        } finally {
            recorder.release()
        }
    }

    /** The session is over once the state is none of the active ones; an event guarantees it ends. */
    private suspend fun awaitSettled(recorder: AudioRecorder, race: Race): RecorderState {
        val deadline: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 10.seconds
        while (true) {
            val state: RecorderState = recorder.state.value
            if (state !is RecorderState.Recording && state !is RecorderState.Paused) return state
            if (deadline.hasPassedNow()) fail("$race: still $state — the event was lost")
            kotlinx.coroutines.yield()
        }
    }

    private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("Timed out waiting for $what")
            kotlinx.coroutines.yield()
        }
    }
}
