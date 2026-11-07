package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle chaos on real threads: several clients each run a random script of prepare, cancel,
 * unload, release, transport calls and stale platform callbacks against one player over a scripted
 * engine that records every contract breach (a load under a load, a release or dispose under a
 * running load, a call after dispose, a load after release). Scripts are seeded, the interleaving is
 * not; every wait is bounded and a deadlock fails as a timeout naming the seed.
 */
@OptIn(ExperimentalAtomicApi::class)
class VideoPlayerThreadedLifecycleAdversarialTest {

    private class StrictEngine : VideoPlaybackEngine {
        private val attachedListener: AtomicReference<VideoPlaybackEngineListener?> = AtomicReference(null)
        val active: AtomicInt = AtomicInt(0)
        val disposeCount: AtomicInt = AtomicInt(0)
        val loaded: AtomicInt = AtomicInt(0)
        private val disposed: AtomicInt = AtomicInt(0)
        private val violationList: AtomicReference<List<String>> = AtomicReference(emptyList())

        val violations: List<String> get() = violationList.load()
        val listener: VideoPlaybackEngineListener? get() = attachedListener.load()

        private fun breach(what: String) {
            while (true) {
                val seen: List<String> = violationList.load()
                if (violationList.compareAndSet(seen, seen + what)) return
            }
        }

        private fun note(call: String) {
            if (disposed.load() != 0) breach("$call after dispose")
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) {
            attachedListener.store(listener)
        }

        override suspend fun load(source: VideoSource) {
            note("load")
            if (active.incrementAndFetch() > 1) breach("two loads at once")
            loaded.store(0)
            try {
                try {
                    delay(LOAD_MS_CHOICES[(source as VideoSource.Remote).url.length % LOAD_MS_CHOICES.size])
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) { delay(UNWIND_MS) }
                    throw cancellation
                }
                loaded.store(1)
            } finally {
                active.decrementAndFetch()
            }
        }

        override fun start() = note("start")
        override fun pause() = note("pause")
        override fun seekTo(positionMs: Long) = note("seekTo")
        override fun setSpeed(speed: Float) = note("setSpeed")
        override fun setVolume(volume: Float) = note("setVolume")
        override fun setLooping(looping: Boolean) = note("setLooping")
        override fun durationMs(): Long = DURATION_MS
        override fun positionMs(): Long {
            note("positionMs")
            return 1L
        }

        override fun bufferedPositionMs(): Long = 2L

        override fun release() {
            note("release")
            if (active.load() > 0) breach("release under a running load")
            loaded.store(0)
        }

        override fun dispose() {
            if (disposed.compareAndSet(0, 1).not()) breach("second dispose")
            if (active.load() > 0) breach("dispose under a running load")
            disposeCount.incrementAndFetch()
        }
    }

    @Test
    fun `four clients running random lifecycle scripts never breach the engine contract and never hang`() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(REAL_BUDGET) {
                    repeat(SEEDS) { seed: Int -> runSeed(seed, releases = seed % 3 == 0) }
                }
            }
        }

    private suspend fun kotlinx.coroutines.CoroutineScope.runSeed(seed: Int, releases: Boolean) {
        val engine = StrictEngine()
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = VideoPlayerConfig(positionUpdateIntervalMs = 3L),
            coroutineContext = Dispatchers.Default,
        )
        val stale: VideoPlaybackEngineListener = requireNotNull(engine.listener)
        val start = CompletableDeferred<Unit>()
        val prepares: AtomicReference<List<Job>> = AtomicReference(emptyList())
        val clients: List<Deferred<Unit>> = List(CLIENTS) { client: Int ->
            async {
                val random = Random(seed * 31 + client)
                start.await()
                repeat(OPS_PER_CLIENT) { index: Int ->
                    when (val op: Int = random.nextInt(100)) {
                        in 0..29 -> {
                            val url = "https://example.test/$seed/$client/$index.mp4"
                            val call: Job = async { player.prepare(VideoSource.Remote(url)) }
                            while (true) {
                                val seen: List<Job> = prepares.load()
                                if (prepares.compareAndSet(seen, seen + call)) break
                            }
                        }

                        in 30..39 -> prepares.load().let { all: List<Job> -> if (all.isNotEmpty()) all.random(random).cancel() }
                        in 40..49 -> player.unload()
                        in 50..51 -> if (releases) player.release()
                        in 52..61 -> player.play()
                        in 62..64 -> player.pause()
                        in 65..70 -> player.seekTo(random.nextLong(0L, 12_000L))
                        in 71..73 -> player.replay()
                        in 74..76 -> player.stop()
                        in 77..79 -> {
                            player.setVolume(random.nextFloat())
                            player.setPlaybackSpeed(random.nextFloat() * 3f)
                        }
                        in 80..84 -> stale.onCompleted()
                        in 85..87 -> stale.onFailed(IllegalStateException("stale"))
                        in 88..92 -> {
                            stale.onBufferingChanged(op % 2 == 0)
                            stale.onVideoSizeChanged(VideoSize(4, 4))
                        }
                        else -> delay(random.nextLong(0L, 3L))
                    }
                }
            }
        }
        start.complete(Unit)
        clients.awaitAll()
        prepares.load().forEach { call: Job ->
            // A cancelled call ends with the cancellation; anything else a prepare throws is a breach.
            try {
                call.join()
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        // Let the engine's unwinds and a last poll tick land.
        delay(40L)

        val state: VideoPlayerState = player.stateFlow.value
        val context = "seed $seed state=$state violations=${engine.violations}"
        assertTrue(engine.violations.isEmpty(), context)
        assertEquals(0, engine.active.load(), "$context: a load is still running")
        assertTrue(state != VideoPlayerState.Preparing, "$context: stuck in Preparing")
        if (state.isPlayable) assertEquals(1, engine.loaded.load(), "$context: playable state with nothing loaded in the engine")
        if (!releases) assertEquals(0, engine.disposeCount.load(), "$context: disposed without release")

        player.release()
        delay(20L)
        assertEquals(1, engine.disposeCount.load(), "$context: dispose count")
        assertTrue(engine.violations.isEmpty(), "$context after the final release")
        assertEquals(0L, player.playbackPositionFlow.value, "$context: position after release")
        assertEquals(0L, player.bufferedPositionFlow.value, "$context: buffered after release")
        assertEquals(false, player.isBufferingFlow.value, "$context: buffering after release")
        assertEquals(null, player.videoSizeFlow.value, "$context: size after release")
        assertTrue(
            player.stateFlow.value.let { it == VideoPlayerState.Idle || (it is VideoPlayerState.Error && it.cause is VideoPlayerReleasedException) },
            "$context: final state ${player.stateFlow.value}",
        )
    }

    private companion object {
        const val SEEDS: Int = 300
        const val CLIENTS: Int = 4
        const val OPS_PER_CLIENT: Int = 40
        const val UNWIND_MS: Long = 3L
        const val DURATION_MS: Long = 10_000L
        val LOAD_MS_CHOICES: LongArray = longArrayOf(0L, 1L, 4L, 9L)
        val REAL_BUDGET = 55.seconds
    }
}
