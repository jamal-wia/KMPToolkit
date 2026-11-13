package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * D1 round 12, Q2: real threads. A platform-thread engine callback racing the consumer's calls, over the transition
 * under the lock and the queued-callback drain at depth 0 (`04-api-reference.md` "Threading": "An engine event that arrives
 * while a call is running is applied right after it"; every engine call is serialized, "never two at once"). The hard
 * bounds are real-time failures with their own message; every wait is a latch or a barrier, no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadInterleavingD1R12AdversarialTest {

    /** An engine that fails the test when two of its calls overlap, when it is called after dispose, or when it has no source and is started. */
    private class SerialEngine : VideoPlaybackEngine {
        @Volatile var sink: VideoPlaybackEngineListener? = null
        val inCall: AtomicInteger = AtomicInteger(0)
        val violations: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        @Volatile var disposed: Boolean = false
        @Volatile var started: Boolean = false
        @Volatile var position: Long = 0L
        var loadAction: suspend () -> Unit = {}
        val releases: AtomicInteger = AtomicInteger(0)
        val loads: AtomicInteger = AtomicInteger(0)
        val log: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()

        private inline fun <T> call(name: String, block: () -> T): T {
            if (inCall.incrementAndGet() != 1) violations += "$name overlapped another engine call"
            if (disposed) violations += "$name after dispose"
            log += name
            try {
                return block()
            } finally {
                inCall.decrementAndGet()
            }
        }

        override fun setListener(listener: VideoPlaybackEngineListener?) = call("setListener") { sink = listener }
        override suspend fun load(source: VideoSource) {
            // load runs suspended outside the lock by design; it is not part of the overlap count, but it is never after dispose.
            loads.incrementAndGet()
            log += "load"
            if (disposed) violations += "load after dispose"
            loadAction()
        }
        override fun start() = call("start") { started = true }
        override fun pause() = call("pause") { started = false }
        override fun seekTo(positionMs: Long) = call("seek") { position = positionMs }
        override fun setSpeed(speed: Float) = call("speed") {}
        override fun setVolume(volume: Float) = call("volume") {}
        override fun setLooping(looping: Boolean) = call("looping") {}
        override fun durationMs(): Long = 10_000L
        override fun positionMs(): Long = call("positionMs") { position }
        override fun bufferedPositionMs(): Long = call("bufferedMs") { 0L }
        override fun release() = call("release") { releases.incrementAndGet(); started = false }
        override fun dispose() = call("dispose") { disposed = true }
    }

    private val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")

    private fun newPlayer(engine: SerialEngine): VideoPlayer =
        createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 1L), Dispatchers.Default)

    private fun joinOrFail(thread: Thread, what: String) {
        thread.join(30_000L)
        if (thread.isAlive) throw AssertionError("$what did not finish within 30 s: deadlock or hang")
    }

    @Test
    fun `a consumer thread and an engine callback thread race and the engine never sees two calls at once`() {
        val iterations: Int = System.getProperty("kmptoolkit.test.threads.iterations")?.toIntOrNull() ?: 120
        val seedBase: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 12_012L
        repeat(iterations) { iteration ->
            val seed: Long = seedBase + iteration
            val engine = SerialEngine()
            val player: VideoPlayer = newPlayer(engine)
            val escaped: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue()
            try {
                runBlocking { withTimeout(10_000L) { player.prepare(source) } }
                val barrier = CyclicBarrier(2)
                val consumer = Thread {
                    val random = Random(seed)
                    try {
                        barrier.await(10, TimeUnit.SECONDS)
                        repeat(120) {
                            when (random.nextInt(11)) {
                                0 -> player.play()
                                1 -> player.pause()
                                2 -> player.stop()
                                3 -> player.seekTo(random.nextLong(-50L, 11_000L))
                                4 -> player.seekForward(random.nextLong(0L, 3_000L))
                                5 -> player.seekBackward(random.nextLong(0L, 3_000L))
                                6 -> player.replay()
                                7 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                                8 -> player.setPlaybackSpeed(0.5f + random.nextInt(4) * 0.5f)
                                9 -> player.setVolume(random.nextInt(5) / 4f)
                                else -> player.setMuted(random.nextBoolean())
                            }
                        }
                    } catch (thrown: Throwable) {
                        escaped += thrown
                    }
                }
                val callbacks = Thread {
                    val random = Random(seed xor 0x5DEECE66DL)
                    try {
                        barrier.await(10, TimeUnit.SECONDS)
                        val sink: VideoPlaybackEngineListener = checkNotNull(engine.sink)
                        repeat(240) {
                            when (random.nextInt(3)) {
                                0 -> sink.onCompleted()
                                1 -> sink.onBufferingChanged(random.nextBoolean())
                                else -> sink.onVideoSizeChanged(if (random.nextBoolean()) VideoSize(640, 360) else null)
                            }
                        }
                    } catch (thrown: Throwable) {
                        escaped += thrown
                    }
                }
                consumer.start()
                callbacks.start()
                joinOrFail(consumer, "consumer thread (seed $seed)")
                joinOrFail(callbacks, "callback thread (seed $seed)")
                assertTrue(escaped.isEmpty(), "seed $seed: an exception escaped: ${escaped.toList()}")
                // Quiesce: the calls below drain whatever the callback thread queued behind the last transition.
                player.stop()
                assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "seed $seed: stop leaves a playable player Ready; log ${engine.log.toList().takeLast(30)}")
                assertTrue(!engine.started, "seed $seed: stop left the engine started")
                player.play()
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing && engine.started, "seed $seed: play after the race: ${player.stateFlow.value}")
                player.pause()
                assertTrue(player.stateFlow.value is VideoPlayerState.Paused && !engine.started, "seed $seed: pause after the race: ${player.stateFlow.value}")
            } finally {
                player.release()
            }
            assertTrue(engine.violations.isEmpty(), "seed $seed: engine contract broken by the player: ${engine.violations.toList()}")
            assertEquals(1, engine.log.count { it == "dispose" }, "seed $seed: dispose exactly once")
        }
    }

    /** An engine callback arrives from another thread while a collector of stateFlow is inside the update and calls the player. */
    @Test
    fun `a callback from another thread during a collector's nested call is applied after the transition`() {
        for (kind in listOf("completed", "failed")) {
            val engine = SerialEngine()
            val player: VideoPlayer = newPlayer(engine)
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            try {
                runBlocking { withTimeout(10_000L) { player.prepare(source) } }
                player.play()
                val inside = CountDownLatch(1)
                val reported = CountDownLatch(1)
                val cause: Throwable = IllegalStateException("scripted $kind failure")
                var fired = false
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    player.stateFlow.drop(1).collect {
                        if (!fired) {
                            fired = true
                            inside.countDown()
                            // The consumer thread is inside the transition holding the lock; wait until the other thread has
                            // submitted its callback (submit never blocks), then call the player from inside the update.
                            if (!reported.await(10, TimeUnit.SECONDS)) throw AssertionError("the callback thread never returned from the callback: it blocked")
                            player.pause()
                        }
                    }
                }
                val callbackThread = Thread {
                    inside.await(10, TimeUnit.SECONDS)
                    val sink: VideoPlaybackEngineListener = checkNotNull(engine.sink)
                    engine.position = 10_000L
                    if (kind == "completed") sink.onCompleted() else sink.onFailed(cause)
                    reported.countDown()
                }
                callbackThread.start()
                player.seekTo(5_000L) // the write of Playing(5000) wakes the collector; it pauses; the callback is queued behind
                joinOrFail(callbackThread, "callback thread ($kind)")
                val expected: VideoPlayerState =
                    if (kind == "completed") VideoPlayerState.Completed(10_000L) else VideoPlayerState.Error(cause)
                assertEquals(expected, player.stateFlow.value, "$kind: the collector's pause stands and the callback lands on it afterwards; log ${engine.log.toList()}")
                assertTrue(!engine.started, "$kind: the pause is not undone, the engine stays stopped")
            } finally {
                scope.cancel()
                player.release()
            }
            assertTrue(engine.violations.isEmpty(), "$kind: ${engine.violations.toList()}")
        }
    }

    /** A load that ignores cancellation: unload and release return at once and the engine is freed only after the load returns. */
    @Test
    fun `a load that ignores cancellation is freed after it returns by unload and by release`() {
        for (finalCall in listOf("unload", "release")) {
            val engine = SerialEngine()
            val gate = CompletableDeferred<Unit>()
            val entered = CountDownLatch(1)
            engine.loadAction = {
                entered.countDown()
                withContext(NonCancellable) { gate.await() }
            }
            val player: VideoPlayer = newPlayer(engine)
            val prepared = CountDownLatch(1)
            val prepareThread = Thread {
                try {
                    runBlocking { player.prepare(source) }
                } catch (_: Throwable) {
                    // a cancelled prepare may rethrow; the state is judged below
                } finally {
                    prepared.countDown()
                }
            }
            try {
                prepareThread.start()
                assertTrue(entered.await(10, TimeUnit.SECONDS), "the load never started")
                assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
                // The platform keeps a reference to the listener it was given, as a real engine does.
                val sink: VideoPlaybackEngineListener = checkNotNull(engine.sink)
                if (finalCall == "unload") player.unload() else player.release()
                // Returned without waiting for the stuck load: the state is already Idle and the engine has not been freed yet.
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "$finalCall: Idle at once")
                assertEquals(0, engine.releases.get(), "$finalCall: the engine is not freed under a running load")
                // A callback from another thread while the load still runs (and the source is dropped): ignored.
                sink.onFailed(IllegalStateException("late"))
                sink.onBufferingChanged(true)
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "$finalCall: reports of a dropped source change nothing")
                gate.complete(Unit)
                assertTrue(prepared.await(10, TimeUnit.SECONDS), "$finalCall: prepare never returned after the load returned")
                assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "$finalCall: the abandoned prepare writes nothing")
                assertEquals(1, engine.releases.get(), "$finalCall: the engine is freed exactly once after the load returned; log ${engine.log.toList()}")
                if (finalCall == "unload") {
                    engine.loadAction = {}
                    runBlocking { withTimeout(10_000L) { player.prepare(source) } }
                    assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "the next prepare works")
                }
            } finally {
                gate.complete(Unit)
                player.release()
                joinOrFail(prepareThread, "prepare thread ($finalCall)")
            }
            assertEquals(1, engine.log.count { it == "dispose" }, "$finalCall: dispose exactly once")
            assertTrue(engine.violations.isEmpty(), "$finalCall: ${engine.violations.toList()}")
        }
    }

    /** Overlapping prepare, unload and play calls on two threads over loads that suspend, some ignoring cancellation, with callbacks on a third. */
    @Test
    fun `a lifecycle storm of prepare unload and callbacks on three threads leaves every load freed and the engine disposed once`() {
        val iterations: Int = System.getProperty("kmptoolkit.test.threads.iterations")?.toIntOrNull() ?: 80
        val seedBase: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 12_012L
        repeat(iterations) { iteration ->
            val seed: Long = seedBase + iteration
            val engine = SerialEngine()
            val loadRandom = Random(seed)
            engine.loadAction = {
                val rounds: Int = synchronized(loadRandom) { loadRandom.nextInt(6) }
                val stubborn: Boolean = synchronized(loadRandom) { loadRandom.nextInt(3) == 0 }
                repeat(rounds) {
                    if (stubborn) withContext(NonCancellable) { kotlinx.coroutines.yield() } else kotlinx.coroutines.yield()
                }
            }
            val player: VideoPlayer = newPlayer(engine)
            val escaped: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue()
            val barrier = CyclicBarrier(3)
            fun worker(name: String, salt: Long, body: (Random) -> Unit): Thread = Thread {
                try {
                    barrier.await(10, TimeUnit.SECONDS)
                    body(Random(seed xor salt))
                } catch (thrown: Throwable) {
                    escaped += AssertionError("$name: $thrown", thrown)
                }
            }
            val a = worker("lifecycle-a", 1L) { random ->
                repeat(40) {
                    when (random.nextInt(5)) {
                        0, 1 -> runBlocking { player.prepare(source) }
                        2 -> player.unload()
                        3 -> player.play()
                        else -> player.pause()
                    }
                }
            }
            val b = worker("lifecycle-b", 2L) { random ->
                repeat(40) {
                    when (random.nextInt(5)) {
                        0, 1 -> runBlocking { player.prepare(source) }
                        2 -> player.unload()
                        3 -> player.seekTo(random.nextLong(0L, 10_000L))
                        else -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                    }
                }
            }
            val c = worker("callbacks", 3L) { random ->
                repeat(200) {
                    // The listener is read afresh each time: unload keeps it, only the final release detaches it.
                    val sink: VideoPlaybackEngineListener = engine.sink ?: return@repeat
                    when (random.nextInt(4)) {
                        0 -> sink.onCompleted()
                        1 -> sink.onBufferingChanged(random.nextBoolean())
                        2 -> sink.onFailed(IllegalStateException("late"))
                        else -> sink.onVideoSizeChanged(VideoSize(64, 36))
                    }
                }
            }
            listOf(a, b, c).forEach { it.start() }
            listOf(a to "a", b to "b", c to "c").forEach { (thread, name) -> joinOrFail(thread, "storm thread $name (seed $seed)") }
            assertTrue(escaped.isEmpty(), "seed $seed: an exception escaped: ${escaped.toList()}")
            // Everything that was submitted behind a transition has run (the preparing threads have returned): settle and judge.
            runBlocking { withTimeout(10_000L) { player.prepare(source) } }
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "seed $seed: the next prepare after the storm; log ${engine.log.toList().takeLast(40)}")
            player.release()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(1, engine.log.count { it == "dispose" }, "seed $seed: dispose exactly once")
            assertTrue(engine.releases.get() >= engine.loads.get(), "seed $seed: ${engine.loads.get()} loads but only ${engine.releases.get()} releases: a load was never freed")
            assertTrue(engine.violations.isEmpty(), "seed $seed: engine contract broken by the player: ${engine.violations.toList()}")
        }
    }
}
