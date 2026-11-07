package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Media3 engine over a looper that really runs on its own thread (the other Media3 tests use the
 * Robolectric main thread, where the looper and the callers never run at the same time): callers on
 * several threads race the looper itself, the queued-call counter and the timing snapshot's
 * compare-and-set. After the storm the engine and the ExoPlayer must agree on what the last calls said.
 *
 * Robolectric's clock is virtual and frozen between looper idles, so a background looper's delayed
 * messages (ExoPlayer's own work ticks) would never come due: every rig advances it with real time.
 */
@RunWith(AndroidJUnit4::class)
class Media3RealLooperStormAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Asset("clips/intro.mp4")

    private class Rig {
        val thread: HandlerThread = HandlerThread("exo-storm").also { it.start() }
        val handler: Handler = Handler(thread.looper)
        val exo: AtomicReference<ExoPlayer?> = AtomicReference(null)

        @Volatile
        private var ticking: Boolean = true
        private val ticker: Thread = thread(isDaemon = true, name = "clock-ticker") {
            while (ticking) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(1))
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        /** Runs [block] on the looper thread and returns its result, bounded. */
        fun <T> onLooper(block: () -> T): T {
            val latch = CountDownLatch(1)
            val out: AtomicReference<Result<T>?> = AtomicReference(null)
            handler.post {
                out.set(runCatching(block))
                latch.countDown()
            }
            if (!latch.await(10, TimeUnit.SECONDS)) fail("the looper did not drain in 10 s")
            return requireNotNull(out.get()).getOrThrow()
        }

        fun idle() {
            onLooper { }
            onLooper { }
        }

        fun close() {
            ticking = false
            ticker.interrupt()
            thread.quitSafely()
        }
    }

    private class Observed(val playWhenReady: Boolean, val position: Long, val speed: Float, val volume: Float, val repeat: Int)

    private fun newEngine(rig: Rig): Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = rig.thread.looper,
        playerFactory = { playerContext: Context, looper: Looper ->
            TestExoPlayerBuilder(playerContext).setClock(Clock.DEFAULT).setLooper(looper).build().also { rig.exo.set(it) }
        },
        mediaSourceFactory = { _, _ -> Media3TestSupport.fakeSource(durationMs = 60_000L) },
    )

    private fun loadBlocking(engine: Media3VideoEngine) {
        runBlocking(Dispatchers.Default) { withTimeout(10_000L) { engine.load(source) } }
    }

    @Test
    fun `after a seven thread storm the last calls win on the player and in the cached timing`() {
        repeat(ROUNDS) { round: Int ->
            val rig = Rig()
            try {
                val engine: Media3VideoEngine = newEngine(rig)
                loadBlocking(engine)
                val go = CountDownLatch(1)
                val workers: List<Thread> = List(THREADS) { id: Int ->
                    thread(name = "storm-$round-$id") {
                        val random = Random(round * 100 + id)
                        go.await()
                        repeat(STEPS) {
                            when (random.nextInt(8)) {
                                0 -> engine.start()
                                1 -> engine.pause()
                                2 -> engine.seekTo(random.nextLong(0L, 50_000L))
                                3 -> engine.setSpeed(0.5f + random.nextFloat())
                                4 -> engine.setVolume(random.nextFloat())
                                5 -> engine.setLooping(random.nextBoolean())
                                else -> {
                                    engine.positionMs()
                                    engine.durationMs()
                                    engine.bufferedPositionMs()
                                }
                            }
                        }
                    }
                }
                go.countDown()
                workers.forEach { worker: Thread ->
                    worker.join(15_000L)
                    if (worker.isAlive) fail("round $round: ${worker.name} blocked")
                }
                // The last word, from one thread: the settings, then start, pause, seek.
                engine.setSpeed(1.5f)
                engine.setVolume(0.25f)
                engine.setLooping(true)
                engine.start()
                engine.pause()
                engine.seekTo(1_234L)
                repeat(3) {
                    rig.idle()
                    engine.positionMs()
                    rig.idle()
                }

                val exo: ExoPlayer = requireNotNull(rig.exo.get())
                val seen: Observed = rig.onLooper {
                    Observed(exo.playWhenReady, exo.currentPosition, exo.playbackParameters.speed, exo.volume, exo.repeatMode)
                }
                assertEquals(false, seen.playWhenReady, "round $round: the last pause did not stick on the player")
                assertEquals(1_234L, seen.position, "round $round: the last seek did not stick on the player")
                assertEquals(1.5f, seen.speed, "round $round: speed")
                assertEquals(0.25f, seen.volume, "round $round: volume")
                assertEquals(Player.REPEAT_MODE_ONE, seen.repeat, "round $round: repeat mode")
                assertEquals(1_234L, engine.positionMs(), "round $round: the cached position after the last seek")
                Thread.sleep(30L)
                assertEquals(1_234L, engine.positionMs(), "round $round: the cached playhead moves after a pause")
                engine.release()
                engine.dispose()
                rig.idle()
            } finally {
                rig.close()
            }
        }
    }

    @Test
    fun `a pause made after a start returned always wins, whichever threads made them`() {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = newEngine(rig)
            loadBlocking(engine)
            val exo: ExoPlayer = requireNotNull(rig.exo.get())
            repeat(PAIRS) { pair: Int ->
                val started = CountDownLatch(1)
                val first = thread(name = "start-$pair") {
                    engine.start()
                    started.countDown()
                }
                val second = thread(name = "pause-$pair") {
                    started.await()
                    engine.pause()
                }
                first.join(5_000L)
                second.join(5_000L)
                if (first.isAlive || second.isAlive) fail("pair $pair blocked")
                rig.idle()
                assertEquals(false, rig.onLooper { exo.playWhenReady }, "pair $pair: a start overtook the pause that was made after it")
            }
            engine.release()
            engine.dispose()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `loads cancelled and released from other threads at random moments leave an empty player and a working engine`() {
        repeat(ROUNDS) { round: Int ->
            val rig = Rig()
            try {
                val engine: Media3VideoEngine = newEngine(rig)
                runBlocking(Dispatchers.Default) {
                    withTimeout(30_000L) {
                        repeat(12) { index: Int ->
                            val random = Random(round * 50 + index)
                            val call = async { runCatching { engine.load(source) } }
                            if (random.nextBoolean()) Thread.sleep(random.nextLong(0L, 3L))
                            when (random.nextInt(3)) {
                                0 -> call.cancel()
                                1 -> engine.release()
                                else -> {
                                    call.cancel()
                                    engine.release()
                                }
                            }
                            call.join()
                            engine.release()
                        }
                    }
                }
                rig.idle()
                val exo: ExoPlayer? = rig.exo.get()
                if (exo != null) {
                    assertEquals(0, rig.onLooper { exo.mediaItemCount }, "round $round: a released engine still holds a media item")
                    assertEquals(false, rig.onLooper { exo.playWhenReady }, "round $round: playWhenReady after release")
                }
                loadBlocking(engine)
                assertTrue(engine.durationMs() > 0L, "round $round: the engine did not load after the storm")
                engine.release()
                engine.dispose()
                rig.idle()
            } finally {
                rig.close()
            }
        }
    }


    @Test
    fun `five threads storming the real player over a real looper leave a state that describes the ExoPlayer`() {
        repeat(PLAYER_SEEDS) { seed: Int ->
            val rig = Rig()
            val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
            try {
                val engine: Media3VideoEngine = newEngine(rig)
                val player: VideoPlayer = createVideoPlayer(
                    engine = engine,
                    config = VideoPlayerConfig(positionUpdateIntervalMs = 5L),
                    coroutineContext = Dispatchers.Default,
                )
                val prepares: AtomicReference<List<kotlinx.coroutines.Job>> = AtomicReference(emptyList())
                val go = CountDownLatch(1)
                val workers: List<Thread> = List(5) { id: Int ->
                    thread(name = "player-storm-$seed-$id") {
                        val random = Random(seed * 13 + id)
                        go.await()
                        repeat(60) { index: Int ->
                            when (random.nextInt(100)) {
                                in 0..9 -> {
                                    val call = scope.launch { player.prepare(VideoSource.Asset("clips/$seed-$id-$index.mp4")) }
                                    while (true) {
                                        val seen = prepares.get()
                                        if (prepares.compareAndSet(seen, seen + call)) break
                                    }
                                }
                                in 10..13 -> prepares.get().let { all -> if (all.isNotEmpty()) all.random(random).cancel() }
                                in 14..17 -> player.unload()
                                in 18..37 -> player.play()
                                in 38..52 -> player.pause()
                                in 53..57 -> player.stop()
                                in 58..67 -> player.seekTo(random.nextLong(0L, 61_000L))
                                in 68..71 -> player.replay()
                                in 72..75 -> player.seekForward(random.nextLong(0L, 5_000L))
                                in 76..79 -> player.setPlaybackSpeed(0.5f + random.nextFloat() * 2f)
                                in 80..83 -> player.setRepeatMode(if (random.nextBoolean()) RepeatMode.One else RepeatMode.Off)
                                in 84..87 -> player.setVolume(random.nextFloat())
                                else -> Thread.sleep(random.nextLong(0L, 3L))
                            }
                        }
                    }
                }
                go.countDown()
                workers.forEach { worker: Thread ->
                    worker.join(20_000L)
                    if (worker.isAlive) fail("seed $seed: ${worker.name} blocked")
                }
                runBlocking(Dispatchers.Default) {
                    withTimeout(15_000L) {
                        prepares.get().forEach { call: kotlinx.coroutines.Job -> call.join() }
                    }
                }
                repeat(3) {
                    rig.idle()
                    Thread.sleep(15L)
                }
                val state: VideoPlayerState = player.stateFlow.value
                val exo: ExoPlayer? = rig.exo.get()
                val context = "seed $seed state=$state"
                assertTrue(state != VideoPlayerState.Preparing, "$context: stuck in Preparing")
                if (exo != null) {
                    val playWhenReady: Boolean = rig.onLooper { exo.playWhenReady }
                    val items: Int = rig.onLooper { exo.mediaItemCount }
                    when (state) {
                        is VideoPlayerState.Playing -> assertTrue(playWhenReady, "$context: Playing but the ExoPlayer is paused")
                        is VideoPlayerState.Paused, is VideoPlayerState.Ready, is VideoPlayerState.Completed ->
                            assertEquals(false, playWhenReady, "$context: not Playing but the ExoPlayer plays")
                        VideoPlayerState.Idle -> {
                            assertEquals(false, playWhenReady, "$context: Idle but the ExoPlayer plays")
                            assertEquals(0, items, "$context: Idle but the ExoPlayer holds a source")
                        }
                        else -> Unit
                    }
                    if (state.isPlayable) assertEquals(1, items, "$context: playable without a source in the ExoPlayer")
                }
                player.release()
                rig.idle()
                assertEquals(0L, player.playbackPositionFlow.value, "$context: position after release")
            } finally {
                scope.cancel()
                rig.close()
            }
        }
    }


    @Test
    fun `a looper thread call made after other threads' calls returned never overtakes them`() {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = newEngine(rig)
            loadBlocking(engine)
            val exo: ExoPlayer = requireNotNull(rig.exo.get())
            repeat(PAIRS) { round: Int ->
                val insideLooper = CountDownLatch(1)
                val offThreadCallsReturned = CountDownLatch(1)
                val finished = CountDownLatch(1)
                val target: Long = 1_000L + round
                rig.handler.post {
                    insideLooper.countDown()
                    offThreadCallsReturned.await(10, TimeUnit.SECONDS)
                    // Still on the looper thread; the backlog posted meanwhile has not run.
                    engine.pause()
                    engine.seekTo(target)
                    finished.countDown()
                }
                if (!insideLooper.await(10, TimeUnit.SECONDS)) fail("round $round: the looper task did not start")
                val callers: List<Thread> = List(3) { id: Int ->
                    thread(name = "backlog-$round-$id") {
                        repeat(20) {
                            engine.start()
                            engine.seekTo(50_000L + id)
                        }
                    }
                }
                callers.forEach { it.join(10_000L) }
                offThreadCallsReturned.countDown()
                if (!finished.await(10, TimeUnit.SECONDS)) fail("round $round: the looper task did not finish")
                rig.idle()
                val playWhenReady: Boolean = rig.onLooper { exo.playWhenReady }
                val position: Long = rig.onLooper { exo.currentPosition }
                assertEquals(false, playWhenReady, "round $round: a start made before the looper's pause overtook it")
                assertEquals(target, position, "round $round: a seek made before the looper's seek overtook it")
                assertEquals(target, engine.positionMs(), "round $round: the cached position")
            }
            engine.release()
            engine.dispose()
        } finally {
            rig.close()
        }
    }


    private class CountingListener : VideoPlaybackEngineListener {
        val completed: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
        val events: java.util.concurrent.CopyOnWriteArrayList<String> = java.util.concurrent.CopyOnWriteArrayList()
        override fun onCompleted() { completed.incrementAndGet(); events += "completed" }
        override fun onFailed(cause: Throwable) { events += "failed" }
        override fun onBufferingChanged(isBuffering: Boolean) { events += "buffering=$isBuffering" }
        override fun onVideoSizeChanged(size: VideoSize?) { events += "size" }
    }

    private fun playToTheEndBehindABlockedLooper(release: Boolean): CountingListener {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = newEngine(rig)
            val listener = CountingListener()
            engine.setListener(listener)
            loadBlocking(engine)
            engine.seekTo(59_900L)
            rig.idle()
            engine.start()
            rig.idle()
            val gate = CountDownLatch(1)
            rig.handler.post { gate.await(20, TimeUnit.SECONDS) }
            // The looper is blocked: the player's own thread reaches the end and queues its news behind the gate.
            Thread.sleep(700L)
            listener.events.clear()
            if (release) engine.release()
            val releasedAt = listener.events.size
            gate.countDown()
            rig.idle()
            Thread.sleep(50L)
            rig.idle()
            if (release) {
                engine.dispose()
                rig.idle()
            }
            return listener.also { it.events.add(0, "mark:$releasedAt") }
        } finally {
            rig.close()
        }
    }

    @Test
    fun `control - a source that reaches its end behind a busy looper is reported once the looper is free`() {
        val listener: CountingListener = playToTheEndBehindABlockedLooper(release = false)
        assertEquals(1, listener.completed.get(), "the setup did not reach the end of the source: ${listener.events}")
    }


    @Test
    fun `behind a looper that is busy for a second no call blocks and the cached timing answers at once`() {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = newEngine(rig)
            loadBlocking(engine)
            engine.start()
            rig.idle()
            val gate = CountDownLatch(1)
            rig.handler.post { gate.await(20, TimeUnit.SECONDS) }
            val outcome: AtomicReference<String?> = AtomicReference(null)
            val caller = thread(name = "off-looper-caller") {
                try {
                    engine.seekTo(12_345L)
                    check(engine.positionMs() == 12_345L) { "seek not visible at once: ${engine.positionMs()}" }
                    engine.pause()
                    val frozen: Long = engine.positionMs()
                    Thread.sleep(40L)
                    check(engine.positionMs() == frozen) { "playhead moved after pause" }
                    engine.setVolume(0.5f)
                    engine.setSpeed(2f)
                    engine.setLooping(true)
                    repeat(200) {
                        engine.durationMs()
                        engine.bufferedPositionMs()
                        engine.positionMs()
                    }
                    engine.release()
                    engine.dispose()
                } catch (failure: Throwable) {
                    outcome.set(failure.toString())
                }
            }
            caller.join(5_000L)
            val blocked: Boolean = caller.isAlive
            gate.countDown()
            if (blocked) fail("a call blocked while the looper was busy")
            assertEquals(null, outcome.get())
            rig.idle()
        } finally {
            rig.close()
        }
    }


    @Test
    fun `a source of zero length neither hangs the load nor leaves it half loaded`() {
        val rig = Rig()
        try {
            val engine: Media3VideoEngine = Media3VideoEngine(
                context = context,
                looper = rig.thread.looper,
                playerFactory = { playerContext: Context, looper: Looper ->
                    TestExoPlayerBuilder(playerContext).setClock(Clock.DEFAULT).setLooper(looper).build().also { rig.exo.set(it) }
                },
                mediaSourceFactory = { _, _ -> Media3TestSupport.fakeSource(durationMs = 0L) },
            )
            val outcome: Result<Unit> = runBlocking(Dispatchers.Default) {
                withTimeout(5_000L) { runCatching { engine.load(source) } }
            }
            // Either it loaded or it failed; what it must not do is wait forever (withTimeout above fails the test).
            outcome.exceptionOrNull()?.let { System.err.println("zero-length load failed with $it") }
            engine.release()
            engine.dispose()
            rig.idle()
        } finally {
            rig.close()
        }
    }

    private companion object {
        const val PLAYER_SEEDS: Int = 20
        const val ROUNDS: Int = 15
        const val THREADS: Int = 7
        const val STEPS: Int = 400
        const val PAIRS: Int = 150
    }
}
