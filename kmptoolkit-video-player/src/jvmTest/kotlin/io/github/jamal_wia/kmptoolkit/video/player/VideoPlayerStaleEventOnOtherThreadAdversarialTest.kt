package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An engine thread reports an event of the old source while the player is dropping it (the report
 * returns before the engine's release() does, so it is not an event after release). The player is
 * mid-transition, so the report is queued and applied right after the transition that released the
 * source: the next prepare, already Preparing by then.
 */
class VideoPlayerStaleEventOnOtherThreadAdversarialTest {

    private class ThreadedEchoEngine(private val inner: RecordingVideoPlaybackEngine) : VideoPlaybackEngine by inner {
        @Volatile var fire: ((VideoPlaybackEngineListener) -> Unit)? = null
        override fun release() {
            val action = fire
            fire = null
            if (action != null) {
                val done = CountDownLatch(1)
                val thread = Thread {
                    try {
                        inner.listener?.let(action)
                    } finally {
                        done.countDown()
                    }
                }
                thread.isDaemon = true
                thread.start()
                assertTrue(done.await(5, TimeUnit.SECONDS), "the engine thread's report blocked on the player")
            }
            inner.release()
        }
    }

    private fun scenario(fire: (VideoPlaybackEngineListener) -> Unit, check: (VideoPlayer) -> Unit) = runBlocking {
        val inner = RecordingVideoPlaybackEngine()
        val engine = ThreadedEchoEngine(inner)
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig())
        try {
            withTimeout(10_000) {
                player.prepare(VideoSource.Asset("a.mp4"))
                player.play()
                engine.fire = fire
                player.prepare(VideoSource.Asset("b.mp4"))
            }
            check(player)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a failure reported by another thread while the old source is dropped does not fail the next prepare`() = scenario(
        fire = { it.onFailed(IllegalStateException("A died")) },
    ) { player ->
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value, "B was reported as ${player.stateFlow.value}")
    }

    @Test
    fun `a stall reported by another thread while the old source is dropped is not shown for the next prepare`() = scenario(
        fire = { it.onBufferingChanged(true) },
    ) { player ->
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
        assertEquals(false, player.isBufferingFlow.value, "B shows A's stall")
    }
}
