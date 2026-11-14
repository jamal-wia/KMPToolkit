package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 7, a mutation survivor of the round 5 fix of S5 (a seek in flight while the end is reported), at the
 * engine: two seeks are in flight when the end is reported -- the first inside `set_time`, the second made by the
 * listener from the completion callback (so it is only counted and queued, on the task thread, which is held
 * up). When the first seek's `set_time` returns, the input has ended and libvlc ignored it; the engine must not
 * report the FIRST target as the playhead while the newer seek is still waiting: the newest target is the
 * playhead, and the restart then plays from it.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjSeekEndOrderR7AdversarialTest {

    private val input: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "r7-input").apply { isDaemon = true } }
    private val tasks: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "r7-tasks").apply { isDaemon = true } }
    private val inSetTime = CountDownLatch(1)
    private val gate = CountDownLatch(1)
    private val taskBlock = CountDownLatch(1)
    private var callbacks: VlcPlayerCallbacks? = null
    private val calls: MutableList<String> = CopyOnWriteArrayList()

    @AfterTest
    fun tearDown() {
        gate.countDown()
        taskBlock.countDown()
        input.shutdownNow()
        tasks.shutdownNow()
    }

    private inner class Model : VlcNativePlayer {
        override fun prepare(mrl: String, options: List<String>): Boolean = true
        override fun parse(): Boolean {
            input.execute { callbacks!!.parsed(VlcParseStatus.DONE) }
            return true
        }
        override fun parsedInfo(): VlcParsedInfo = VlcParsedInfo(durationMs = 4_000L, trackCount = 1, videoSize = null)
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun setTime(timeMs: Long) {
            calls += "setTime($timeMs)"
            if (timeMs == 1_000L) {
                inSetTime.countDown()
                gate.await(5L, TimeUnit.SECONDS)
            }
        }
        override fun time(): Long = 3_900L
        override fun setRate(rate: Float) = Unit
        override fun setVolume(percent: Int) = Unit
        override fun rate(): Float = 1f
        override fun volume(): Int = 100
        override fun setRepeat(repeat: Boolean) = Unit
        override fun repeat(): Boolean = false
        override fun submit(task: () -> Unit) { tasks.execute(task) }
        override fun stopParsing() = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    private val runtime = object : VlcRuntime {
        override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
            this@VlcjSeekEndOrderR7AdversarialTest.callbacks = callbacks
            return Model()
        }
        override fun release() = Unit
    }

    private fun fire(event: VlcPlayerCallbacks.() -> Unit) {
        input.submit { callbacks!!.event() }.get(5L, TimeUnit.SECONDS)
    }

    @Test
    fun `the newest seek is the playhead while an older one, whose set_time libvlc ignored at the end, is committed`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        val listener = RecordingListener()
        engine.setListener(listener)
        try {
            runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
            engine.start()
            fire { playing() }
            tasks.submit {}.get(5L, TimeUnit.SECONDS)

            val first = thread(name = "r7-first-seeker", isDaemon = true) { engine.seekTo(1_000L) }
            assertTrue(inSetTime.await(5L, TimeUnit.SECONDS), "the first seek never reached libvlc")
            // Hold the task thread, so the second seek is decided only after the first one has committed.
            tasks.execute { taskBlock.await(10L, TimeUnit.SECONDS) }
            listener.reaction = { event -> if (event == "completed") engine.seekTo(2_000L) }
            fire { finished() }
            assertEquals(2_000L, engine.positionMs(), "the newest target is reported while both seeks are in flight")
            gate.countDown()
            first.join(5_000L)
            assertTrue(!first.isAlive, "the first seeker did not return")
            assertEquals(
                2_000L,
                engine.positionMs(),
                "the first seek (to 1000) committed while the newer one (to 2000) still waited: its target must not be the playhead",
            )
            taskBlock.countDown()
            tasks.submit {}.get(5L, TimeUnit.SECONDS)
            assertEquals(2_000L, engine.positionMs(), "after both seeks")
            // The restart applies the newest target when libvlc reports Playing again.
            engine.start()
            fire { playing() }
            tasks.submit {}.get(5L, TimeUnit.SECONDS)
            assertEquals("setTime(2000)", calls.last(), "calls $calls")
        } finally {
            engine.release()
            engine.dispose()
        }
    }
}
