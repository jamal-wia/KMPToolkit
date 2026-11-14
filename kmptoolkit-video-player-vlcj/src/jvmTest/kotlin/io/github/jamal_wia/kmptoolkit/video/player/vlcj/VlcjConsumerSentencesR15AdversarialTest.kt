package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, Q1, vlcj on its fake runtime: sentences of `docs/kmptoolkit-video-player-vlcj/03-guide.md` and `04-api-reference.md`,
 * each run through the shared `VideoPlayer`:
 * - "`unload` and `release` therefore return at once" while libvlc is still stopping a stalled input (guide, Lifecycle and threading);
 * - "`bufferedPositionFlow` ... the duration for a local file or asset ... and the playhead for a remote source" (guide, Buffering);
 * - "`isBufferingFlow` ... `true` while the cache refills during playback, `false` once it is full, paused or stopped" (guide);
 * - "A seek made in `Ready` is remembered and applied as playback starts" (guide, Readiness);
 * - "`videoSizeFlow` carries the picture size of the first video track" and "No picture is decoded before the first `play()`";
 * - "The player never reports `Completed` while looping" (guide, Looping).
 */
@OptIn(ToolkitInternalApi::class)
class VlcjConsumerSentencesR15AdversarialTest {

    private val runtime: FakeVlcRuntime = FakeVlcRuntime().also {
        it.parsedInfo = VlcParsedInfo(durationMs = 3_000L, trackCount = 1, videoSize = VideoSize(160, 90))
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newPlayer(): VideoPlayer = createVlcjVideoPlayer(
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime }),
        VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        Dispatchers.Default,
    )

    private fun prepare(player: VideoPlayer, source: VideoSource = VideoSource.File(TestClip.file.path)) {
        runBlocking { withTimeout(BOUND_MS) { player.prepare(source) } }
    }

    private fun outputBegins() {
        runtime.drainTasks()
        runtime.player.fire { playing() }
        runtime.drainTasks()
    }

    @Test
    fun `unload and release return at once while libvlc is still stopping a stalled input`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        outputBegins()
        val gate = CountDownLatch(1)
        runtime.player.stopGate = gate
        // The calls run on another thread and must return while libvlc's stop still waits at the gate: the fake's own wait on the gate
        // is longer than the bound here, so a call that waited for the stop would not return in time. No elapsed time is asserted.
        val unloaded: Boolean = returnsWhileTheGateIsClosed { player.unload() }
        assertTrue(unloaded, "unload did not return while libvlc was still stopping")
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        prepare(player)
        runtime.player.stopGate = gate
        player.play()
        outputBegins()
        val released: Boolean = returnsWhileTheGateIsClosed { player.release() }
        gate.countDown()
        assertTrue(released, "release did not return while libvlc was still stopping")
        withHang { runtime.released >= 1 }
        assertEquals(1, runtime.released)
    }

    private fun returnsWhileTheGateIsClosed(call: () -> Unit): Boolean {
        val done = CountDownLatch(1)
        Thread {
            call()
            done.countDown()
        }.apply { isDaemon = true; start() }
        return done.await(RETURN_BOUND_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun `the buffered position is the duration for a local file and the playhead for a remote source`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        assertEquals(3_000L, player.bufferedPositionFlow.value, "a local file is all there")
        player.play()
        outputBegins()
        player.pause()
        runtime.drainTasks()
        assertEquals(3_000L, player.bufferedPositionFlow.value, "a local file is all there after a pause")
        prepare(player, VideoSource.Remote("https://example.test/a.mp4"))
        player.seekTo(1_300L)
        player.play()
        outputBegins()
        player.pause()
        runtime.drainTasks()
        val state: VideoPlayerState = player.stateFlow.value
        assertTrue(state is VideoPlayerState.Paused, "state $state")
        assertEquals(state.currentPosition, player.bufferedPositionFlow.value, "a remote source shows the playhead")
        player.release()
    }

    @Test
    fun `buffering is true while the cache refills and false once it is full paused or stopped`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        outputBegins()
        val fake: FakeVlcPlayer = runtime.player
        assertEquals(false, player.isBufferingFlow.value)
        fake.fire { buffering(30f) }
        assertEquals(true, player.isBufferingFlow.value, "a refilling cache during playback")
        fake.fire { buffering(100f) }
        assertEquals(false, player.isBufferingFlow.value, "a full cache")
        fake.fire { buffering(30f) }
        fake.fire { paused() }
        assertEquals(false, player.isBufferingFlow.value, "paused ends buffering")
        fake.fire { buffering(30f) }
        fake.fire { stopped() }
        assertEquals(false, player.isBufferingFlow.value, "stopped ends buffering")
        player.release()
    }

    @Test
    fun `a seek made in Ready is applied when playback starts and not before`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.seekTo(1_200L)
        runtime.drainTasks()
        assertTrue(runtime.player.calls.none { it.startsWith("setTime") }, "a seek in Ready reached libvlc before output: ${runtime.player.calls}")
        assertEquals(1_200L, player.playbackPositionFlow.value)
        player.play()
        outputBegins()
        assertTrue("setTime(1200)" in runtime.player.calls, "the remembered seek never reached libvlc: ${runtime.player.calls}")
        assertEquals(1, runtime.player.calls.count { it.startsWith("setTime") }, "applied once: ${runtime.player.calls}")
        player.release()
    }

    @Test
    fun `the first video track size shows after prepare and no frame is decoded before the first play`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        assertEquals(VideoSize(160, 90), player.videoSizeFlow.value)
        assertTrue(runtime.player.calls.none { it == "play" }, "prepare started playback: ${runtime.player.calls}")
        player.unload()
        assertNull(player.videoSizeFlow.value, "unload clears the size")
        prepare(player)
        assertEquals(VideoSize(160, 90), player.videoSizeFlow.value)
        player.release()
        assertNull(player.videoSizeFlow.value, "release clears the size")
    }

    @Test
    fun `a looping player never reports Completed however many times the source ends`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.setRepeatMode(RepeatMode.One)
        player.play()
        outputBegins()
        repeat(25) {
            runtime.player.fire { finished() }
            runtime.drainTasks()
            runtime.player.fire { playing() }
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value} after end ${it + 1}")
        }
        player.setRepeatMode(RepeatMode.Off)
        runtime.drainTasks()
        runtime.player.fire { finished() }
        runtime.drainTasks()
        assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value, "a loop turned off ends at the next end")
        player.release()
    }

    private fun withHang(condition: () -> Boolean) {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a teardown that runs on the engine's own thread
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        while (!condition()) {
            // timing-hygiene-ignore: TH2 bound the real-time hang bound of a teardown that runs on the engine's own thread
            check(System.nanoTime() - deadline < 0L) { "the condition did not hold within $BOUND_MS ms" }
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val RETURN_BOUND_MS: Long = 2_000L
    }
}
