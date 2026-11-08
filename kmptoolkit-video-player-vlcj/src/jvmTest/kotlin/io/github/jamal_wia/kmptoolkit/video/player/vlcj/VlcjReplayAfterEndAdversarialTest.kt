package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * VLCJ resets an ended media itself: its internal ResetMediaEventHandler is registered before any
 * listener of ours and, on `finished`, submits `controls().stop()` to the task thread (VLCJ 4.12.1
 * ResetMediaEventHandler: "for some reason, we must invoke stop here to get the media to play again
 * with the subsequent play"). Until that task has run, libvlc 3.0.21's `play()` on an ended player is
 * a no-op (probe end2.c: state stays Ended, the clock stays put), and the late `stop` then stops
 * whatever the no-op left. A restart must therefore run behind that task, whichever thread asks.
 * The fake models the task as a blocked job on the task thread, queued before the engine hears of
 * the end.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjReplayAfterEndAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine =
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })

    private fun startAndPlay(engine: VlcjVideoEngine): FakeVlcPlayer {
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        return player
    }

    /** What VLCJ does on `finished`: a stop on its task thread, slow to run (libvlc's stop joins the input). */
    private fun queueVlcjReset(player: FakeVlcPlayer, gate: CountDownLatch) {
        player.submit {
            gate.await(5L, TimeUnit.SECONDS)
            player.calls += "vlcj-reset-stop"
        }
    }

    @Test
    fun `a restart from another thread right after the end waits behind VLCJ's reset of the ended media`() {
        val engine = newEngine()
        val player: FakeVlcPlayer = startAndPlay(engine)
        val gate = CountDownLatch(1)
        queueVlcjReset(player, gate)
        player.fire { finished() }

        // The app reacts at once, on its own thread (a collector of Completed, the core's own
        // RepeatMode.One restart): nothing on VLC's threads.
        engine.seekTo(0L)
        engine.start()

        val playsBeforeReset: Int = player.calls.count { it == "play" }
        gate.countDown()
        runtime.drainTasks()
        assertEquals(1, playsBeforeReset, "play() reached libvlc before VLCJ's reset of the ended media: ${player.calls}")
        val calls: List<String> = player.calls.toList()
        assertTrue(
            calls.indexOf("vlcj-reset-stop") < calls.lastIndexOf("play"),
            "the restart's play() must come after the reset stop: $calls",
        )
        assertEquals(emptyList(), errors.toList())
        engine.dispose()
    }

    @Test
    fun `a restart from inside the completion callback already waits behind the reset`() {
        val engine = newEngine()
        engine.setListener(object : VideoPlaybackEngineListener {
            override fun onCompleted() {
                engine.seekTo(0L)
                engine.start()
            }
            override fun onFailed(cause: Throwable) = Unit
            override fun onBufferingChanged(isBuffering: Boolean) = Unit
            override fun onVideoSizeChanged(size: VideoSize?) = Unit
        })
        val player: FakeVlcPlayer = startAndPlay(engine)
        val gate = CountDownLatch(1)
        queueVlcjReset(player, gate)
        player.fire { finished() }
        val playsBeforeReset: Int = player.calls.count { it == "play" }
        gate.countDown()
        runtime.drainTasks()
        assertEquals(1, playsBeforeReset, "play() ran before the reset: ${player.calls}")
        val calls: List<String> = player.calls.toList()
        assertTrue(calls.indexOf("vlcj-reset-stop") < calls.lastIndexOf("play"), "order: $calls")
        engine.dispose()
    }
}
