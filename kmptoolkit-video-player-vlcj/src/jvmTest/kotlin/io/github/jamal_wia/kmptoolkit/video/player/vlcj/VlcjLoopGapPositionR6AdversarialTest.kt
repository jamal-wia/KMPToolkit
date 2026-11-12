package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6, found by the differential fuzzer: when a looping source reaches its end, VLCJ's own handler
 * replays it from 0 and libvlc has no clock until the restart reports Playing. The engine keeps the last
 * reading, which is the end, so for that gap `positionMs()` (and with it `seekForward()`, a pause or a poll
 * that lands in the gap) reports the end of the source although the playhead restarted at 0. Other engines
 * report 0 at the wrap.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjLoopGapPositionR6AdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    @Test
    fun `the playhead of a looping source reads 0 between the wrap and the output of the restart`() = runBlocking<Unit> {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
        engine.setLooping(true)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        player.fire { timeChanged(1_950L) }
        runtime.drainTasks()
        assertEquals(1_950L, engine.positionMs())
        player.fire { finished() }
        runtime.drainTasks()
        // The restart has no output yet: no Playing since the wrap.
        assertEquals(0L, engine.positionMs(), "the playhead after a loop wrap, before the restart has output")
        engine.dispose()
    }
}
