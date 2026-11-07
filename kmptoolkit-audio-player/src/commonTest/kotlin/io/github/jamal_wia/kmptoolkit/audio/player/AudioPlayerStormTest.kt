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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Concurrency storm for the player: many callers on real threads issuing prepare (some cancelled
 * mid-flight), unload, release and every transport call while a further thread fires engine callbacks,
 * against an engine that models what the real ones hold: one native handle, replaced by `load` and
 * freed by `release`.
 *
 * Every wait is bounded in real time and every test body runs under `withTimeout`; the jitter is
 * seeded and the seed is in each failure message.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalCoroutinesApi::class)
class AudioPlayerStormTest {

    private val sources: List<AudioSource> = List(5) { AudioSource.Remote("https://example.test/clip-$it.mp3") }

    /**
     * An engine like [PlaybackEngine] describes the real ones: `load` frees what it held, publishes a
     * new handle first and only then waits (so a cancelled wait must free it), and `release` frees it.
     */
    private class HandleEngine(private val loadSteps: Int, private val slowUnwind: Boolean = false) : PlaybackEngine {
        val live: AtomicInt = AtomicInt(0)
        val maxLive: AtomicInt = AtomicInt(0)
        val activeLoads: AtomicInt = AtomicInt(0)
        val maxLoads: AtomicInt = AtomicInt(0)
        val releasesDuringLoad: AtomicInt = AtomicInt(0)
        val loads: AtomicInt = AtomicInt(0)
        private val handle: AtomicInt = AtomicInt(0)
        private val ids: AtomicInt = AtomicInt(0)
        private val listenerRef: AtomicReference<PlaybackEngineListener?> = AtomicReference(null)
        private val failNextLoad: AtomicBoolean = AtomicBoolean(false)

        fun listener(): PlaybackEngineListener? = listenerRef.load()
        fun failNext() = failNextLoad.store(true)
        fun clearFailure() = failNextLoad.store(false)

        override fun setListener(listener: PlaybackEngineListener?) = listenerRef.store(listener)

        override suspend fun load(source: AudioSource) {
            loads.incrementAndFetch()
            val now: Int = activeLoads.incrementAndFetch()
            while (true) {
                val seen: Int = maxLoads.load()
                if (now <= seen || maxLoads.compareAndSet(seen, now)) break
            }
            try {
                freeHandle()
                val mine: Int = ids.incrementAndFetch()
                handle.store(mine)
                val liveNow: Int = live.incrementAndFetch()
                while (true) {
                    val seen: Int = maxLive.load()
                    if (liveNow <= seen || maxLive.compareAndSet(seen, liveNow)) break
                }
                try {
                    if (loadSteps == 0) yield() else delay(loadSteps.toLong())
                    if (failNextLoad.exchange(false)) throw IllegalStateException("scripted load failure")
                } catch (failure: Throwable) {
                    // A load that fails or is cancelled leaves nothing playable behind; a slow engine
                    // takes its time to get there, and a cancelled load must still not be overtaken.
                    if (slowUnwind) withContext(NonCancellable) { delay(1L) }
                    freeHandle()
                    throw failure
                }
            } finally {
                activeLoads.decrementAndFetch()
            }
        }

        private fun freeHandle() {
            if (handle.exchange(0) != 0) live.decrementAndFetch()
        }

        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = 1_234L

