package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.TimeSource

/**
 * Transport, settings, platform callbacks and prepare/unload storms on real threads against a
 * strict engine that models what a platform engine is: not thread-safe (two calls overlapping is a
 * breach), unable to take a transport call while a load runs, and either running or not. At rest the
 * player's state must describe the engine: Playing exactly when the engine runs.
 */
@OptIn(ExperimentalAtomicApi::class)
class VideoPlayerEngineConsistencyStormAdversarialTest {

    private class ModelEngine : VideoPlaybackEngine {
        private val attached: AtomicReference<VideoPlaybackEngineListener?> = AtomicReference(null)
        private val inCall: AtomicInt = AtomicInt(0)
        val loadActive: AtomicInt = AtomicInt(0)
        val running: AtomicInt = AtomicInt(0)
        private val disposed: AtomicInt = AtomicInt(0)
        private val breaches: AtomicReference<List<String>> = AtomicReference(emptyList())

        val violations: List<String> get() = breaches.load()
        val listener: VideoPlaybackEngineListener? get() = attached.load()

        private fun breach(what: String) {
            while (true) {
                val seen: List<String> = breaches.load()
                if (breaches.compareAndSet(seen, seen + what)) return
            }
        }

        private inline fun <T> call(name: String, transport: Boolean = false, block: () -> T): T {
            if (disposed.load() != 0) breach("$name after dispose")
            if (transport && loadActive.load() > 0) breach("$name during a load")
            if (inCall.incrementAndFetch() > 1) breach("$name overlapped another engine call")
            try {
                val mark = TimeSource.Monotonic.markNow()
                while (mark.elapsedNow().inWholeMicroseconds < SPIN_US) Unit
                return block()
            } finally {
                inCall.decrementAndFetch()
            }
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) = attached.store(listener)

        override suspend fun load(source: VideoSource) {
            call("load") { running.store(0) }
            loadActive.incrementAndFetch()
            try {
                try {
                    delay(2L)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    withContext(NonCancellable) { delay(2L) }
                    throw cancellation
                }
            } finally {
                loadActive.decrementAndFetch()
            }
        }

        override fun start() = call("start", transport = true) { running.store(1) }
        override fun pause() = call("pause", transport = true) { running.store(0) }
        override fun seekTo(positionMs: Long) = call("seekTo", transport = true) { }
        override fun setSpeed(speed: Float) = call("setSpeed", transport = true) { }
        override fun setVolume(volume: Float) = call("setVolume", transport = true) { }
        override fun setLooping(looping: Boolean) = call("setLooping", transport = true) { }
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = call("positionMs") { 5L }
        override fun bufferedPositionMs(): Long = 6L
        override fun release() = call("release") { running.store(0) }
        override fun dispose() {
            if (!disposed.compareAndSet(0, 1)) breach("second dispose")
        }

        /** The platform reaching the end of the source: it stops itself, then reports. */
        fun finish() {
            running.store(0)
            listener?.onCompleted()
        }
    }

    @Test
    fun `storms of transport, settings, callbacks and loads keep the engine contract and the state describes the engine`() =
        runTest {
            withContext(Dispatchers.Default) {
                withTimeout(30.seconds) { repeat(SEEDS) { seed: Int -> runSeed(seed) } }
            }
        }

    private suspend fun CoroutineScope.runSeed(seed: Int) {
        val engine = ModelEngine()
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = VideoPlayerConfig(positionUpdateIntervalMs = 2L),
            coroutineContext = Dispatchers.Default,
        )
        val start = CompletableDeferred<Unit>()
        val prepares: AtomicReference<List<Job>> = AtomicReference(emptyList())
        val clients: List<Deferred<Unit>> = List(CLIENTS) { client: Int ->
            async {
                val random = Random(seed * 17 + client)
                start.await()
                repeat(OPS) { index: Int ->
                    when (random.nextInt(100)) {
                        in 0..11 -> {
                            val call: Job = async { player.prepare(VideoSource.Remote("https://t.test/$seed/$client/$index")) }
                            while (true) {
                                val seen: List<Job> = prepares.load()
                                if (prepares.compareAndSet(seen, seen + call)) break
                            }
                        }
                        in 12..15 -> prepares.load().let { all: List<Job> -> if (all.isNotEmpty()) all.random(random).cancel() }
                        in 16..19 -> player.unload()
                        in 20..39 -> player.play()
                        in 40..54 -> player.pause()
                        in 55..60 -> player.stop()
                        in 61..66 -> player.seekTo(random.nextLong(0L, 11_000L))
                        in 67..69 -> player.seekForward(random.nextLong(0L, 3_000L))
                        in 70..72 -> player.seekBackward(random.nextLong(0L, 3_000L))
                        in 73..75 -> player.replay()
                        in 76..78 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                        in 79..81 -> player.setPlaybackSpeed(random.nextFloat() * 3f)
                        in 82..84 -> { player.setVolume(random.nextFloat()); player.setMuted(random.nextBoolean()) }
                        in 85..89 -> engine.finish()
                        in 90..92 -> engine.listener?.onBufferingChanged(random.nextBoolean())
                        in 93..94 -> engine.listener?.onVideoSizeChanged(VideoSize(2, 2))
                        in 95..96 -> engine.listener?.onFailed(IllegalStateException("platform failure"))
                        else -> delay(random.nextLong(0L, 3L))
                    }
                }
            }
        }
        start.complete(Unit)
        clients.awaitAll()
        prepares.load().forEach { call: Job ->
            try { call.join() } catch (_: kotlinx.coroutines.CancellationException) { }
        }
        delay(30L)

        val state: VideoPlayerState = player.stateFlow.value
        val context = "seed $seed state=$state running=${engine.running.load()} violations=${engine.violations}"
        assertTrue(engine.violations.isEmpty(), context)
        assertEquals(0, engine.loadActive.load(), "$context: load still active")
        assertTrue(state != VideoPlayerState.Preparing, "$context: stuck in Preparing")
        when (state) {
            is VideoPlayerState.Playing -> assertEquals(1, engine.running.load(), "$context: Playing but the engine is not running")
            is VideoPlayerState.Paused, is VideoPlayerState.Ready, VideoPlayerState.Idle ->
                assertEquals(0, engine.running.load(), "$context: not Playing but the engine runs")
            else -> Unit
        }
        if (state == VideoPlayerState.Idle) {
            assertEquals(0L, player.playbackPositionFlow.value, "$context: position on Idle")
            assertEquals(false, player.isBufferingFlow.value, "$context: buffering on Idle")
            assertEquals(null, player.videoSizeFlow.value, "$context: size on Idle")
        }
        player.release()
        delay(10L)
        assertTrue(engine.violations.isEmpty(), "$context after release")
    }

    private companion object {
        const val SEEDS: Int = 120
        const val CLIENTS: Int = 5
        const val OPS: Int = 50
        const val SPIN_US: Long = 30L
    }
}
