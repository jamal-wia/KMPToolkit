package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 4 on the unknown-length end (D-62 V3): the playhead of a source whose length libvlc never
 * reported stays where it ended, however the readers and the event thread interleave.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjUnknownLengthStormR4AdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun engineOfUnknownLength(): Pair<VlcjVideoEngine, FakeVlcPlayer> {
        runtime.parsedInfo = VlcParsedInfo(durationMs = 0L, trackCount = 1, videoSize = null)
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        return engine to player
    }

    @Test
    fun `readers on four threads never see the playhead of an unknown length source fall while it plays and ends`() {
        repeat(20) { round ->
            runtime.players.clear()
            val (engine, player) = engineOfUnknownLength()
            val random = Random(round)
            val stop = AtomicBoolean(false)
            val violations: MutableList<String> = CopyOnWriteArrayList()
            val readers: List<Thread> = List(4) {
                thread(name = "reader-$it") {
                    var last = 0L
                    while (!stop.get()) {
                        val now: Long = engine.positionMs()
                        if (now < last) violations += "reader saw $now after $last"
                        last = maxOf(last, now)
                    }
                }
            }
            var lastTime = 0L
            repeat(random.nextInt(5, 40)) {
                lastTime += random.nextLong(1L, 400L)
                player.fire { timeChanged(lastTime) }
            }
            player.fire { finished() }
            runtime.drainTasks()
            stop.set(true)
            readers.forEach { it.join(5_000L) }
            assertTrue(readers.none { it.isAlive }, "a reader is stuck")
            assertEquals(emptyList(), violations.toList(), "round $round")
            assertEquals(lastTime, engine.positionMs(), "round $round: the playhead at the end")
            assertEquals(0L, engine.durationMs())
            engine.dispose()
        }
    }

    @Test
    fun `an unknown length source that ends before any time was reported ends at zero without error`() {
        val (engine, player) = engineOfUnknownLength()
        player.fire { finished() }
        runtime.drainTasks()
        assertEquals(0L, engine.positionMs())
        engine.dispose()
    }

    @Test
    fun `a length learned after the end moves the ended playhead to that length`() {
        val (engine, player) = engineOfUnknownLength()
        player.fire { timeChanged(4_000L) }
        player.fire { finished() }
        assertEquals(4_000L, engine.positionMs())
        player.fire { lengthChanged(4_100L) }
        assertEquals(4_100L, engine.positionMs(), "once the length is known the end is the length")
        engine.dispose()
    }
}
