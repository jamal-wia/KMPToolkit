package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every sentence of the fake's KDoc and of the testing guide, tested literally with edge inputs. */
class FakeEngineContractLawyerAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun TestScope.newPlayer(engine: FakeVideoPlaybackEngine): VideoPlayer = createVideoPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `the documented counts hold across unload cancelled load and release`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = newPlayer(engine)
        val loading = launch { player.prepare(source) }
        runCurrent()
        assertTrue(engine.isLoadPending)
        loading.cancel()
        loading.join()
        assertFalse(engine.isLoadPending, "a cancelled load must not stay pending")
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.releaseCount)

        engine.suspendLoads = false
        player.prepare(source)
        player.unload()
        assertEquals(2, engine.releaseCount)
        assertEquals(0, engine.disposeCount)
        assertTrue(engine.hasListener)
        player.prepare(source)
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)

        player.release()
        player.release()
        player.close()
        assertEquals(1, engine.disposeCount)
        assertFalse(engine.hasListener)
    }

    @Test
    fun `the documented position poll example reads the fake's playhead and buffer`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)
        player.prepare(source)
        player.play()
        engine.advancePositionBy(4_000)
        engine.bufferedPositionMs = 12_000
        advanceTimeBy(251)
        assertEquals(4_000L, player.playbackPositionFlow.value)
        assertEquals(12_000L, player.bufferedPositionFlow.value)
        player.release()
    }

    @Test
    fun `a completion reported on an idle or released player changes nothing`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = newPlayer(engine)
        engine.completePlayback()
        engine.failPlayback(IllegalStateException("late"))
        engine.reportBuffering(true)
        engine.reportVideoSize(VideoSize(4, 3))
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertEquals(null, player.videoSizeFlow.value)

        player.prepare(source)
        player.release()
        engine.completePlayback()
        engine.failPlayback(IllegalStateException("late"))
        engine.reportBuffering(true)
        engine.reportVideoSize(VideoSize(4, 3))
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertFalse(player.isBufferingFlow.value)
        assertEquals(null, player.videoSizeFlow.value)
    }

    @Test
    fun `a looping fake never completes and a stall reported while preparing is shown with ready`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = newPlayer(engine)
        player.setRepeatMode(RepeatMode.One)
        val loading = launch { player.prepare(source) }
        runCurrent()
        engine.reportBuffering(true)
        engine.finishLoad()
        loading.join()
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        assertTrue(player.isBufferingFlow.value)
        assertTrue(engine.isLooping)

        engine.reportBuffering(false)
        player.play()
        engine.advancePositionTo(9_000L)
        engine.completePlayback()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        assertEquals(0L, engine.positionMs)
        player.release()
    }

    @Test
    fun `a sticky load failure stays until cleared and a second finish or fail is harmless`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { loadFailure = IllegalStateException("403") }
        val player: VideoPlayer = newPlayer(engine)
        repeat(3) {
            player.prepare(source)
            assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        }
        assertEquals(3, engine.loadedSources.size)
        engine.finishLoad()
        engine.failLoad(IllegalStateException("x"))
        engine.loadFailure = null
        player.prepare(source)
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        player.release()
    }
}
