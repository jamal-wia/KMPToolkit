package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

@OptIn(ToolkitInternalApi::class)
class VlcjSeekBeforeOutputAdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    @Test
    fun `a seek made between start and the first output is applied again once output exists`() = runBlocking<Unit> {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
        engine.start() // play() issued, but libvlc has not reported Playing yet: no output
        engine.seekTo(1_500L) // the engine itself says libvlc accepts a seek only once the output exists
        val player: FakeVlcPlayer = runtime.player
        val before: Int = player.calls.size
        assertFalse(player.calls.take(before).contains("setTime(1500)"), "sent before output: ${player.calls}")
        assertEquals(1_500L, engine.positionMs())
        player.fire { playing() }
        runtime.drainTasks()
        assertTrue(
            player.calls.drop(before).contains("setTime(1500)"),
            "the seek was sent only before output existed and never again: ${player.calls}",
        )
        assertEquals(1, player.calls.count { it == "setTime(1500)" }, "applied exactly once: ${player.calls}")
        assertEquals(1_500L, engine.positionMs())
        engine.dispose()
    }

    @Test
    fun `a seek after a resume from pause is not parked waiting for another Playing`() = runBlocking<Unit> {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        engine.pause()
        engine.start() // a resume, not from rest: libvlc reports no fresh Playing
        engine.seekTo(900L)
        runtime.drainTasks()
        assertTrue(player.calls.contains("setTime(900)"), "the seek reached libvlc at once: ${player.calls}")
        engine.dispose()
    }
}
