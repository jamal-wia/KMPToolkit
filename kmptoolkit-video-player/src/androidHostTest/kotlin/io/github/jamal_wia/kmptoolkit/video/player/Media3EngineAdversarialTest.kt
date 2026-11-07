package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.launchOnMain
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Main-thread confinement and re-initialisation of the Media3 engine under adversarial callers.
 *
 * ExoPlayer lives on the main looper; the engine promises that any thread may call it, that calls
 * keep each caller's order, that nothing waits for the main thread, and that nothing is reported
 * once the engine has been released. Every wait on another thread here is bounded and fails with a
 * filtered thread dump, so a deadlock is a quick, readable failure.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3EngineAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source: VideoSource = VideoSource.Asset("clips/intro.mp4")

    /** An ExoPlayer that records, in the order they reach it on the looper, the positions it was asked to seek to. */
    private class SeekRecordingPlayer(
        private val delegate: ExoPlayer,
        val seeks: MutableList<Long>,
    ) : ExoPlayer by delegate {
        override fun seekTo(positionMs: Long) {
            seeks += positionMs
            delegate.seekTo(positionMs)
        }
    }

    private fun recordingEngine(
        seeks: MutableList<Long>,
        created: (ExoPlayer) -> Unit = {},
    ): Media3VideoEngine = Media3VideoEngine(
        context = context,
        looper = Looper.getMainLooper(),
        playerFactory = { playerContext: Context, looper: Looper ->
            val real: ExoPlayer = TestExoPlayerBuilder(playerContext).setLooper(looper).build()
            created(real)
            SeekRecordingPlayer(real, seeks)
        },
        mediaSourceFactory = { _, _ -> Media3TestSupport.fakeSource() },
    )

    private fun dump(): String = Thread.getAllStackTraces().entries
        .filter { (thread: Thread, _) ->
            DUMP_PREFIXES.any { thread.name.startsWith(it) }
        }
        .joinToString("\n") { (thread: Thread, frames: Array<StackTraceElement>) ->
            "${thread.name} [${thread.state}]\n" + frames.take(DUMP_FRAMES).joinToString("\n") { "    at $it" }
        }

    private fun joinOrFail(what: String, vararg threads: Thread) {
        val deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOIN_MS)
        for (worker: Thread in threads) {
            worker.join(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L))
            if (worker.isAlive) fail("$what: ${worker.name} is still blocked after ${JOIN_MS} ms\n${dump()}")
        }
    }

    // --- Main-thread confinement ---

    @Test
    fun `eight threads storming seek pause play and volume never hang, keep each thread's order and lose no call`() {
        val seeks: MutableList<Long> = mutableListOf()
        var exo: ExoPlayer? = null
        val engine: Media3VideoEngine = recordingEngine(seeks) { exo = it }
        runSuspending { engine.load(source) }.getOrThrow()
        seeks.clear()

        val go = CountDownLatch(1)
        val workers: List<Thread> = List(THREADS) { id: Int ->
            thread(name = "storm-$id") {
                go.await()
                for (step: Int in 0 until STEPS) {
                    engine.seekTo(id * STRIDE + step)
                    when (step % 4) {
                        0 -> engine.pause()
                        1 -> engine.start()
                        2 -> engine.setVolume((step % 10) / 10f)
                        else -> engine.positionMs()
                    }
                }
            }
        }
        go.countDown()

        // The main looper is idled in a loop meanwhile, as a live app's would be.
        val deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STORM_BUDGET_MS)
        while (workers.any { it.isAlive }) {
            ShadowLooper.idleMainLooper()
            if (System.nanoTime() > deadline) fail("the storm did not finish in $STORM_BUDGET_MS ms\n${dump()}")
            Thread.sleep(1L)
        }
        joinOrFail("storm", *workers.toTypedArray())
        repeat(3) { ShadowLooper.idleMainLooper() }

        assertEquals(THREADS * STEPS, seeks.size, "every seekTo must reach the player exactly once")
        for (id: Int in 0 until THREADS) {
            val mine: List<Long> = seeks.filter { it / STRIDE == id.toLong() }.map { it % STRIDE }
            assertEquals((0 until STEPS).map { it.toLong() }, mine, "thread $id: its seeks overtook one another")
        }

        // The refresh must not have left a stale picture of the player behind once things went quiet.
        engine.pause()
        ShadowLooper.idleMainLooper()
        thread { engine.positionMs() }.also { joinOrFail("refresh trigger", it) }
        ShadowLooper.idleMainLooper()
        var reported = -1L
        thread { reported = engine.positionMs() }.also { joinOrFail("final read", it) }
        assertEquals(requireNotNull(exo).currentPosition, reported, "the cached position disagrees with the player")

        // Nothing is left counted as queued: a call on the looper thread now runs at once.
        engine.setVolume(0.33f)
        assertEquals(0.33f, requireNotNull(exo).volume, "a looper-thread call was held behind phantom queued work")

        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    // --- Release against queued and off-looper callers ---

    @Test
    fun `release on the looper thread behind queued off-looper calls returns at once and nothing is reported after it`() {
        repeat(CYCLES) { cycle: Int ->
            val listener = RecordingEngineListener()
            var exo: ExoPlayer? = null
            val engine: Media3VideoEngine = recordingEngine(mutableListOf()) { exo = it }
            engine.setListener(listener)
            runSuspending { engine.load(source) }.getOrThrow()

            val caller: Thread = thread(name = "queued-$cycle") {
                repeat(QUEUED_CALLS) { step: Int ->
                    engine.seekTo(step * 10L)
                    engine.start()
                    engine.pause()
                }
                engine.start()
            }
            joinOrFail("cycle $cycle queueing", caller)

            // The main looper has not run any of it: release and dispose queue behind the calls.
            engine.release()
            engine.dispose()
            ShadowLooper.idleMainLooper()

            assertTrue(requireNotNull(exo).isReleased, "cycle $cycle: the ExoPlayer was not released")
            assertEquals(0L, engine.positionMs(), "cycle $cycle: position after release")
            assertNull(engine.playerForSurface(), "cycle $cycle: a disposed engine handed out a player")
            val settledCompletions: Int = listener.completions.size
            val settledFailures: Int = listener.failures.size
            val settledBuffering: Int = listener.buffering.size
            repeat(10) { ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS) }
            assertEquals(settledCompletions, listener.completions.size, "cycle $cycle: completion after release")
            assertEquals(settledFailures, listener.failures.size, "cycle $cycle: failure after release")
            assertEquals(settledBuffering, listener.buffering.size, "cycle $cycle: buffering after release")
            assertTrue(listener.completions.isEmpty(), "cycle $cycle: the source ended although it was released first")
        }
    }

    @Test
    fun `release from another thread while the main looper is paused does not wait for it`() {
        repeat(CYCLES) { cycle: Int ->
            var exo: ExoPlayer? = null
            val engine: Media3VideoEngine = recordingEngine(mutableListOf()) { exo = it }
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
            runSuspending { player.prepare(source) }.getOrThrow()
            player.play()

            // Main is not pumping; a deadlock against it would never return.
            val releaser: Thread = thread(name = "releaser-$cycle") {
                player.release()
                player.release()
                player.play()
                player.seekTo(1_000L)
            }
            joinOrFail("cycle $cycle release", releaser)
            ShadowLooper.idleMainLooper()

            assertTrue(requireNotNull(exo).isReleased, "cycle $cycle: the ExoPlayer was not released")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "cycle $cycle: state after release")
            assertNull(player.media3PlayerOrNull(), "cycle $cycle: a released player still has a Media3 player")
        }
    }

    @Test
    fun `release on the main thread while a prepare is parked on the looper unwinds the prepare and releases once`() {
        repeat(CYCLES) { cycle: Int ->
            var exo: ExoPlayer? = null
            val neverPrepares = FakeMediaSource().apply { setAllowPreparation(false) }
            val engine: Media3VideoEngine = Media3TestSupport.newEngine(
                context,
                created = { exo = it },
                mediaSources = { _, _ -> neverPrepares },
            )
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
            val loading: Job = launchOnMain { player.prepare(source) }
            runMainLooperUntil { exo?.mediaItemCount == 1 }

            player.release()
            runMainLooperUntil { loading.isCompleted }
            ShadowLooper.idleMainLooper()

            assertTrue(requireNotNull(exo).isReleased, "cycle $cycle: the ExoPlayer was not released")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "cycle $cycle: state after release")
        }
    }

    // --- Re-initialisation ---

    @Test
    fun `thirty create prepare play release cycles release every ExoPlayer and leave no playback thread behind`() {
        val created: MutableList<ExoPlayer> = mutableListOf()
        val baseline: Int = playbackThreads()

        repeat(CYCLES_REINIT) { cycle: Int ->
            val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { created += it })
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
            assertTrue(player.media3PlayerOrNull() != null, "cycle $cycle: no surface player before prepare")
            runSuspending { player.prepare(source) }.getOrThrow()
            player.play()
            player.release()
            ShadowLooper.idleMainLooper()
            assertNull(player.media3PlayerOrNull(), "cycle $cycle: a Media3 player survived release")
        }

        assertEquals(CYCLES_REINIT, created.size, "one ExoPlayer per player: created")
        assertEquals(CYCLES_REINIT, created.count { it.isReleased }, "ExoPlayers released")

        // A player that is released before anything asked for its ExoPlayer must not build one.
        repeat(CYCLES_REINIT) {
            val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { created += it })
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined)
            player.release()
            ShadowLooper.idleMainLooper()
        }
        assertEquals(CYCLES_REINIT, created.size, "releasing an untouched player built an ExoPlayer")

        // ExoPlayer's playback thread exits asynchronously after release; allow it a bounded settle.
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_S)
        while (playbackThreads() > baseline && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(20L)
        }
        assertTrue(
            playbackThreads() <= baseline,
            "playback threads ${playbackThreads()} did not return to the baseline $baseline\n${dump()}",
        )
    }

    // --- More attacks ---

    @Test
    fun `sixteen threads polling the position while the looper runs hang nothing and leave no refresh queued`() {
        var exo: ExoPlayer? = null
        val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { exo = it })
        runSuspending { engine.load(source) }.getOrThrow()
        ShadowLooper.idleMainLooper()

        val go = CountDownLatch(1)
        val workers: List<Thread> = List(16) { id: Int ->
            thread(name = "storm-poll-$id") {
                go.await()
                repeat(300) {
                    engine.positionMs()
                    engine.durationMs()
                    engine.bufferedPositionMs()
                }
            }
        }
        go.countDown()
        val deadline: Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STORM_BUDGET_MS)
        while (workers.any { it.isAlive }) {
            ShadowLooper.idleMainLooper()
            if (System.nanoTime() > deadline) fail("the polling storm did not finish\n${dump()}")
            Thread.sleep(1L)
        }
        joinOrFail("poll storm", *workers.toTypedArray())
        ShadowLooper.idleMainLooper()

        assertEquals(Media3TestSupport.SOURCE_DURATION_MS, engine.durationMs())
        assertTrue(org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).isIdle, "refresh tasks are still queued")
        assertEquals(0L, requireNotNull(exo).currentPosition)
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `fifty load release load cycles keep one ExoPlayer with at most one item and end ready`() {
        var exo: ExoPlayer? = null
        val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { exo = it })
        runSuspending { engine.load(source) }.getOrThrow()
        val first: ExoPlayer = requireNotNull(exo)

        repeat(50) { cycle: Int ->
            engine.release()
            ShadowLooper.idleMainLooper()
            assertEquals(0, first.mediaItemCount, "cycle $cycle: item left after release")
            runSuspending { engine.load(source) }.getOrThrow()
            assertEquals(1, first.mediaItemCount, "cycle $cycle: items after load")
        }

        assertTrue(first === exo, "a second ExoPlayer was built")
        assertEquals(androidx.media3.common.Player.STATE_READY, first.playbackState)
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
        assertTrue(first.isReleased)
    }

    @Test
    fun `thirty loads cancelled while parked leave the engine able to load a real source`() {
        var exo: ExoPlayer? = null
        var stall = true
        val neverPrepares = FakeMediaSource().apply { setAllowPreparation(false) }
        val engine: Media3VideoEngine = Media3TestSupport.newEngine(
            context,
            created = { exo = it },
            mediaSources = { _, _ -> if (stall) neverPrepares else Media3TestSupport.fakeSource() },
        )

        repeat(30) { cycle: Int ->
            val loading: Job = launchOnMain { engine.load(source) }
            runMainLooperUntil { exo?.mediaItemCount == 1 }
            loading.cancel()
            runMainLooperUntil { loading.isCompleted }
            ShadowLooper.idleMainLooper()
            assertEquals(0, requireNotNull(exo).mediaItemCount, "cycle $cycle: item left after a cancelled load")
        }
        stall = false
        runSuspending { engine.load(source) }.getOrThrow()

        assertEquals(androidx.media3.common.Player.STATE_READY, requireNotNull(exo).playbackState)
        engine.release()
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `dispose while a load is parked ends the load and a later load fails fast`() {
        repeat(CYCLES) { cycle: Int ->
            var exo: ExoPlayer? = null
            val neverPrepares = FakeMediaSource().apply { setAllowPreparation(false) }
            val engine: Media3VideoEngine = Media3TestSupport.newEngine(
                context,
                created = { exo = it },
                mediaSources = { _, _ -> neverPrepares },
            )
            val loading: Job = launchOnMain { engine.load(source) }
            runMainLooperUntil { exo?.mediaItemCount == 1 }

            engine.release()
            engine.dispose()
            runMainLooperUntil { loading.isCompleted }
            ShadowLooper.idleMainLooper()

            assertTrue(requireNotNull(exo).isReleased, "cycle $cycle: not released")
            assertTrue(
                runSuspending { engine.load(source) }.exceptionOrNull() is IllegalStateException,
                "cycle $cycle: a load on a disposed engine did not fail fast",
            )
        }
    }

    @Test
    fun `eight threads asking for the surface player before it exists get none and the looper thread then builds exactly one`() {
        val built: MutableList<ExoPlayer> = mutableListOf()
        val engine: Media3VideoEngine = Media3TestSupport.newEngine(context, created = { built += it })
        val go = CountDownLatch(1)
        val answers: MutableList<ExoPlayer?> = java.util.Collections.synchronizedList(mutableListOf())
        val workers: List<Thread> = List(THREADS) { id: Int ->
            thread(name = "storm-surface-$id") {
                go.await()
                repeat(100) { answers += engine.playerForSurface() }
            }
        }
        go.countDown()
        joinOrFail("surface storm", *workers.toTypedArray())

        assertTrue(answers.all { it == null }, "an off-looper caller was handed a player or built one")
        assertEquals(0, built.size, "an ExoPlayer was built off the looper thread")
        val onMain: ExoPlayer? = engine.playerForSurface()
        assertEquals(1, built.size)
        assertTrue(onMain === engine.playerForSurface())
        assertTrue(offLooperRead(engine) === onMain, "an off-looper caller does not see the player the looper built")
        engine.dispose()
        ShadowLooper.idleMainLooper()
    }

    private fun offLooperRead(engine: Media3VideoEngine): ExoPlayer? {
        var result: ExoPlayer? = null
        thread { result = engine.playerForSurface() }.also { joinOrFail("surface read", it) }
        return result
    }

    private fun playbackThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith("ExoPlayer:") }

    private companion object {
        const val THREADS: Int = 8
        const val STEPS: Int = 200
        const val STRIDE: Long = 100_000L
        const val STORM_BUDGET_MS: Long = 20_000L
        const val JOIN_MS: Long = 5_000L
        const val CYCLES: Int = 25
        const val CYCLES_REINIT: Int = 30
        const val QUEUED_CALLS: Int = 40
        const val SETTLE_S: Long = 5L
        const val DUMP_FRAMES: Int = 12
        val DUMP_PREFIXES: List<String> =
            listOf("kmptoolkit-", "main", "DefaultDispatcher-", "JavaFX", "ExoPlayer", "storm-", "queued-", "releaser-")
    }
}
