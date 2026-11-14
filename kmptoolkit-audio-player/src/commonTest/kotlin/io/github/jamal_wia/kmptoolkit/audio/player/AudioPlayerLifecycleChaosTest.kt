package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
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
 * Lifecycle chaos round 2: a source replaced while the one before it is still unwinding, a consumer
 * `Job` in the player's context, a prepare racing a release, a caller cancelled at the instant the
 * load finishes, pollers piling up over foreground/background churn, transport calls arriving
 * while a load is still running, and a leaked screen reference calling a released player.
 *
 * Every wait on real threads is bounded in real time; the jitter is seeded and the seed is in each
 * failure message.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class AudioPlayerLifecycleChaosTest {

    private val first: AudioSource = AudioSource.Remote("https://example.test/first.mp3")
    private val second: AudioSource = AudioSource.Remote("https://example.test/second.mp3")
    private val third: AudioSource = AudioSource.Remote("https://example.test/third.mp3")

    /** Records the order of engine calls that matter for "who frees the engine when". */
    private class LoggingEngine(private val delegate: RecordingPlaybackEngine) : PlaybackEngine by delegate {
        val inner: RecordingPlaybackEngine get() = delegate
        val log: MutableList<String> = mutableListOf()
        var positionReads: Int = 0
        val listener: PlaybackEngineListener? get() = delegate.listener

        override suspend fun load(source: AudioSource) {
            // path-hygiene-ignore: PH3 the value is a URL, not a file path
            log += "load ${(source as AudioSource.Remote).url.substringAfterLast('/')}"
            try {
                delegate.load(source)
            } finally {
                // path-hygiene-ignore: PH3 the value is a URL, not a file path
                log += "unwound ${source.url.substringAfterLast('/')}"
            }
        }

        override fun positionMs(): Long {
            positionReads++
            return delegate.positionMs()
        }

        override fun release() {
            log += "release"
            delegate.release()
        }
    }

    // --- A source replaced while the one before it unwinds ---

    @Test
    fun `an unload during an unwinding load frees the engine before the next load starts`() =
        runTest(timeout = 30.seconds) {
            val inner = RecordingPlaybackEngine().apply {
                loadDelayMs = 1_000L
                unwindDelayMs = 500L
            }
            val engine = LoggingEngine(inner)
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))

            val preparingFirst: Job = launch { player.prepare(first) }
            runCurrent()
            assertEquals(1, inner.activeLoads, "the first load must be running")
            player.unload()
            val preparingSecond: Job = launch { player.prepare(second) }
            runCurrent()

            advanceTimeBy(400L)
            runCurrent()
            assertEquals(
                listOf("load first.mp3"),
                engine.log,
                "the second load, or the deferred free, ran while the first was still unwinding",
            )

            advanceTimeBy(200L)
            runCurrent()
            val release: Int = engine.log.indexOf("release")
            val secondLoad: Int = engine.log.indexOf("load second.mp3")
            assertTrue(release in 0 until secondLoad, "the engine must be freed before the second load: ${engine.log}")

            advanceTimeBy(1_000L)
            runCurrent()
            preparingFirst.join()
            preparingSecond.join()
            assertEquals(
                secondLoad,
                engine.log.lastIndexOf("load second.mp3"),
                "a release landed after the second load started: ${engine.log}",
            )
            assertTrue(
                engine.log.drop(secondLoad).none { it == "release" },
                "a release landed after the second load started: ${engine.log}",
            )
            assertEquals(0, inner.releasesDuringLoad)
            assertEquals(1, inner.maxConcurrentLoads)
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            player.release()
        }

    @Test
    fun `three prepares in a row load the first and the last one on an engine that is never shared`() =
        runTest(timeout = 30.seconds) {
            val inner = RecordingPlaybackEngine().apply {
                loadDelayMs = 1_000L
                unwindDelayMs = 300L
            }
            val engine = LoggingEngine(inner)
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))

            val preparing: List<Job> = listOf(first, second, third).map { source: AudioSource ->
                launch { player.prepare(source) }.also { runCurrent() }
            }
            advanceTimeBy(300L)
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()
            preparing.joinAll()

            assertEquals(1, inner.maxConcurrentLoads, "loads overlapped: ${engine.log}")
            assertEquals(0, inner.releasesDuringLoad, "released under a load: ${engine.log}")
            assertEquals(third, inner.loadedSources.last(), "the last prepare must win: ${engine.log}")
            assertIs<PlayerState.Ready>(player.stateFlow.value, "log ${engine.log}")
            assertEquals(0, inner.activeLoads)
            player.release()
        }

    // --- A consumer's Job ---

    @Test
    fun `releasing never cancels the consumers job and cancelling it never wedges the player`() =
        runTest(timeout = 30.seconds) {
            val consumer: Job = Job()
            val engine = RecordingPlaybackEngine()
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler) + consumer,
            )
            player.prepare(first)
            player.play()
            advanceTimeBy(250L)
            runCurrent()

            consumer.cancel()
            engine.position = 1_234L
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(
                1_234L,
                (player.stateFlow.value as? PlayerState.Playing)?.currentPosition,
                "polling stopped when the consumer's job was cancelled",
            )
            player.pause()
            assertIs<PlayerState.Paused>(player.stateFlow.value)
            player.play()
            assertIs<PlayerState.Playing>(player.stateFlow.value)
            player.prepare(second)
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            player.release()

            val survivor: Job = Job()
            val other: AudioPlayer = createAudioPlayer(
                RecordingPlaybackEngine(),
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler) + survivor,
            )
            other.prepare(first)
            other.play()
            other.release()
            runCurrent()
            assertTrue(survivor.isActive, "release() cancelled the consumer's own job")
            survivor.cancel()
        }

    // --- Real threads: prepare against release, cancel against completion ---

    /** A thread-safe engine whose loads take a few real milliseconds and unwind non-cancellably. */
    private class ChaosEngine(private val loadMillis: Int) : PlaybackEngine {
        private val listener: AtomicReference<PlaybackEngineListener?> = AtomicReference(null)
        val activeLoads: AtomicInt = AtomicInt(0)
        val releases: AtomicInt = AtomicInt(0)
        val releasesDuringLoad: AtomicInt = AtomicInt(0)
        val completedLoads: AtomicInt = AtomicInt(0)

        fun currentListener(): PlaybackEngineListener? = listener.load()

        override fun setListener(listener: PlaybackEngineListener?) = this.listener.store(listener)

        override suspend fun load(source: AudioSource) {
            activeLoads.incrementAndFetch()
            try {
                delay(loadMillis.milliseconds)
                completedLoads.incrementAndFetch()
            } finally {
                withContext(NonCancellable) { yield() }
                activeLoads.decrementAndFetch()
            }
        }

        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 0L

        override fun release() {
            if (activeLoads.load() > 0) releasesDuringLoad.incrementAndFetch()
            releases.incrementAndFetch()
        }
    }

    @Test
    fun `a prepare racing a release ends idle or released and never ready`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(90.seconds) {
                    repeat(RACE_ITERATIONS) { iteration: Int ->
                        val seed: Int = RACE_SEED + iteration
                        val random = Random(seed)
                        val engine = ChaosEngine(loadMillis = random.nextInt(0, 4))
                        val player: AudioPlayer = createAudioPlayer(
                            engine,
                            AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                            Dispatchers.Default,
                        )
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val jitter: Int = random.nextInt(0, 3)
                        val preparing: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            player.prepare(first)
                        }
                        val releasing: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(jitter) { yield() }
                            if (iteration % 5 == 0) delay(1L)
                            player.release()
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        try {
                            withTimeout(10.seconds) { listOf(preparing, releasing).joinAll() }
                        } catch (timeout: CancellationException) {
                            preparing.cancel()
                            releasing.cancel()
                            fail("seed $seed: hung with state ${player.stateFlow.value}, loads ${engine.activeLoads.load()}")
                        }
                        player.seekTo(0L) // takes the lock, so everything submitted has run
                        val state: PlayerState = player.stateFlow.value
                        assertTrue(
                            state is PlayerState.Idle ||
                                (state is PlayerState.Error && state.cause is AudioPlayerReleasedException),
                            "seed $seed: state after prepare raced release: $state",
                        )
                        val deadline = TimeSource.Monotonic.markNow() + 5.seconds
                        while (engine.activeLoads.load() > 0 || engine.releases.load() == 0) {
                            if (deadline.hasPassedNow()) {
                                fail("seed $seed: engine not freed: loads=${engine.activeLoads.load()} releases=${engine.releases.load()}")
                            }
                            delay(1L)
                        }
                        assertEquals(0, engine.releasesDuringLoad.load(), "seed $seed: released under a load")
                        assertNull(engine.currentListener(), "seed $seed: listener still attached")
                    }
                }
            }
        }

    @Test
    fun `a caller cancelled around the moment the load finishes leaves idle or ready and a usable player`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(90.seconds) {
                    repeat(RACE_ITERATIONS) { iteration: Int ->
                        val seed: Int = CANCEL_SEED + iteration
                        val random = Random(seed)
                        val loadMillis: Int = random.nextInt(1, 4)
                        val engine = ChaosEngine(loadMillis)
                        val player: AudioPlayer = createAudioPlayer(
                            engine,
                            AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                            Dispatchers.Default,
                        )
                        val bodyStarted = AtomicBoolean(false)
                        val preparing: Job = launch(Dispatchers.Default) {
                            bodyStarted.store(true)
                            player.prepare(first)
                        }
                        // Anywhere from before the load starts to after it ended.
                        val wait: Int = random.nextInt(0, 2 * loadMillis + 2)
                        if (wait > 0) delay(wait.milliseconds)
                        preparing.cancel()
                        try {
                            withTimeout(10.seconds) { preparing.join() }
                        } catch (timeout: CancellationException) {
                            fail("seed $seed: the cancelled prepare never finished, state ${player.stateFlow.value}")
                        }
                        player.seekTo(0L)
                        val state: PlayerState = player.stateFlow.value
                        assertTrue(
                            state is PlayerState.Idle || state is PlayerState.Ready,
                            "seed $seed: state after a cancelled prepare: $state",
                        )
                        if (state is PlayerState.Ready) {
                            assertEquals(0, engine.releases.load(), "seed $seed: Ready on an engine that was released")
                        } else if (bodyStarted.load()) {
                            assertTrue(engine.releases.load() >= 1, "seed $seed: Idle but the engine was never freed")
                        }
                        assertEquals(0, engine.activeLoads.load(), "seed $seed: a load is still running")

                        withTimeout(5.seconds) { player.prepare(second) }
                        assertIs<PlayerState.Ready>(player.stateFlow.value, "seed $seed: not usable afterwards")
                        player.release()
                    }
                }
            }
        }

    @Test
    fun `a poll tick queued behind an unload or a stop never writes a stale position afterwards`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ITERATIONS) { iteration: Int ->
                        val seed: Int = TICK_SEED + iteration
                        val random = Random(seed)
                        val engine = ChaosEngine(loadMillis = 0)
                        val player: AudioPlayer = createAudioPlayer(
                            engine,
                            AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                            Dispatchers.Default,
                        )
                        player.prepare(first)
                        player.play()
                        delay(random.nextInt(0, 4).milliseconds)
                        val unload: Boolean = iteration % 2 == 0
                        val ending: Job = launch(Dispatchers.Default) {
                            repeat(random.nextInt(0, 3)) { yield() }
                            if (unload) player.unload() else player.stop()
                        }
                        withTimeout(10.seconds) { ending.join() }
                        player.seekTo(0L) // drains the lock; inert unless playable
                        val expected: PlayerState = if (unload) PlayerState.Idle else PlayerState.Ready(10_000L)
                        assertEquals(expected, player.stateFlow.value, "seed $seed: state right after")
                        delay(5.milliseconds)
                        assertEquals(expected, player.stateFlow.value, "seed $seed: a stale tick rewrote the state")
                        assertEquals(0L, player.playbackPositionFlow.value, "seed $seed: a stale tick rewrote the position")
                        player.release()
                    }
                }
            }
        }

    @Test
    fun `a seek racing the end of the source leaves it completed or paused and never playing`() =
        runTest(timeout = 120.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(100.seconds) {
                    repeat(RACE_ITERATIONS) { iteration: Int ->
                        val seed: Int = SEEK_SEED + iteration
                        val random = Random(seed)
                        val engine = ChaosEngine(loadMillis = 0)
                        val player: AudioPlayer = createAudioPlayer(
                            engine,
                            AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                            Dispatchers.Default,
                        )
                        player.prepare(first)
                        player.play()
                        val ready = AtomicInt(0)
                        val go = AtomicBoolean(false)
                        val jitters: List<Int> = List(2) { random.nextInt(0, 3) }
                        val completing: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(jitters[0]) { yield() }
                            requireNotNull(engine.currentListener()).onCompleted()
                        }
                        val seeking: Job = launch(Dispatchers.Default) {
                            ready.incrementAndFetch()
                            while (!go.load()) yield()
                            repeat(jitters[1]) { yield() }
                            player.seekTo(2_000L)
                        }
                        while (ready.load() < 2) yield()
                        go.store(true)
                        withTimeout(10.seconds) { listOf(completing, seeking).joinAll() }
                        player.seekBackward(0L) // drains the lock
                        val state: PlayerState = player.stateFlow.value
                        assertTrue(
                            state is PlayerState.Completed || state is PlayerState.Paused,
                            "seed $seed: a seek racing the end left $state",
                        )
                        player.release()
                    }
                }
            }
        }

    // --- Foreground and background churn ---

    @Test
    fun `play pause replay and stop churn never leaves more than one poller running`() =
        runTest(timeout = 60.seconds) {
            val engine = LoggingEngine(RecordingPlaybackEngine(duration = 1_000_000L))
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler),
            )
            player.prepare(first)
            repeat(CYCLES * 4) { cycle: Int ->
                when (cycle % 4) {
                    0 -> player.play()
                    1 -> player.pause()
                    2 -> player.replay()
                    else -> player.stop()
                }
                if (cycle % 8 == 0) advanceTimeBy(30L)
            }
            player.play()
            runCurrent()
            val before: Int = engine.positionReads
            advanceTimeBy(1_000L)
            runCurrent()
            val ticks: Int = engine.positionReads - before
            assertTrue(ticks in 9..11, "ten polls expected over a second at 100 ms, saw $ticks: pollers piled up")
            player.release()
        }

    @Test
    fun `a play after completion and a speed change do not start a second poller`() =
        runTest(timeout = 60.seconds) {
            val engine = LoggingEngine(RecordingPlaybackEngine())
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler),
            )
            player.prepare(first)
            repeat(CYCLES) {
                player.play()
                requireNotNull(engine.listener).onCompleted()
                runCurrent()
                assertIs<PlayerState.Completed>(player.stateFlow.value)
                player.setPlaybackSpeed(1.5f)
                player.play()
                player.setPlaybackSpeed(1.0f)
                player.seekTo(10L)
            }
            runCurrent()
            val before: Int = engine.positionReads
            advanceTimeBy(1_000L)
            runCurrent()
            val ticks: Int = engine.positionReads - before
            assertTrue(ticks in 9..11, "ten polls expected over a second, saw $ticks")
            player.release()
        }

    @Test
    fun `transport calls made while a load is still running are ignored and the chosen speed survives`() =
        runTest(timeout = 30.seconds) {
            val engine = RecordingPlaybackEngine().apply { loadDelayMs = 1_000L }
            val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
            val preparing: Job = launch { player.prepare(first) }
            runCurrent()
            assertEquals(PlayerState.Preparing, player.stateFlow.value)

            // A screen coming back to the foreground while the source is still loading.
            player.pause()
            player.play()
            player.replay()
            player.stop()
            player.seekTo(500L)
            player.seekForward(100L)
            player.seekBackward(100L)
            player.setPlaybackSpeed(2.0f)
            assertEquals(PlayerState.Preparing, player.stateFlow.value)
            assertEquals(0, engine.started, "play() reached an engine that was still loading")
            assertTrue(engine.seekTargets.isEmpty(), "a seek reached an engine that was still loading: ${engine.seekTargets}")

            advanceTimeBy(1_000L)
            runCurrent()
            preparing.join()
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            assertEquals(2.0f, engine.appliedSpeed, "the speed chosen during loading was lost")
            player.release()
        }

    // --- Re-entrancy: reacting to a state or position from inside the collector ---

    @Test
    fun `a playlist that prepares the next track from a Completed collector plays twenty tracks in order`() =
        runTest(timeout = 60.seconds) {
            val engine = LoggingEngine(RecordingPlaybackEngine().apply { loadDelayMs = 100L })
            val inner: RecordingPlaybackEngine = engine.inner
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 50L),
                StandardTestDispatcher(testScheduler),
            )
            val tracks: List<AudioSource> = List(20) { AudioSource.Remote("https://example.test/track-$it.mp3") }
            var next = 1
            val collector: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.stateFlow.collect { state: PlayerState ->
                    if (state is PlayerState.Completed && next < tracks.size) {
                        val track: AudioSource = tracks[next++]
                        launch {
                            player.prepare(track)
                            player.play()
                        }
                    }
                }
            }
            player.prepare(tracks[0])
            player.play()
            repeat(tracks.size - 1) { index: Int ->
                advanceTimeBy(300L)
                runCurrent()
                assertIs<PlayerState.Playing>(player.stateFlow.value, "track $index: state before the end, log ${engine.log}")
                requireNotNull(inner.listener).onCompleted()
                advanceTimeBy(150L)
                runCurrent()
            }
            advanceTimeBy(300L)
            runCurrent()

            assertEquals(tracks, inner.loadedSources, "tracks loaded out of order or twice")
            assertIs<PlayerState.Playing>(player.stateFlow.value)
            assertEquals(1, inner.maxConcurrentLoads, "loads overlapped")
            assertEquals(0, inner.releasesDuringLoad, "released under a load")
            collector.cancel()
            player.release()
        }

    @Test
    fun `a position collector that releases the player from inside a poll tick leaves it idle and silent`() =
        runTest(timeout = 30.seconds) {
            val engine = LoggingEngine(RecordingPlaybackEngine(duration = 1_000_000L))
            val inner: RecordingPlaybackEngine = engine.inner
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler),
            )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                player.playbackPositionFlow.collect { position: Long -> if (position >= 500L) player.release() }
            }
            player.prepare(first)
            player.play()
            inner.position = 600L
            advanceTimeBy(100L)
            runCurrent()

            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(1, inner.releaseCount, "engine releases")
            val reads: Int = engine.positionReads
            advanceTimeBy(5.seconds)
            runCurrent()
            assertEquals(reads, engine.positionReads, "polling went on after the collector released the player")
            assertEquals(PlayerState.Idle, player.stateFlow.value)
        }

    @Test
    fun `an engine that reports a cancelled load as an ordinary failure never fails the player after an unload or a newer prepare`() =
        runTest(timeout = 30.seconds) {
            val inner = RecordingPlaybackEngine().apply {
                loadDelayMs = 1_000L
                failureWhenCancelled = IllegalStateException("released while loading")
            }
            val player: AudioPlayer = createAudioPlayer(inner, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))

            val unloaded: Job = launch { player.prepare(first) }
            runCurrent()
            player.unload()
            runCurrent()
            unloaded.join()
            assertEquals(PlayerState.Idle, player.stateFlow.value, "unload of a loading source")

            val superseded: Job = launch { player.prepare(first) }
            runCurrent()
            val winner: Job = launch { player.prepare(second) }
            runCurrent()
            advanceTimeBy(1_100L)
            runCurrent()
            superseded.join()
            winner.join()
            assertIs<PlayerState.Ready>(player.stateFlow.value, "the superseded load's failure overwrote the winner")

            val cancelled: Job = launch { player.prepare(third) }
            runCurrent()
            cancelled.cancel()
            cancelled.join()
            assertEquals(PlayerState.Idle, player.stateFlow.value, "a cancelled caller is not a failure")
            assertEquals(0, inner.releasesDuringLoad)
            player.release()
        }

    // --- A leaked screen reference calling a released player ---

    @Test
    fun `a released player ignores every call a leaked screen makes afterwards`() =
        runTest(timeout = 30.seconds) {
            val engine = RecordingPlaybackEngine()
            val player: AudioPlayer = createAudioPlayer(
                engine,
                AudioPlayerConfig(positionUpdateIntervalMs = 100L),
                StandardTestDispatcher(testScheduler),
            )
            player.prepare(first)
            player.play()
            player.release()
            val started: Int = engine.started
            val paused: Int = engine.paused
            val releases: Int = engine.releaseCount

            repeat(CYCLES) {
                player.play()
                player.pause()
                player.stop()
                player.replay()
                player.seekTo(5L)
                player.seekForward(5L)
                player.seekBackward(5L)
                player.setPlaybackSpeed(2.0f)
                player.unload()
                player.release()
                player.prepare(second)
                advanceTimeBy(500L)
            }
            runCurrent()

            assertEquals(started, engine.started, "play() reached the engine after release")
            assertEquals(paused, engine.paused, "pause() reached the engine after release")
            assertEquals(releases, engine.releaseCount, "the engine was released again")
            assertEquals(listOf(first), engine.loadedSources, "a released player loaded another source")
            assertTrue(engine.seekTargets.isEmpty(), "a seek reached the engine after release: ${engine.seekTargets}")
            val state: PlayerState = player.stateFlow.value
            assertTrue(
                state is PlayerState.Idle || (state is PlayerState.Error && state.cause is AudioPlayerReleasedException),
                "state after a leaked prepare: $state",
            )
        }

    private companion object {
        const val CYCLES: Int = 50
        const val RACE_ITERATIONS: Int = 300
        const val RACE_SEED: Int = 0x5EED
        const val CANCEL_SEED: Int = 0xCA17
        const val TICK_SEED: Int = 0x71C
        const val SEEK_SEED: Int = 0x5EE
    }
}
