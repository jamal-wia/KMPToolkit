package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A looping source restarts at its end without libvlc having a running input in between, so a seek
 * made there is parked like one made after start() (round 2, L1). Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLoopingRestartSeekAdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine =
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })

    private suspend fun loadClip(engine: VlcjVideoEngine) {
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
    }

    @Test
    fun `a seek made while a looping source restarts waits for the output of the restart`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.setLooping(true)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        // The end of a cycle: VLCJ's own handler replays the media, libvlc has no running input
        // until it reports Playing again - the situation the engine parks a seek for after start().
        player.fire { finished() }
        runtime.drainTasks()
        val before: Int = player.calls.size
        engine.seekTo(1_500L)
        runtime.drainTasks()
        assertFalse(player.calls.drop(before).contains("setTime(1500)"), "sent while the restart has no output: ${player.calls}")
        assertEquals(1_500L, engine.positionMs())
        player.fire { playing() }
        runtime.drainTasks()
        assertEquals(1, player.calls.count { it == "setTime(1500)" }, "applied once the restart has output: ${player.calls}")
        assertEquals(1_500L, engine.positionMs())
        engine.dispose()
    }

    @Test
    fun `a Playing of the ended cycle still queued does not reopen the gap`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.setLooping(true)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        val gate = CountDownLatch(1)
        try {
            player.submit { gate.await(5L, TimeUnit.SECONDS) }
            player.fire { playing() } // the ended cycle's Playing: its action queues behind the gate
            player.fire { finished() } // the reset queues behind it
            engine.seekTo(1_500L) // queues behind the reset
        } finally {
            gate.countDown()
        }
        runtime.drainTasks()
        assertFalse(player.calls.contains("setTime(1500)"), "sent in the gap: ${player.calls}")
        player.fire { playing() }
        runtime.drainTasks()
        assertEquals(1, player.calls.count { it == "setTime(1500)" }, "applied exactly once: ${player.calls}")
        engine.dispose()
    }

    @Test
    fun `a seek once the restart has output goes to libvlc at once`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.setLooping(true)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        player.fire { finished() }
        player.fire { playing() }
        runtime.drainTasks()
        engine.seekTo(800L)
        runtime.drainTasks()
        assertTrue(player.calls.contains("setTime(800)"), "parked although output exists: ${player.calls}")
        engine.dispose()
    }
}