        // `load` frees through freeHandle() directly, so every release() call is the player's.
        override fun release() {
            if (activeLoads.load() > 0) releasesDuringLoad.incrementAndFetch()
            freeHandle()
        }
    }

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("timed out waiting for: $what")
            delay(1L)
        }
    }

    private fun storm(body: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default) { withTimeout(45.seconds) { body() } }
    }

    private suspend fun jitter(random: Random) {
        when (random.nextInt(4)) {
            0 -> repeat(random.nextInt(3_000)) { Unit }
            1 -> yield()
            2 -> delay(random.nextLong(0L, 3L))
            else -> Unit
        }
    }

    private fun CoroutineScope.worker(
        player: AudioPlayer,
        engine: HandleEngine,
        seed: Long,
        releaseAtEnd: Boolean,
    ): Job {
        val random = Random(seed)
        return launch(Dispatchers.Default) {
            repeat(OPS) { op: Int ->
                when (random.nextInt(14)) {
                    0, 1, 2 -> {
                        val target: AudioSource = sources[random.nextInt(sources.size)]
                        val call: Job = launch(Dispatchers.Default) { player.prepare(target) }
                        // A child of this worker, so joining the worker waits for the call as well.
                        if (random.nextBoolean()) {
                            jitter(random)
                            call.cancel()
                        }
                    }
                    3 -> player.unload()
                    4 -> player.play()
                    5 -> player.pause()
                    6 -> player.stop()
                    7 -> player.seekTo(random.nextLong(-5L, 12_000L))
                    8 -> player.seekForward(random.nextLong(0L, 5_000L))
                    9 -> player.seekBackward(random.nextLong(0L, 5_000L))
                    10 -> player.replay()
                    11 -> player.setPlaybackSpeed(0.5f + random.nextInt(4) * 0.5f)
                    12 -> if (random.nextInt(3) == 0) engine.failNext() else player.pause()
                    else -> jitter(random)
                }
                if (releaseAtEnd && op == OPS - 1) player.release()
            }
        }
    }

    private fun CoroutineScope.callbackThread(engine: HandleEngine, seed: Long, done: AtomicBoolean): Job {
        val random = Random(seed)
        return launch(Dispatchers.Default) {
            while (!done.load()) {
                val target: PlaybackEngineListener? = engine.listener()
                when (random.nextInt(6)) {
                    0 -> target?.onFailed(IllegalStateException("platform error"))
                    1, 2, 3 -> target?.onCompleted()
                    else -> Unit
                }
                if (random.nextInt(3) == 0) delay(1L) else repeat(random.nextInt(400)) { Unit }
            }
        }
    }

    @Test
    fun `six threads of prepare and cancel and unload and transport calls never overlap loads or leak a handle`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 104_729L * round + 17L
            val engine = HandleEngine(loadSteps = round % 3, slowUnwind = round % 2 == 0)
            val player: AudioPlayer = createAudioPlayer(
                engine = engine,
                config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                coroutineContext = Dispatchers.Default,
            )
            val done = AtomicBoolean(false)
            val workers: List<Job> = (0 until 6).map { w: Int -> worker(player, engine, seed + w, releaseAtEnd = false) }
            val reactor: Job = launch(Dispatchers.Default) {
                // A consumer reacting to every state from yet another thread: the feedback must settle.
                player.stateFlow.collect { state: PlayerState ->
                    when (state) {
                        is PlayerState.Completed -> player.replay()
                        is PlayerState.Error -> player.unload()
                        is PlayerState.Ready -> player.play()
                        else -> Unit
                    }
                }
            }
            val callbacks: Job = callbackThread(engine, seed + 99L, done)
            workers.joinAll()
            done.store(true)
            callbacks.join()
            reactor.cancelAndJoin()
            engine.clearFailure()

            awaitTrue("seed $seed: loads unwound") { engine.activeLoads.load() == 0 }
            assertTrue(engine.maxLoads.load() <= 1, "seed $seed: ${engine.maxLoads.load()} loads ran at once")
            assertEquals(0, engine.releasesDuringLoad.load(), "seed $seed: engine released under a running load")
            assertTrue(engine.maxLive.load() <= 1, "seed $seed: ${engine.maxLive.load()} native handles live at once")

            // Not wedged: the very next prepare completes and publishes Ready.
            withTimeout(5.seconds) { player.prepare(sources[0]) }
            assertIs<PlayerState.Ready>(player.stateFlow.value, "seed $seed")
            assertEquals(1, engine.live.load(), "seed $seed: a Ready player must hold exactly one handle")

            player.release()
            awaitTrue("seed $seed: handle freed after release") { engine.live.load() == 0 }
            assertEquals(PlayerState.Idle, player.stateFlow.value, "seed $seed")
            assertNull(engine.listener(), "seed $seed: listener still attached after release")
        }
    }

    @Test
    fun `a release landing anywhere in the storm frees the handle once and leaves an inert player`() = storm {
        repeat(ROUNDS) { round: Int ->
            val seed: Long = 7_907L * round + 5L
            val engine = HandleEngine(loadSteps = round % 3, slowUnwind = round % 2 == 1)
            val player: AudioPlayer = createAudioPlayer(
                engine = engine,
                config = AudioPlayerConfig(positionUpdateIntervalMs = 1L),
                coroutineContext = Dispatchers.Default,
            )
            val done = AtomicBoolean(false)
            val workers: List<Job> = (0 until 5).map { w: Int ->
                worker(player, engine, seed + w, releaseAtEnd = w == 0)
            }
            val killer: Job = launch(Dispatchers.Default) {
                jitter(Random(seed + 50L))
                player.release()
            }
            val reactor: Job = launch(Dispatchers.Default) {
                // A consumer reacting to every state from yet another thread: the feedback must settle.
                player.stateFlow.collect { state: PlayerState ->
                    when (state) {
                        is PlayerState.Completed -> player.replay()
                        is PlayerState.Error -> player.unload()
                        is PlayerState.Ready -> player.play()
                        else -> Unit
                    }
                }
            }
            val callbacks: Job = callbackThread(engine, seed + 99L, done)
            workers.joinAll()
            killer.join()
            done.store(true)
            callbacks.join()
            reactor.cancelAndJoin()

            awaitTrue("seed $seed: loads unwound") { engine.activeLoads.load() == 0 }
            awaitTrue("seed $seed: handle freed") { engine.live.load() == 0 }
            assertEquals(0, engine.releasesDuringLoad.load(), "seed $seed: engine released under a running load")
            assertTrue(engine.maxLoads.load() <= 1, "seed $seed: ${engine.maxLoads.load()} loads ran at once")
            assertTrue(engine.maxLive.load() <= 1, "seed $seed: ${engine.maxLive.load()} native handles live at once")
            assertNull(engine.listener(), "seed $seed: listener still attached after release")
            // Inert: nothing resurrects the state or the handle.
            player.play()
            player.seekTo(1L)
            withTimeout(5.seconds) { player.prepare(sources[1]) }
            delay(5L)
            assertEquals(0, engine.live.load(), "seed $seed: a released player loaded a handle")
            assertTrue(
                player.stateFlow.value == PlayerState.Idle || player.stateFlow.value is PlayerState.Error,
                "seed $seed: ${player.stateFlow.value}",
            )
        }
    }


    private companion object {
        const val ROUNDS: Int = 30
        const val OPS: Int = 120
    }
}
