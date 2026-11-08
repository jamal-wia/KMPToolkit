package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ToolkitInternalApi::class)
class VlcjPendingSeekStaleReadingAdversarialTest {

    private val runtime = FakeVlcRuntime()

    @Volatile private var clock: Long = 1_000_000_000L

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = { true },
        createRuntime = { runtime },
        nanoTime = { clock },
    )

    private suspend fun loadClip(engine: VlcjVideoEngine) {
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
    }

    @Test
    fun `a seek made before the first start keeps its target over stale readings once output began`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.seekTo(1_500L) // before the first start: remembered, applied when output begins
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        assertTrue(player.calls.contains("setTime(1500)"), "the pending seek reached libvlc: ${player.calls}")

        // libvlc 3 applies set_time asynchronously (see the engine's own comment): the clock and the
        // events still tell the playback's beginning for a moment.
        player.playhead = 100L
        player.fire { timeChanged(100L) }

        assertEquals(1_500L, engine.positionMs(), "the target stands until libvlc reports a time near it")

        // The hold expires after 500 ms and does not freeze the playhead.
        clock += 600_000_000L
        player.playhead = 1_620L
        assertEquals(1_620L, engine.positionMs(), "the hold expired, the clock reading counts again")
        engine.dispose()
    }

    @Test
    fun `a seek made after the end keeps its target over stale readings once playback restarts`() = runBlocking<Unit> {
        val engine: VlcjVideoEngine = newEngine()
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        player.fire { finished() }
        engine.seekTo(1_500L) // the source has ended: pending
        engine.start()
        player.fire { playing() }
        runtime.drainTasks()
        player.playhead = 100L
        player.fire { timeChanged(100L) }

        assertEquals(1_500L, engine.positionMs(), "the target stands until libvlc reports a time near it")
        engine.dispose()
    }
}
