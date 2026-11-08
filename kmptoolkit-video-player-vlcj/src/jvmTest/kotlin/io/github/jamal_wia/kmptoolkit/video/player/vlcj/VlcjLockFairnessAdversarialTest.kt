package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * libvlc's event thread must never wait long on the engine's lock: a callback blocked there stalls
 * libvlc's input thread (and so the picture). Eight threads hammer the getters a UI polls while the
 * event thread delivers readings; every delivery is timed.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLockFairnessAdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    @Test
    fun `event deliveries stay prompt while eight threads poll the getters in tight loops`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        val stop = AtomicBoolean(false)
        val hammers = List(8) {
            thread(name = "hammer-$it") {
                while (!stop.get()) {
                    engine.positionMs()
                    engine.durationMs()
                    engine.bufferedPositionMs()
                }
            }
        }
        var worstMs = 0L
        try {
            repeat(300) { i ->
                val started: Long = System.nanoTime()
                player.fire { timeChanged(1_000L + i) }
                worstMs = maxOf(worstMs, (System.nanoTime() - started) / 1_000_000L)
            }
        } finally {
            stop.set(true)
            hammers.forEach { it.join(10_000L) }
        }
        assertTrue(hammers.none { it.isAlive }, "a polling thread hung")
        assertTrue(worstMs < 500L, "an event delivery waited $worstMs ms behind polling threads")
        engine.dispose()
    }
}
