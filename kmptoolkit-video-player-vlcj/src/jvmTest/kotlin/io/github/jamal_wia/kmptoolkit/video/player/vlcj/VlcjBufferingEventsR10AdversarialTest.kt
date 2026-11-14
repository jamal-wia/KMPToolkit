package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt): the stall flag against libvlc's own events, through the shared player: a stall (Buffering below
 * 100 percent) shows while playing, and libvlc's Paused, Stopped, EndReached and Error each end it
 * (04-api-reference, isBufferingFlow: false on completion and on failure). Mutants that kept the flag in the engine's own
 * handlers of Paused or EndReached survived every other round-10 class.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjBufferingEventsR10AdversarialTest {

    private fun scenario(action: FakeVlcPlayer.() -> Unit, expectState: (VideoPlayerState) -> Boolean, label: String) {
        val runtime = FakeVlcRuntime()
        val file: File = File.createTempFile("r10-buffering", ".mp4").apply { writeBytes(ByteArray(8)); deleteOnExit() }
        val engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = { true },
            createRuntime = { runtime },
            teardown = Executor { it.run() },
        )
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 3_600_000L))
        try {
            runBlocking { withTimeout(5_000L) { player.prepare(VideoSource.File(file.path)) } }
            player.play()
            runtime.drainTasks()
            val native: FakeVlcPlayer = runtime.player
            native.fire { playing() }
            runtime.drainTasks()
            native.fire { buffering(10f) }
            runtime.drainTasks()
            assertTrue(player.isBufferingFlow.value, "$label: a stall while playing must show")
            native.action()
            runtime.drainTasks()
            Thread.sleep(30L)
            assertTrue(expectState(player.stateFlow.value), "$label: state ${player.stateFlow.value}")
            assertFalse(player.isBufferingFlow.value, "$label: the stall flag must be cleared")
        } finally {
            player.release()
            runtime.shutdown()
            file.delete()
        }
    }

    @Test
    fun `libvlc Paused ends a stall`() = scenario({ fire { paused() } }, { true }, "paused")

    @Test
    fun `libvlc Stopped ends a stall`() = scenario({ fire { stopped() } }, { true }, "stopped")

    @Test
    fun `libvlc EndReached ends a stall and completes`() = scenario({ fire { finished() } }, { it is VideoPlayerState.Completed }, "finished")

    @Test
    fun `libvlc Error ends a stall and fails`() = scenario({ fire { error() } }, { it is VideoPlayerState.Error }, "error")
}
