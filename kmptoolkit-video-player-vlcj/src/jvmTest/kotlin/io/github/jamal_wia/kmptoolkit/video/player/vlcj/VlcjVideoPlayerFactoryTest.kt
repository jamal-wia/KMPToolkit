package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.frameSourceOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The player `createVlcjVideoPlayer` builds, through the shared state machine — no VLC needed. */
@OptIn(ToolkitInternalApi::class)
class VlcjVideoPlayerFactoryTest {

    private val discoveries = AtomicInteger()
    private val runtimeCreations = AtomicInteger()

    private fun playerOver(runtime: FakeVlcRuntime?, vlcFound: Boolean): VideoPlayer = createVlcjVideoPlayer(
        engine = VlcjVideoEngine(
            vlcArgs = emptyList(),
            discover = {
                discoveries.incrementAndGet()
                vlcFound
            },
            createRuntime = {
                runtimeCreations.incrementAndGet()
                checkNotNull(runtime) { "libvlc must not be touched" }
            },
        ),
        config = VideoPlayerConfig(),
        coroutineContext = Dispatchers.Default,
    )

    @Test
    fun `createVlcjVideoPlayer never throws and exposes a frame source`() {
        val player: VideoPlayer = createVlcjVideoPlayer(vlcArgs = listOf("--no-such-option"))
        try {
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNotNull(player.frameSourceOrNull())
        } finally {
            player.release()
        }
    }

    @Test
    fun `creating and releasing a player neither looks for VLC nor starts libvlc`() {
        val player: VideoPlayer = playerOver(runtime = null, vlcFound = false)

        assertNotNull(player.frameSourceOrNull())
        player.release()

        assertEquals(0, discoveries.get())
        assertEquals(0, runtimeCreations.get())
    }

    @Test
    fun `without VLC prepare settles on Error carrying VlcUnavailableException`() = runBlocking<Unit> {
        val player: VideoPlayer = playerOver(runtime = null, vlcFound = false)
        try {
            player.prepare(VideoSource.File(TestClip.file.path))

            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state)
            assertIs<VlcUnavailableException>(state.cause)
            assertEquals(0, runtimeCreations.get())
        } finally {
            player.release()
        }
    }

    @Test
    fun `a seek after completion leaves the player paused and VLC stopped`() = runBlocking<Unit> {
        val runtime = FakeVlcRuntime()
        val player: VideoPlayer = playerOver(runtime, vlcFound = true)
        try {
            player.prepare(VideoSource.File(TestClip.file.path))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            player.play()
            val native: FakeVlcPlayer = runtime.player
            native.fire { playing() }
            native.fire { finished() }
            player.awaitState { it is VideoPlayerState.Completed }
            native.calls.clear()

            player.seekTo(500L)
            delay(200L)

            assertEquals(VideoPlayerState.Paused(duration = 2_000L, currentPosition = 500L), player.stateFlow.value)
            assertTrue("play" !in native.calls, "${native.calls}")

            player.play()
            assertEquals("play", native.calls.first())
            assertEquals(1, native.calls.count { it == "play" })
        } finally {
            player.release()
            runtime.shutdown()
        }
    }

    @Test
    fun `the frame source is the engine's and outlives unload`() = runBlocking<Unit> {
        val runtime = FakeVlcRuntime()
        val player: VideoPlayer = playerOver(runtime, vlcFound = true)
        try {
            val source = player.frameSourceOrNull()
            player.prepare(VideoSource.File(TestClip.file.path))
            player.unload()

            assertSame(source, player.frameSourceOrNull())
            assertEquals(1, runtimeCreations.get())
        } finally {
            player.release()
            runtime.shutdown()
        }
    }

    private suspend fun VideoPlayer.awaitState(condition: (VideoPlayerState) -> Boolean) {
        withTimeout(5_000L) {
            while (!condition(stateFlow.value)) delay(10L)
        }
    }
}
