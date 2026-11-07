package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Many callers: dozens of coroutines call `prepare` on one player at the same moment, on real
 * threads. The player's contract is one load at a time, the newest source winning, every caller
 * returning, and no source left half-loaded in the engine.
 */
@OptIn(ExperimentalAtomicApi::class)
class VideoPlayerConcurrentPrepareAdversarialTest {

    @Test
    fun `64 concurrent prepare calls run one load at a time and all return with the newest source ready`() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(REAL_BUDGET) {
                    repeat(ROUNDS) { round: Int ->
                        val engine = ExclusiveLoadEngine()
                        val player: VideoPlayer = createVideoPlayer(
                            engine = engine,
                            config = VideoPlayerConfig(positionUpdateIntervalMs = 5L),
                            coroutineContext = Dispatchers.Default,
                        )
                        val start = CompletableDeferred<Unit>()
                        val callers: List<Deferred<Unit>> = List(CALLERS) { index: Int ->
                            async {
                                start.await()
                                player.prepare(VideoSource.Remote("https://example.test/$round/$index.mp4"))
                            }
                        }
                        start.complete(Unit)
                        callers.awaitAll()

                        val state: VideoPlayerState = player.stateFlow.value
                        assertIs<VideoPlayerState.Ready>(state, "round $round: final state")
                        assertEquals(1, engine.maxConcurrentLoads.load(), "round $round: loads overlapped")
                        assertEquals(0, engine.openLoads.load(), "round $round: a load is still open in the engine")
                        val started: List<VideoSource> = engine.started.load()
                        val completed: List<VideoSource> = engine.completed.load()
                        assertTrue(completed.isNotEmpty(), "round $round: nothing finished loading")
                        assertEquals(
                            started.last(),
                            completed.last(),
                            "round $round: the engine's last finished load is not the last one it was asked to start",
                        )

                        player.release()
                        assertEquals(1, engine.disposals.load(), "round $round: disposals")
                        assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "round $round: after release")
                    }
                }
            }
        }

    @Test
    fun `callers that prepare then are cancelled while others keep preparing leave no load open and the player usable`() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(REAL_BUDGET) {
                    repeat(ROUNDS) { round: Int ->
                        val engine = ExclusiveLoadEngine()
                        val player: VideoPlayer = createVideoPlayer(
                            engine = engine,
                            coroutineContext = Dispatchers.Default,
                        )
                        val start = CompletableDeferred<Unit>()
                        val callers: List<Deferred<Unit>> = List(CALLERS) { index: Int ->
                            async {
                                start.await()
                                player.prepare(VideoSource.Remote("https://example.test/$round/$index.mp4"))
                            }
                        }
                        start.complete(Unit)
                        // Cancel every third caller while loads are in flight: cancellation mid-load.
                        callers.filterIndexed { i: Int, _ -> i % 3 == 0 }.forEach { it.cancel() }
                        callers.forEach { runCatching { it.await() } }

                        assertEquals(0, engine.openLoads.load(), "round $round: a load is still open in the engine")
                        // At most one: when the last caller to arrive is cancelled, every earlier load was
                        // superseded before it reached the engine and zero loads is legitimate.
                        assertTrue(engine.maxConcurrentLoads.load() <= 1, "round $round: loads overlapped")
                        // The player must still be usable: a fresh prepare reaches Ready.
                        player.prepare(VideoSource.Remote("https://example.test/$round/final.mp4"))
                        assertIs<VideoPlayerState.Ready>(player.stateFlow.value, "round $round: after a fresh prepare")
                        player.release()
                    }
                }
            }
        }

    @Test
    fun `release racing 32 concurrent prepare calls disposes once and starts no load after it`() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(REAL_BUDGET) {
                    repeat(ROUNDS) { round: Int ->
                        val engine = ExclusiveLoadEngine()
                        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Default)
                        val start = CompletableDeferred<Unit>()
                        val callers: List<Deferred<Unit>> = List(32) { index: Int ->
                            async {
                                start.await()
                                player.prepare(VideoSource.Remote("https://example.test/$round/$index.mp4"))
                            }
                        }
                        val releasers: List<Deferred<Unit>> = List(3) {
                            async {
                                start.await()
                                delay((round % 3).toLong())
                                player.release()
                            }
                        }
                        start.complete(Unit)
                        (callers + releasers).awaitAll()

                        assertEquals(1, engine.disposals.load(), "round $round: disposals")
                        assertEquals(0, engine.openLoads.load(), "round $round: a load is still open in the engine")
                        assertTrue(engine.maxConcurrentLoads.load() <= 1, "round $round: loads overlapped")
                        assertEquals(0, engine.lateLoads.load(), "round $round: a load started after dispose")
                        val after: VideoPlayerState = player.stateFlow.value
                        assertTrue(
                            after == VideoPlayerState.Idle ||
                                (after is VideoPlayerState.Error && after.cause is VideoPlayerReleasedException),
                            "round $round: state after release was $after",
                        )
                    }
                }
            }
        }

    @Test
    fun `unload racing 64 concurrent prepare calls leaves no load open and a player that prepares again`() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(REAL_BUDGET) {
                    repeat(ROUNDS) { round: Int ->
                        val engine = ExclusiveLoadEngine()
                        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Default)
                        val start = CompletableDeferred<Unit>()
                        val callers: List<Deferred<Unit>> = List(CALLERS) { index: Int ->
                            async {
                                start.await()
                                if (index % 2 == 0) {
                                    player.prepare(VideoSource.Remote("https://example.test/$round/$index.mp4"))
                                } else {
                                    player.unload()
                                }
                            }
                        }
                        start.complete(Unit)
                        callers.awaitAll()

                        assertEquals(0, engine.openLoads.load(), "round $round: a load is still open in the engine")
                        assertTrue(engine.maxConcurrentLoads.load() <= 1, "round $round: loads overlapped")
                        val settled: VideoPlayerState = player.stateFlow.value
                        assertTrue(
                            settled is VideoPlayerState.Ready || settled == VideoPlayerState.Idle,
                            "round $round: stuck in $settled",
                        )
                        player.prepare(VideoSource.Remote("https://example.test/$round/final.mp4"))
                        assertIs<VideoPlayerState.Ready>(player.stateFlow.value, "round $round: after a fresh prepare")
                        player.release()
                        assertEquals(1, engine.disposals.load(), "round $round: disposals")
                    }
                }
            }
        }

    /**
     * Thread-safe engine whose [load] takes a little real time and counts how many loads are open at
     * once, which the player promises is at most one.
     */
    private class ExclusiveLoadEngine : VideoPlaybackEngine {
        private val listener: AtomicReference<VideoPlaybackEngineListener?> = AtomicReference(null)
        private val open: AtomicInt = AtomicInt(0)
        val openLoads: AtomicInt get() = open
        val maxConcurrentLoads: AtomicInt = AtomicInt(0)
        val disposals: AtomicInt = AtomicInt(0)
        val lateLoads: AtomicInt = AtomicInt(0)
        private val disposed: AtomicInt = AtomicInt(0)
        val started: AtomicReference<List<VideoSource>> = AtomicReference(emptyList())
        val completed: AtomicReference<List<VideoSource>> = AtomicReference(emptyList())

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            this.listener.store(listener)
        }

        override suspend fun load(source: VideoSource) {
            if (disposed.load() != 0) lateLoads.incrementAndFetch()
            val now: Int = open.incrementAndFetch()
            while (true) {
                val seen: Int = maxConcurrentLoads.load()
                if (now <= seen || maxConcurrentLoads.compareAndSet(seen, now)) break
            }
            append(started, source)
            try {
                delay(LOAD_MS)
                append(completed, source)
            } finally {
                open.decrementAndFetch()
            }
        }

        private fun append(cell: AtomicReference<List<VideoSource>>, source: VideoSource) {
            while (true) {
                val seen: List<VideoSource> = cell.load()
                if (cell.compareAndSet(seen, seen + source)) return
            }
        }

        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setLooping(looping: Boolean) = Unit
        override fun durationMs(): Long = DURATION_MS
        override fun positionMs(): Long = 0L
        override fun bufferedPositionMs(): Long = 0L
        override fun release() = Unit
        override fun dispose() {
            disposed.store(1)
            disposals.incrementAndFetch()
        }
    }

    private companion object {
        const val CALLERS: Int = 64
        const val ROUNDS: Int = 20
        const val LOAD_MS: Long = 2L
        const val DURATION_MS: Long = 10_000L
        val REAL_BUDGET = 40.seconds
    }
}
