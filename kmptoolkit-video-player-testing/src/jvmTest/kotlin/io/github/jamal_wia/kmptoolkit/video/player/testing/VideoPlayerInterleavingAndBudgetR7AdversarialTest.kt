package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Round 7 (breaker hf), Q2 + Q3 of unit D1.
 *
 * Q3: every ordered sequence of public calls and engine events up to length 3 (all 13 actions) and
 * length 4 (a 9-action subset), applied to one player over the scriptable fake with held loads, on a
 * deterministic scheduler (a StandardTestDispatcher whose queue only the test runs). After every action
 * and at the end of every world: no public call threw, nothing reached the engine after its dispose,
 * a released player publishes nothing but Idle (and Error(VideoPlayerReleasedException) for a later
 * prepare), dispose happened exactly once after a release and never before, a state of the player agrees
 * with the engine's playing flag, and once nothing is pending the player is not stuck in Preparing.
 *
 * Q2: 200 prepare/release cycles, 1000 source swaps on one player, 50 players alive at once: every engine
 * ends released once and disposed once with no listener, the accounting of 06-testing (`releaseCount`) is
 * exact, a released player leaves no poll behind (the engine is never read again), release is idempotent.
 * Every wait is virtual (no sleeps); a seed is not needed because the enumeration has no random choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerInterleavingAndBudgetR7AdversarialTest {

    /** The fake plus a call log that notices what arrives after dispose and counts engine reads. */
    private class WatchedEngine(val fake: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(5_000L)) :
        VideoPlaybackEngine {
        var disposed: Boolean = false
        val lateCalls: MutableList<String> = mutableListOf()
        var reads: Int = 0
        var listenerCleared: Boolean = false

        private fun late(name: String) {
            if (disposed) lateCalls += name
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            late("setListener")
            if (listener == null) listenerCleared = true
            fake.setListener(listener)
        }

        override suspend fun load(source: VideoSource) {
            late("load")
            fake.load(source)
        }

        override fun start() {
            late("start")
            fake.start()
        }

        override fun pause() {
            late("pause")
            fake.pause()
        }

        override fun seekTo(positionMs: Long) {
            late("seekTo")
            fake.seekTo(positionMs)
        }

        override fun setSpeed(speed: Float) {
            late("setSpeed")
            fake.setSpeed(speed)
        }

        override fun setVolume(volume: Float) {
            late("setVolume")
            fake.setVolume(volume)
        }

        override fun setLooping(looping: Boolean) {
            late("setLooping")
            fake.setLooping(looping)
        }

        override fun durationMs(): Long {
            reads++
            late("durationMs")
            return fake.durationMs()
        }

        override fun positionMs(): Long {
            reads++
            late("positionMs")
            return fake.positionMs()
        }

        override fun bufferedPositionMs(): Long {
            reads++
            late("bufferedPositionMs")
            return fake.bufferedPositionMs()
        }

        override fun release() {
            late("release")
            fake.release()
        }

        override fun dispose() {
            late("dispose")
            disposed = true
            fake.dispose()
        }
    }

    /**
     * The test scheduler's dispatcher that counts every delay a coroutine asks it for. A poll that keeps
     * running after its player was released asks for another delay on every wake-up; a stopped one never does.
     */
    @OptIn(InternalCoroutinesApi::class)
    private class DelayCountingDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay {
        var delays: Int = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(context, block)

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            delays++
            (inner as Delay).scheduleResumeAfterDelay(timeMillis, continuation)
        }
    }

    private enum class Act {
        PrepareA, PrepareB, CancelLastPrepare, Release, Seek, Play, Pause, Stop, Unload, FinishLoad, FailLoad,
        FailPlayback, CompletePlayback,
    }

    private val sourceA: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val sourceB: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    private class World(scope: TestScope) {
        val engine: WatchedEngine = WatchedEngine().also { it.fake.suspendLoads = true }
        val poll: DelayCountingDispatcher = DelayCountingDispatcher(StandardTestDispatcher(scope.testScheduler))
        val caller = StandardTestDispatcher(scope.testScheduler)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), poll)
        val prepares: MutableList<Job> = mutableListOf()
        val seen: MutableList<VideoPlayerState> = mutableListOf()
        var releasedAtIndex: Int = -1
        var releasedCalled: Boolean = false
        var preparedAfterRelease: Boolean = false
        val trace: MutableList<Act> = mutableListOf()
        var collector: Job? = null
    }

    private fun TestScope.apply(world: World, act: Act) {
        world.trace += act
        try {
            when (act) {
                Act.PrepareA, Act.PrepareB -> {
                    if (world.releasedCalled) world.preparedAfterRelease = true
                    val source: VideoSource = if (act == Act.PrepareA) sourceA else sourceB
                    world.prepares += launch(world.caller, CoroutineStart.DEFAULT) { world.player.prepare(source) }
                }
                Act.CancelLastPrepare -> world.prepares.lastOrNull()?.cancel()
                Act.Release -> {
                    world.releasedCalled = true
                    world.releasedAtIndex = world.seen.size
                    world.player.release()
                }
                Act.Seek -> world.player.seekTo(1_000L)
                Act.Play -> world.player.play()
                Act.Pause -> world.player.pause()
                Act.Stop -> world.player.stop()
                Act.Unload -> world.player.unload()
                Act.FinishLoad -> world.engine.fake.finishLoad()
                Act.FailLoad -> world.engine.fake.failLoad(IOException("load failed"))
                Act.FailPlayback -> world.engine.fake.failPlayback(IOException("playback failed"))
                Act.CompletePlayback -> world.engine.fake.completePlayback()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail("a public call or engine event threw $t after ${world.trace}")
        }
        testScheduler.runCurrent()
        check(world)
    }

    private fun check(world: World) {
        val where = "after ${world.trace}"
        assertEquals(emptyList(), world.engine.lateCalls, "engine calls after dispose $where")
        val engine: FakeVideoPlaybackEngine = world.engine.fake
        if (!world.releasedCalled) {
            assertEquals(0, engine.disposeCount, "disposed before a release $where")
        } else {
            assertEquals(1, engine.disposeCount, "dispose count after a release $where")
            assertTrue(world.engine.listenerCleared, "listener not cleared after a release $where")
            val after: List<VideoPlayerState> = world.seen.drop(world.releasedAtIndex)
            for (state: VideoPlayerState in after) {
                val ok: Boolean = state == VideoPlayerState.Idle ||
                    (state is VideoPlayerState.Error && state.cause is VideoPlayerReleasedException)
                assertTrue(ok, "a released player published $state $where; all: ${world.seen}")
            }
            val final: VideoPlayerState = world.player.stateFlow.value
            if (world.preparedAfterRelease) {
                assertTrue(
                    final == VideoPlayerState.Idle ||
                        (final is VideoPlayerState.Error && final.cause is VideoPlayerReleasedException),
                    "state of a released player is $final $where",
                )
            } else {
                assertEquals(VideoPlayerState.Idle, final, "state of a released player $where")
            }
        }
        val state: VideoPlayerState = world.player.stateFlow.value
        if (state is VideoPlayerState.Playing) {
            assertTrue(engine.isPlaying, "state Playing but the engine is not playing $where")
        }
        if (state is VideoPlayerState.Paused || state is VideoPlayerState.Ready ||
            state is VideoPlayerState.Completed || state is VideoPlayerState.Idle ||
            state is VideoPlayerState.Error
        ) {
            assertFalse(engine.isPlaying, "state $state but the engine is playing $where")
        }
    }

    private fun TestScope.settle(world: World) {
        val where = "while settling ${world.trace}"
        // Drain: finish every held load and wait for every prepare to return.
        var guard = 0
        while (world.prepares.any { it.isActive } && guard++ < 20) {
            world.engine.fake.finishLoad()
            testScheduler.runCurrent()
        }
        assertFalse(world.prepares.any { it.isActive }, "a prepare call never returned $where")
        assertFalse(world.engine.fake.isLoadPending, "a load is still pending $where")
        val state: VideoPlayerState = world.player.stateFlow.value
        assertFalse(state is VideoPlayerState.Preparing, "stuck in Preparing $where")
        check(world)
        world.collector?.cancel()
        // Finally releasing is always possible and exact.
        if (!world.releasedCalled) {
            world.player.release()
            world.releasedCalled = true
            testScheduler.runCurrent()
        }
        assertEquals(1, world.engine.fake.disposeCount, "dispose count at the end $where")
        val readsBefore: Int = world.engine.reads
        val delaysBefore: Int = world.poll.delays
        testScheduler.advanceTimeBy(10_000L)
        testScheduler.runCurrent()
        assertEquals(delaysBefore, world.poll.delays, "a poll kept waking up after the release $where")
        assertEquals(readsBefore, world.engine.reads, "a poll kept reading the engine after release $where")
        assertEquals(emptyList(), world.engine.lateCalls, "late engine calls at the end $where")
    }

    // A bare TestScope: the enumeration builds thousands of worlds and runTest's watchdog machinery
    // costs more than the world itself. Nothing here waits in real time; the scheduler is the test's.
    private fun runWorld(trace: List<Act>) {
        val scope = TestScope()
        with(scope) {
            val world = World(this)
            world.collector = launch(StandardTestDispatcher(testScheduler)) {
                world.player.stateFlow.collect { world.seen += it }
            }
            testScheduler.runCurrent()
            for (act: Act in trace) apply(world, act)
            settle(world)
        }
    }

    private fun sequences(alphabet: List<Act>, length: Int): Sequence<List<Act>> =
        if (length == 0) {
            sequenceOf(emptyList())
        } else {
            sequences(alphabet, length - 1).flatMap { prefix -> alphabet.asSequence().map { prefix + it } }
        }

    @Test
    fun `all 169 sequences of two calls or events keep the documented states`() {
        var worlds = 0
        for (trace: List<Act> in sequences(Act.entries, 2)) {
            runWorld(trace)
            worlds++
        }
        assertEquals(169, worlds)
    }

    @Test
    fun `all 2197 sequences of three calls or events keep the documented states`() {
        var worlds = 0
        for (trace: List<Act> in sequences(Act.entries, 3)) {
            runWorld(trace)
            worlds++
        }
        assertEquals(2197, worlds)
    }

    @Test
    fun `all 6561 sequences of four of nine main calls keep the documented states`() {
        val alphabet: List<Act> = listOf(
            Act.PrepareA, Act.PrepareB, Act.Release, Act.FinishLoad, Act.Play, Act.Pause, Act.Stop, Act.FailPlayback,
            Act.CancelLastPrepare,
        )
        var worlds = 0
        for (trace: List<Act> in sequences(alphabet, 4)) {
            runWorld(trace)
            worlds++
        }
        assertEquals(6561, worlds)
    }

    /**
     * A bare TestScope instead of runTest: runTest drains the scheduler when the body ends, and the poll of a
     * player that is still playing never ends, so a red assertion would turn into an endless loop. Here a red
     * stays a red; every test below still releases what it created on its green path.
     */
    private fun budget(block: TestScope.() -> Unit) {
        with(TestScope()) { block() }
    }

    // --- Q2: resource budget -------------------------------------------------------------------------

    private fun TestScope.newPlayer(engine: WatchedEngine): VideoPlayer = createVideoPlayer(
        engine,
        VideoPlayerConfig(positionUpdateIntervalMs = 100L),
        StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `two hundred prepare and release cycles leave every engine released once disposed once and silent`() =
        budget {
            val engines: MutableList<WatchedEngine> = mutableListOf()
            repeat(200) { index: Int ->
                val engine = WatchedEngine()
                engines += engine
                val player: VideoPlayer = newPlayer(engine)
                val prepare: Job = launch(StandardTestDispatcher(testScheduler)) { player.prepare(sourceA) }
                testScheduler.runCurrent()
                assertTrue(prepare.isCompleted, "cycle $index: prepare did not return")
                assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
                player.play()
                player.release()
                player.release()
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "cycle $index")
            }
            val reads: List<Int> = engines.map { it.reads }
            testScheduler.advanceTimeBy(60_000L)
            testScheduler.runCurrent()
            for ((index: Int, engine: WatchedEngine) in engines.withIndex()) {
                assertEquals(1, engine.fake.releaseCount, "engine $index releaseCount")
                assertEquals(1, engine.fake.disposeCount, "engine $index disposeCount")
                assertTrue(engine.listenerCleared, "engine $index listener")
                assertEquals(reads[index], engine.reads, "engine $index read after release")
                assertEquals(emptyList(), engine.lateCalls, "engine $index late calls")
            }
        }

    @Test
    fun `a thousand source swaps on one player release the engine once per replaced source`() = budget {
        val engine = WatchedEngine()
        val player: VideoPlayer = newPlayer(engine)
        // runTest drains the scheduler when the body ends, and a playing player's poll never ends:
        // whatever happens below, the player must be released first or a red turns into a hang.
        try {
            swapThousand(player, engine)
        } catch (t: Throwable) {
            player.release()
            throw t
        }
        player.release()
        assertEquals(1_000, engine.fake.releaseCount)
        assertEquals(1, engine.fake.disposeCount)
        val reads: Int = engine.reads
        testScheduler.advanceTimeBy(60_000L)
        testScheduler.runCurrent()
        assertEquals(reads, engine.reads)
    }

    private fun TestScope.swapThousand(player: VideoPlayer, engine: WatchedEngine) {
        repeat(1_000) { index: Int ->
            val source: VideoSource = VideoSource.Remote("https://example.test/$index.mp4")
            val prepare: Job = launch(StandardTestDispatcher(testScheduler)) { player.prepare(source) }
            testScheduler.runCurrent()
            assertTrue(prepare.isCompleted, "swap $index did not return")
            if (index % 3 == 0 && index < 999) player.play()
        }
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        assertEquals(1_000, engine.fake.loadedSources.size)
        // 06-testing: a first prepare adds none; each prepare that replaces a loaded source adds one.
        assertEquals(999, engine.fake.releaseCount)
        assertEquals(0, engine.fake.disposeCount)
    }

    @Test
    fun `a thousand swaps with each load held and superseded leave one load and no stuck prepare`() = budget {
        val engine = WatchedEngine()
        engine.fake.suspendLoads = true
        val player: VideoPlayer = newPlayer(engine)
        val prepares: MutableList<Job> = mutableListOf()
        repeat(1_000) { index: Int ->
            val source: VideoSource = VideoSource.Remote("https://example.test/$index.mp4")
            prepares += launch(StandardTestDispatcher(testScheduler)) { player.prepare(source) }
            testScheduler.runCurrent()
        }
        // Only the newest load is open; every older one was cancelled and returned.
        assertEquals(1, prepares.count { it.isActive }, "open prepares")
        assertTrue(engine.fake.isLoadPending)
        engine.fake.finishLoad()
        testScheduler.runCurrent()
        assertTrue(prepares.none { it.isActive })
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        player.release()
        assertEquals(1, engine.fake.disposeCount)
    }

    @Test
    fun `fifty players alive at once stay independent and each is disposed once`() = budget {
        val engines: List<WatchedEngine> = List(50) { WatchedEngine() }
        val players: List<VideoPlayer> = engines.map { newPlayer(it) }
        for ((index: Int, player: VideoPlayer) in players.withIndex()) {
            launch(StandardTestDispatcher(testScheduler)) { player.prepare(sourceA) }
            testScheduler.runCurrent()
            player.play()
            if (index % 2 == 0) player.seekTo(index * 10L)
        }
        for ((index: Int, player: VideoPlayer) in players.withIndex()) {
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value, "player $index")
        }
        // Releasing every second player touches none of the others.
        for (index: Int in players.indices step 2) players[index].release()
        for ((index: Int, player: VideoPlayer) in players.withIndex()) {
            if (index % 2 == 0) {
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "released player $index")
            } else {
                assertIs<VideoPlayerState.Playing>(player.stateFlow.value, "live player $index")
                assertEquals(0, engines[index].fake.disposeCount, "live engine $index")
            }
        }
        for (index: Int in players.indices.reversed()) players[index].release()
        for ((index: Int, engine: WatchedEngine) in engines.withIndex()) {
            assertEquals(1, engine.fake.disposeCount, "engine $index")
            assertEquals(1, engine.fake.releaseCount, "engine $index")
        }
        val reads: List<Int> = engines.map { it.reads }
        testScheduler.advanceTimeBy(60_000L)
        testScheduler.runCurrent()
        assertEquals(reads, engines.map { it.reads })
    }
}
