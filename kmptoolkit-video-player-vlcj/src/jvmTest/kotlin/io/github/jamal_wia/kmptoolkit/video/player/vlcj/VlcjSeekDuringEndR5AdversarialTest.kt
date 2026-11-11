package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
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
 * Round 5, the core as an observer: the end of the source is reported by libvlc's event thread while a
 * seek is inside `set_time` (libvlc 3 makes `set_time` wait for a running event callback, probes
 * lock2.c to lock5.c, so a seek made as the end is being reported is in flight for as long as the
 * callback runs). A `set_time` on an input that has already ended does nothing (probe seekend5.c: the
 * state stays Ended, the clock stays at 0). The engine reports the seek's target as the playhead while
 * the seek is in flight, so the player drops the completion as one reported while a seek away from the
 * end is applied -- and then nothing ever reports the end again.
 *
 * The model is a libvlc whose `setTime` blocks until the test opens a gate and then leaves the input
 * ended, as the real one does.
 *
 * Oracle (ruling D-16, the seek wins): the player drops the completion as stale and asserts the start again;
 * the engine restarts the ended input, the player reads Playing at the seek's target, and the parked seek is
 * applied when libvlc reports Playing again. The scenario is the breaker's, unchanged; the breaker's first
 * oracle (Completed or Paused) contradicts the core's committed stale-completion contract.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjSeekDuringEndR5AdversarialTest {

    private val inputThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-input-thread").apply { isDaemon = true } }
    private val taskThread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "model-task-thread").apply { isDaemon = true } }
    private val inSetTime = CountDownLatch(1)
    private val gate = CountDownLatch(1)
    private var callbacks: VlcPlayerCallbacks? = null
    private val calls: MutableList<String> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        gate.countDown()
        inputThread.shutdownNow()
        taskThread.shutdownNow()
    }

    private inner class ModelPlayer : VlcNativePlayer {
        override fun prepare(mrl: String, options: List<String>): Boolean = true
        override fun parse(): Boolean {
            inputThread.execute { callbacks!!.parsed(VlcParseStatus.DONE) }
            return true
        }
        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = 2_000L, trackCount = 1, videoSize = null)
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun setTime(timeMs: Long) {
            calls += "setTime($timeMs)"
            inSetTime.countDown()
            gate.await(5L, TimeUnit.SECONDS)
            // The input has ended meanwhile: libvlc ignores the seek and nothing follows.
        }
        override fun time(): Long = 1_900L
        override fun setRate(rate: Float) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun rate(): Float = 1f
        override fun volume(): Int = 100
        override fun setRepeat(repeat: Boolean) = Unit
        override fun repeat(): Boolean = false
        override fun submit(task: () -> Unit) { taskThread.execute(task) }
        override fun stopParsing() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private val runtime = object : VlcRuntime {
        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            this@VlcjSeekDuringEndR5AdversarialTest.callbacks = callbacks
            return ModelPlayer()
        }
        override fun release() = Unit
    }

    private fun fire(event: VlcPlayerCallbacks.() -> Unit) {
        inputThread.submit { callbacks!!.event() }.get(5L, TimeUnit.SECONDS)
    }

    private fun awaitCalls(message: String, predicate: (List<String>) -> Boolean) {
        val deadline: Long = System.nanoTime() + 3_000L * 1_000_000L
        while (System.nanoTime() < deadline && !predicate(calls.toList())) Thread.sleep(10L)
        assertTrue(predicate(calls.toList()), "$message (calls $calls)")
    }

    private fun seekWhileTheEndIsReported(target: Long) {
        val player: VideoPlayer = createVlcjVideoPlayer(
            VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime }),
            VideoPlayerConfig(positionUpdateIntervalMs = 20L),
            Dispatchers.Default,
        )
        try {
            runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.File(TestClip.file.path)) } }
            player.play()
            fire { playing() }
            val seeker = thread(name = "seeker", isDaemon = true) { player.seekTo(target) }
            assertTrue(inSetTime.await(5L, TimeUnit.SECONDS), "the seek never reached libvlc")
            fire { finished() }
            gate.countDown()
            seeker.join(5_000L)
            assertTrue(!seeker.isAlive, "the seeking thread did not return")
            awaitCalls("the engine did not restart the ended input") { it == listOf("play", "setTime($target)", "play") }
            val state: VideoPlayerState = player.stateFlow.value
            assertTrue(
                state is VideoPlayerState.Playing && state.currentPosition == target,
                "libvlc ended and ignored the seek: the player must read Playing at $target, but reads $state (calls $calls)",
            )
            assertEquals(target, player.playbackPositionFlow.value)
            fire { playing() }
            awaitCalls("the parked seek was not applied after the replay") { it.size >= 4 && it.last() == "setTime($target)" }
            assertEquals(listOf("play", "setTime($target)", "play", "setTime($target)"), calls.toList())
        } finally {
            player.release()
        }
    }

    @Test
    fun `a seek in flight while the end is reported is honoured and the source plays on from its start`() {
        seekWhileTheEndIsReported(0L)
    }

    @Test
    fun `a seek in flight while the end is reported is honoured at its own target`() {
        seekWhileTheEndIsReported(1_000L)
    }
}
