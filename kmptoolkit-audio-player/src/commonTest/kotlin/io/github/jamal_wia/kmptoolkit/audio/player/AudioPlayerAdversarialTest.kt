package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Hazards a screen-scoped or lifecycle-driven player meets: it is built and released again and again
 * (rotation), a load is cancelled and replaced while an unload races it from another thread,
 * a collector reacts to the end of playback by releasing the very player that reported it,
 * foreground and background switches pause and resume in quick succession, and the engine reports
 * from a thread of its own after the player let go of the source.
 *
 * Every wait on real threads is bounded in real time.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class AudioPlayerAdversarialTest {

    private val source: AudioSource = AudioSource.Remote("https://example.test/clip.mp3")
    private val other: AudioSource = AudioSource.Remote("https://example.test/other.mp3")

    // --- Rotation: a new player per screen instance ---

    /** Counts reads of the playhead, so a poll that outlives its player is visible. */
    private class CountingEngine(private val delegate: RecordingPlaybackEngine) : PlaybackEngine by delegate {
        var positionReads: Int = 0
        override fun positionMs(): Long {
            positionReads++
            return delegate.positionMs()
        }
    }

    @Test
    fun `a player recreated fifty times frees every engine once and polls no more after release`() =
        runTest(timeout = 60.seconds) {
            val engines: MutableList<RecordingPlaybackEngine> = mutableListOf()
            val readsAtRelease: MutableList<Int> = mutableListOf()
            val counting: MutableList<CountingEngine> = mutableListOf()

            repeat(CYCLES) { cycle: Int ->
                val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
                val wrapped = CountingEngine(engine)
                engines += engine
                counting += wrapped
                val player: AudioPlayer = createAudioPlayer(
                    engine = wrapped,
                    config = AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                    coroutineContext = StandardTestDispatcher(testScheduler),
                )
                val preparing: Job = launch { player.prepare(source) }
                runCurrent()
                if (cycle % 2 == 0) {
                    // Rotated while the load is still in flight.
                    assertEquals(1, engine.activeLoads, "cycle $cycle: the load must be running")
                } else {
                    // Rotated while playing.
                    advanceTimeBy(1_000L)
                    runCurrent()
                    assertIs<PlayerState.Ready>(player.stateFlow.value, "cycle $cycle")
                    player.play()
                    advanceTimeBy(250L)
                    runCurrent()
                }
                player.release()
                runCurrent()
                preparing.join()
                readsAtRelease += wrapped.positionReads

                assertEquals(1, engine.releaseCount, "cycle $cycle: engine releases")
                assertEquals(0, engine.activeLoads, "cycle $cycle: loads still running")
                assertEquals(0, engine.releasesDuringLoad, "cycle $cycle: released under a load")
                assertNull(engine.listener, "cycle $cycle: the listener is still attached")
                assertEquals(PlayerState.Idle, player.stateFlow.value, "cycle $cycle")
            }

            advanceTimeBy(10.seconds)
            runCurrent()

            engines.forEachIndexed { index: Int, engine: RecordingPlaybackEngine ->
                assertEquals(1, engine.releaseCount, "engine $index: releases after the long wait")
                assertEquals(0, engine.activeLoads, "engine $index: loads after the long wait")
                assertEquals(
                    readsAtRelease[index],
                    counting[index].positionReads,
                    "engine $index: position reads continued after release",
                )
            }
        }

    // --- Cancellation of a superseded load against unload, on real threads ---

    @Test
    fun `a cancelled prepare replaced by another while unload races it never overlaps loads`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    repeat(STORM_ITERATIONS) { iteration: Int ->
                        val engine = AtomicEngine(loadStep = iteration)
                        val player: AudioPlayer = createAudioPlayer(
                            engine = engine,
                            config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                            coroutineContext = Dispatchers.Default,
                        )
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        suspend fun startTogether() {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                        }

                        val first: Job = launch(Dispatchers.Default) {
                            startTogether()
                            player.prepare(source)
                        }
                        val second: Job = launch(Dispatchers.Default) {
                            startTogether()
                            if (iteration % 2 == 0) first.cancel()
                            player.prepare(other)
                        }
                        val third: Job = launch(Dispatchers.Default) {
                            startTogether()
                            if (iteration % 3 == 0) yield()
                            player.unload()
                        }
                        while (ready.load() < 3) yield()
                        go.store(true)
                        val all: List<Job> = listOf(first, second, third)
                        try {
                            withTimeout(10.seconds) { all.joinAll() }
                        } catch (timeout: CancellationException) {
                            all.forEach { it.cancel() }
                            fail("iteration $iteration: hung with state ${player.stateFlow.value}, ${engine.describe()}")
                        }

                        assertTrue(engine.maxConcurrentLoads.load() <= 1, "iteration $iteration: ${engine.describe()}")
                        assertEquals(0, engine.releasesDuringLoad.load(), "iteration $iteration: ${engine.describe()}")
                        assertEquals(0, engine.overlappingCalls.load(), "iteration $iteration: ${engine.describe()}")

                        // Not wedged: a fresh prepare completes and the player becomes Ready.
                        withTimeout(5.seconds) { player.prepare(source) }
                        assertIs<PlayerState.Ready>(player.stateFlow.value, "iteration $iteration")
                        assertEquals(0, engine.activeLoads.load(), "iteration $iteration: loads still running")
                        player.release()
                        delay(1L)
                        assertEquals(0, engine.releasesDuringLoad.load(), "iteration $iteration: after release")
                    }
                }
            }
        }

    // --- Re-entrancy: releasing from inside a state collector ---

    @Test
    fun `a collector that releases the player when it sees Completed frees the engine once`() =
        runTest(timeout = 30.seconds) {
            val engine = RecordingPlaybackEngine()
            val player: AudioPlayer = createAudioPlayer(
                engine = engine,
                config = AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                coroutineContext = StandardTestDispatcher(testScheduler),
            )
            val seen: MutableList<PlayerState> = mutableListOf()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.stateFlow.collect { state: PlayerState ->
                    seen += state
                    if (state is PlayerState.Completed) player.release()
                }
            }
            player.prepare(source)
            player.play()

            requireNotNull(engine.listener).onCompleted()
            runCurrent()

            assertEquals(1, engine.releaseCount, "engine releases; states seen: $seen")
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertNull(engine.listener)
            // And nothing keeps polling a player that released itself.
            engine.position = 5_000L
            advanceTimeBy(5.seconds)
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(1, engine.releaseCount)
        }

    @Test
    fun `a collector on another thread releasing on Completed never deadlocks or double frees`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                repeat(REENTRANT_ITERATIONS) { iteration: Int ->
                    val engine = AtomicEngine()
                    val player: AudioPlayer = createAudioPlayer(
                        engine = engine,
                        config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                        coroutineContext = Dispatchers.Default,
                    )
                    val collector: Job = launch(Dispatchers.Default) {
                        player.stateFlow.collect { state: PlayerState ->
                            if (state is PlayerState.Completed) player.release()
                        }
                    }
                    withTimeout(5.seconds) {
                        player.prepare(source)
                        player.play()
                        launch(Dispatchers.Default) { engine.reportCompleted() }.join()
                        while (engine.releases.load() == 0) delay(1L)
                    }
                    delay(5L)
                    collector.cancel()

                    assertEquals(1, engine.releases.load(), "iteration $iteration: engine releases")
                    assertEquals(0, engine.overlappingCalls.load(), "iteration $iteration: overlapping calls")
                    assertEquals(PlayerState.Idle, player.stateFlow.value, "iteration $iteration")
                }
            }
        }

    // --- Foreground and background churn ---

    @Test
    fun `pause and play hammered from two threads settle on the last call while polling keeps working`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    val engine = AtomicEngine(duration = 1_000_000L)
                    val player: AudioPlayer = createAudioPlayer(
                        engine = engine,
                        config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                        coroutineContext = Dispatchers.Default,
                    )
                    player.prepare(source)
                    player.play()

                    val workers: List<Job> = List(2) { worker: Int ->
                        launch(Dispatchers.Default) {
                            repeat(CHURN_CALLS_PER_THREAD) { step: Int ->
                                if ((step + worker) % 2 == 0) player.pause() else player.play()
                            }
                        }
                    }
                    try {
                        withTimeout(20.seconds) { workers.joinAll() }
                    } catch (timeout: CancellationException) {
                        workers.forEach { it.cancel() }
                        fail("hung with state ${player.stateFlow.value}, ${engine.describe()}")
                    }
                    assertEquals(0, engine.overlappingCalls.load(), "overlapping engine calls: ${engine.describe()}")
                    val storm: PlayerState = player.stateFlow.value
                    assertTrue(
                        storm is PlayerState.Playing || storm is PlayerState.Paused,
                        "state after the churn: $storm",
                    )
                    assertEquals(storm is PlayerState.Playing, engine.playing.load(), "state and engine disagree: $storm")

                    // The last call wins, and is honoured whichever way the churn ended.
                    player.pause()
                    assertIs<PlayerState.Paused>(player.stateFlow.value)
                    assertTrue(!engine.playing.load())
                    delay(20L)
                    val readsWhenPaused: Long = engine.positionReads.load()
                    delay(50L)
                    assertEquals(readsWhenPaused, engine.positionReads.load(), "a poll survived the pause")

                    player.play()
                    assertIs<PlayerState.Playing>(player.stateFlow.value)
                    assertTrue(engine.playing.load())
                    val before: Long = (player.stateFlow.value as PlayerState.Playing).currentPosition
                    val deadline = TimeSource.Monotonic.markNow() + 5.seconds
                    while ((player.stateFlow.value as? PlayerState.Playing)?.currentPosition == before) {
                        if (deadline.hasPassedNow()) fail("the poll stopped after the churn: ${player.stateFlow.value}")
                        delay(1L)
                    }
                    player.release()
                    assertEquals(PlayerState.Idle, player.stateFlow.value)
                }
            }
        }

    // --- Late engine callbacks ---

    @Test
    fun `engine callbacks from another thread after release or unload are dropped`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                repeat(LATE_ITERATIONS) { iteration: Int ->
                    val engine = AtomicEngine()
                    val player: AudioPlayer = createAudioPlayer(
                        engine = engine,
                        config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                        coroutineContext = Dispatchers.Default,
                    )
                    player.prepare(source)
                    player.play()
                    // The platform's listener reference as it stood before the player let go of it.
                    val stale: PlaybackEngineListener = requireNotNull(engine.currentListener())

                    player.unload()
                    launch(Dispatchers.Default) {
                        stale.onCompleted()
                        stale.onFailed(IllegalStateException("late $iteration"))
                    }.join()
                    assertEquals(PlayerState.Idle, player.stateFlow.value, "iteration $iteration: after unload")
                    assertEquals(0L, player.playbackPositionFlow.value, "iteration $iteration: after unload")

                    // The next source is not touched by what the previous one left in flight.
                    withTimeout(5.seconds) { player.prepare(other) }
                    assertIs<PlayerState.Ready>(player.stateFlow.value, "iteration $iteration: next source")

                    player.release()
                    launch(Dispatchers.Default) {
                        stale.onCompleted()
                        stale.onFailed(IllegalStateException("later $iteration"))
                        engine.currentListener()?.onCompleted()
                    }.join()
                    delay(1L)
                    assertEquals(PlayerState.Idle, player.stateFlow.value, "iteration $iteration: after release")
                    assertEquals(0, engine.overlappingCalls.load(), "iteration $iteration: overlapping calls")
                }
            }
        }

    /**
     * A thread-safe engine. Loads take a few real milliseconds and unwind through a non-cancellable
     * step like a native handle freeing itself; it counts loads in flight, releases under a load, and
     * calls that overlapped another synchronous call.
     */
    private class AtomicEngine(
        private val duration: Long = 10_000L,
        private val loadStep: Int = 0,
    ) : PlaybackEngine {
        private val listener: AtomicReference<PlaybackEngineListener?> = AtomicReference(null)
        private val position: AtomicLong = AtomicLong(0L)
        private val inCall: AtomicInt = AtomicInt(0)

        val activeLoads: AtomicInt = AtomicInt(0)
        val maxConcurrentLoads: AtomicInt = AtomicInt(0)
        val releasesDuringLoad: AtomicInt = AtomicInt(0)
        val releases: AtomicInt = AtomicInt(0)
        val overlappingCalls: AtomicInt = AtomicInt(0)
        val positionReads: AtomicLong = AtomicLong(0L)
        val playing: AtomicBoolean = AtomicBoolean(false)

        fun currentListener(): PlaybackEngineListener? = listener.load()

        fun reportCompleted() {
            listener.load()?.onCompleted()
        }

        fun describe(): String =
            "loads=${activeLoads.load()} maxLoads=${maxConcurrentLoads.load()} " +
                "releases=${releases.load()} releasesDuringLoad=${releasesDuringLoad.load()}"

        override fun setListener(listener: PlaybackEngineListener?) {
            this.listener.store(listener)
        }

        override suspend fun load(source: AudioSource) {
            val now: Int = activeLoads.incrementAndFetch()
            while (true) {
                val max: Int = maxConcurrentLoads.load()
                if (now <= max || maxConcurrentLoads.compareAndSet(max, now)) break
            }
            try {
                delay((1 + loadStep % 3).milliseconds)
                position.store(0L)
            } finally {
                withContext(NonCancellable) { yield() }
                activeLoads.decrementAndFetch()
            }
        }

        override fun start() = call { playing.store(true) }

        override fun pause() = call { playing.store(false) }

        override fun seekTo(positionMs: Long) = call { position.store(positionMs) }

        override fun setSpeed(speed: Float) = call { }

        override fun durationMs(): Long = duration

        override fun positionMs(): Long {
            var result = 0L
            call {
                positionReads.incrementAndFetch()
                result = position.incrementAndFetch() % duration
            }
            return result
        }

        override fun release() = call {
            if (activeLoads.load() > 0) releasesDuringLoad.incrementAndFetch()
            releases.incrementAndFetch()
            playing.store(false)
        }

        private inline fun call(block: () -> Unit) {
            if (inCall.incrementAndFetch() > 1) overlappingCalls.incrementAndFetch()
            try {
                block()
            } finally {
                inCall.decrementAndFetch()
            }
        }
    }

    private companion object {
        const val CYCLES: Int = 50
        const val STORM_ITERATIONS: Int = 300
        const val REENTRANT_ITERATIONS: Int = 100
        const val CHURN_CALLS_PER_THREAD: Int = 500
        const val LATE_ITERATIONS: Int = 100
    }
}
