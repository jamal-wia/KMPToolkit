package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context handed to createVideoPlayer hosts the position poll only. A host whose one thread is
 * stuck (a starved pool, a blocked single-thread dispatcher) must not make any call of the player
 * wait: transport, prepare, unload and release all complete without that thread.
 */
class VideoPlayerStarvedDispatcherAdversarialTest {

    @Test
    fun `every call completes while the poll's only thread is blocked`() {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        val stuck = CountDownLatch(1)
        val running = CountDownLatch(1)
        executor.execute {
            running.countDown()
            stuck.await(60, TimeUnit.SECONDS)
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        val engine = RecordingVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), executor.asCoroutineDispatcher())
        try {
            runBlocking {
                withTimeout(10_000) {
                    player.prepare(VideoSource.Asset("a.mp4"))
                    player.play()
                    player.pause()
                    player.play()
                    player.seekTo(2_000L)
                    player.setPlaybackSpeed(2f)
                    player.stop()
                    player.replay()
                    player.unload()
                    player.prepare(VideoSource.Asset("b.mp4"))
                    player.play()
                }
            }
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing)
        } finally {
            player.release()
            stuck.countDown()
            executor.shutdownNow()
        }
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        assertEquals(1, engine.disposeCount)
    }
}
