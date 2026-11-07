package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Lifecycle chaos and resource starvation against [FakeVlcRuntime]: a stuck teardown thread under a
 * load/release loop and a restart from the end driven from the event thread. Every wait is bounded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLifecycleChaosAdversarialTest {

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
    fun `a stuck teardown thread neither blocks loads nor loses a player nor frees libvlc early`() = runBlocking<Unit> {
        val gate = CountDownLatch(1)
        val engine: VlcjVideoEngine = newEngine()
        val cycles = 40
        try {
            repeat(cycles) {
                loadClip(engine)
                runtime.player.stopGate = gate
                engine.release()
            }
            loadClip(engine) // 40 teardowns are stuck behind the first one: this load must still end
            assertEquals(cycles + 1, runtime.players.size)
            engine.dispose()
            // Nothing may free libvlc while a player is still alive.
            assertEquals(0, runtime.released, "libvlc freed while teardowns were still pending")
        } finally {
            gate.countDown()
        }
        val deadline: Long = System.nanoTime() + 20_000_000_000L
        while (runtime.released == 0 && System.nanoTime() < deadline) Thread.sleep(20L)
        assertEquals(1, runtime.released, "libvlc was not freed exactly once: ${runtime.log.takeLast(5)}")
        for (player: FakeVlcPlayer in runtime.players) {
            assertEquals(1, player.calls.count { it == "release" }, "player released once: ${player.calls}")
        }
        val lastPlayerRelease: Int = runtime.log.indexOfLast { it.startsWith("release@") }
        assertTrue(runtime.log.indexOf("runtime.release@kmptoolkit-vlcj-teardown") > lastPlayerRelease, "libvlc freed before the last player")
        assertFailsWith<IllegalStateException> { loadClip(engine) }
        assertEquals(cycles + 1, runtime.players.size, "a load after dispose created a native player")
    }

    @Test
    fun `a restart from the end made on the event thread plays once and applies its seek once`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        listener.reaction = { event ->
            if (event == "completed") {
                engine.seekTo(0L)
                engine.start()
            }
        }
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        repeat(5) {
            player.fire { finished() }
            runtime.drainTasks()
            assertEquals(0L, engine.positionMs(), "the restart target is reported at once")
            player.fire { playing() }
            runtime.drainTasks()
        }
        assertEquals(6, player.calls.count { it == "play" }, "one play per start: ${player.calls}")
        assertEquals(5, player.calls.count { it == "setTime(0)" }, "one seek per restart: ${player.calls}")
        assertTrue(
            player.calls.withIndex().filter { it.value == "setTime(0)" }.all { (index, _) ->
                player.calls.take(index).last { it == "play" || it == "setTime(0)" } == "play"
            },
            "a seek went out before its restart's play: ${player.calls}",
        )
        engine.dispose()
    }

    @Test
    fun `calls queued behind a stalled task thread reach libvlc in order and none after release`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        // Stall VLCJ's task thread, then make the engine queue behind it: a Playing event runs its
        // work through the same ordered queue.
        val stall = CountDownLatch(1)
        runtime.taskThread.execute { stall.await(8L, java.util.concurrent.TimeUnit.SECONDS) }
        player.fire { playing() }
        val seeks: List<Long> = listOf(100L, 400L, 900L, 1_300L, 1_800L)
        seeks.forEach { engine.seekTo(it) }
        assertEquals(1_800L, engine.positionMs(), "the target is reported while its seeks are still queued")
        // Nothing may have reached libvlc yet: the queue is stalled and later calls must not overtake.
        assertTrue(player.calls.none { it.startsWith("setTime") }, "a seek overtook the queued work: ${player.calls}")
        stall.countDown()
        runtime.drainTasks()
        assertEquals(seeks.map { "setTime($it)" }, player.calls.filter { it.startsWith("setTime") }, "order lost: ${player.calls}")
        assertEquals(1_800L, engine.positionMs())

        // And once released, nothing queued may touch the native player any more.
        val stall2 = CountDownLatch(1)
        runtime.taskThread.execute { stall2.await(8L, java.util.concurrent.TimeUnit.SECONDS) }
        player.fire { playing() }
        repeat(50) { engine.seekTo(it * 10L) }
        engine.pause()
        engine.release()
        val atRelease: Int = player.calls.size
        stall2.countDown()
        runtime.drainTasks()
        val after: List<String> = player.calls.drop(atRelease).filter { !(it == "stopParsing" || it == "stop" || it == "release") }
        assertTrue(after.isEmpty(), "native calls after release returned: $after")
        engine.dispose()
    }
}
