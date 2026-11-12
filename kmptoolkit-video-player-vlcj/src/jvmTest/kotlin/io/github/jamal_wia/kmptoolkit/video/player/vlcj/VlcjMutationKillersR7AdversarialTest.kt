package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7, mutation survivors of the round 5 and 6 VLC work, each killed by a scenario of its own:
 *
 * - the player's repeated `setSpeed` after it drops a stale completion while Playing (the shared player asserts
 *   `start()` and then the speed again, because a platform that stopped at its end starts at its own rate);
 * - the position of a source that has started but has no output yet is never read from libvlc's clock, which in
 *   that gap still holds the end of the cycle that ended (looping) or a stale reading (first start);
 * - a seek the listener makes from a callback while the pending seek is being applied at Playing is the playhead,
 *   not the older pending target the apply commits afterwards.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjMutationKillersR7AdversarialTest {

    private val input: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "r7m-input").apply { isDaemon = true } }
    private val tasks: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "r7m-tasks").apply { isDaemon = true } }
    private val gates: MutableMap<Long, CountDownLatch> = ConcurrentHashMap()
    private val entered: MutableMap<Long, CountDownLatch> = ConcurrentHashMap()
    private val calls: MutableList<String> = CopyOnWriteArrayList()
    @Volatile private var clock: Long = -1L
    @Volatile private var repeat: Boolean = false
    private var callbacks: VlcPlayerCallbacks? = null

    @AfterTest
    fun tearDown() {
        gates.values.forEach { it.countDown() }
        input.shutdownNow()
        tasks.shutdownNow()
    }

    private fun gate(target: Long) { gates[target] = CountDownLatch(1); entered[target] = CountDownLatch(1) }

    private inner class Model : VlcNativePlayer {
        override fun prepare(mrl: String, options: List<String>): Boolean = true
        override fun parse(): Boolean { input.execute { callbacks!!.parsed(VlcParseStatus.DONE) }; return true }
        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = 4_000L, trackCount = 1, videoSize = null)
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun setTime(timeMs: Long) {
            calls += "setTime($timeMs)"
            entered[timeMs]?.countDown()
            gates[timeMs]?.await(5L, TimeUnit.SECONDS)
        }
        override fun time(): Long = clock
        override fun setRate(rate: Float) { calls += "setRate($rate)" }
        override fun setVolume(percent: Int) = Unit
        override fun rate(): Float = 1f
        override fun volume(): Int = 100
        override fun setRepeat(repeat: Boolean) { this@VlcjMutationKillersR7AdversarialTest.repeat = repeat }
        override fun repeat(): Boolean = repeat
        override fun submit(task: () -> Unit) { tasks.execute(task) }
        override fun stopParsing() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private val runtime = object : VlcRuntime {
        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            this@VlcjMutationKillersR7AdversarialTest.callbacks = callbacks
            return Model()
        }
        override fun release() = Unit
    }

    private fun fire(event: VlcPlayerCallbacks.() -> Unit) { input.submit { callbacks!!.event() }.get(5L, TimeUnit.SECONDS) }

    private fun engine(): VlcjVideoEngine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })

    private fun load(engine: VlcjVideoEngine) = runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }

    @Test
    fun `a stale completion dropped while playing gives the player's speed to the engine again after the start`() {
        val player: VideoPlayer = createVlcjVideoPlayer(engine(), VideoPlayerConfig(positionUpdateIntervalMs = 20L), Dispatchers.Default)
        try {
            runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.File(TestClip.file.path)) } }
            player.setPlaybackSpeed(2f)
            player.play()
            fire { playing() }
            gate(1_000L)
            val seeker = thread(isDaemon = true) { player.seekTo(1_000L) }
            assertTrue(entered.getValue(1_000L).await(5L, TimeUnit.SECONDS), "the seek never reached libvlc")
            fire { finished() }
            gates.getValue(1_000L).countDown()
            seeker.join(5_000L)
            val until: Long = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < until && calls.count { it == "play" } < 2) Thread.sleep(10L)
            tasks.submit {}.get(5L, TimeUnit.SECONDS)
            val afterRestart: List<String> = calls.drop(calls.indexOfLast { it == "play" })
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertTrue(
                afterRestart.any { it == "setRate(2.0)" },
                "the restart after a dropped completion did not give the speed of 2 again: calls after the last play $afterRestart (all $calls)",
            )
        } finally {
            player.release()
        }
    }

    @Test
    fun `the position is never read from libvlc's clock before output begins, in a loop gap or after a first start`() {
        val engine: VlcjVideoEngine = engine()
        try {
            load(engine)
            // First start: the clock holds a stale reading until Playing.
            clock = 1_500L
            engine.start()
            assertEquals(0L, engine.positionMs(), "between start() and the first Playing the playhead is the start, not the stale clock")
            fire { playing() }
            tasks.submit {}.get(5L, TimeUnit.SECONDS)
            // A looping source wraps: the clock still reads the end of the cycle that ended.
            engine.setLooping(true)
            clock = 1_950L
            fire { finished() }
            tasks.submit {}.get(5L, TimeUnit.SECONDS)
            assertEquals(0L, engine.positionMs(), "in the loop gap the playhead is the start, not libvlc's reading of the cycle's end")
        } finally {
            engine.release()
            engine.dispose()
        }
    }

    @Test
    fun `a seek made from a callback while the pending seek is applied at Playing is the playhead after the apply commits`() {
        val engine: VlcjVideoEngine = engine()
        val listener = RecordingListener()
        engine.setListener(listener)
        try {
            load(engine)
            engine.seekTo(1_000L)
            engine.start()
            gate(1_000L)
            gate(2_000L)
            fire { playing() }
            assertTrue(entered.getValue(1_000L).await(5L, TimeUnit.SECONDS), "the pending seek was never applied")
            // The listener reacts to a buffering report by seeking, from inside the callback: counted and queued.
            listener.reaction = { event -> if (event == "buffering=true") engine.seekTo(2_000L) }
            fire { buffering(10f) }
            gates.getValue(1_000L).countDown()
            assertTrue(entered.getValue(2_000L).await(5L, TimeUnit.SECONDS), "the newer seek never reached libvlc")
            assertEquals(2_000L, engine.positionMs(), "while the newer seek is in flight its target is the playhead, not the older pending one")
            gates.getValue(2_000L).countDown()
        } finally {
            gates.values.forEach { it.countDown() }
            engine.release()
            engine.dispose()
        }
    }
}
