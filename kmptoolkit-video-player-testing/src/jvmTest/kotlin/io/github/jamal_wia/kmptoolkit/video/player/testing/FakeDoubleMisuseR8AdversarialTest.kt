package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Round 8: the scriptable fake used as a consumer's tool with hostile script values and wrong call orders. */
class FakeDoubleMisuseR8AdversarialTest {

    private fun player(engine: FakeVideoPlaybackEngine): VideoPlayer =
        createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 5L), Dispatchers.Default)

    @Test
    fun `a fake with an odd duration still gives a player that never reports a negative duration or position`() = runBlocking {
        withTimeout(30_000L) {
            for (duration: Long in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                val engine = FakeVideoPlaybackEngine(durationMs = duration)
                val p: VideoPlayer = player(engine)
                p.prepare(VideoSource.File("x"))
                p.play()
                engine.advancePositionBy(Long.MAX_VALUE); engine.advancePositionBy(Long.MIN_VALUE); engine.advancePositionTo(Long.MIN_VALUE)
                p.seekTo(Long.MAX_VALUE); p.seekForward(Long.MAX_VALUE); p.seekBackward(Long.MAX_VALUE)
                engine.completePlayback()
                val s: VideoPlayerState = p.stateFlow.value
                when (s) {
                    is VideoPlayerState.Ready -> assertTrue(s.duration >= 0L, "duration $duration: $s")
                    is VideoPlayerState.Playing -> assertTrue(s.duration >= 0L && s.currentPosition >= 0L, "duration $duration: $s")
                    is VideoPlayerState.Paused -> assertTrue(s.duration >= 0L && s.currentPosition >= 0L, "duration $duration: $s")
                    is VideoPlayerState.Completed -> assertTrue(s.duration >= 0L, "duration $duration: $s")
                    else -> Unit
                }
                assertTrue(p.playbackPositionFlow.value >= 0L, "position ${p.playbackPositionFlow.value}")
                p.release()
            }
        }
    }

    @Test
    fun `scripting the fake after release or without a player attached throws nothing`() = runBlocking {
        withTimeout(30_000L) {
            val lone = FakeVideoPlaybackEngine()
            lone.completePlayback(); lone.failPlayback(RuntimeException()); lone.reportBuffering(true)
            lone.reportVideoSize(VideoSize(1, 1)); lone.finishLoad(); lone.failLoad(RuntimeException())
            lone.advancePositionBy(5L); lone.release(); lone.release(); lone.dispose(); lone.dispose()
            val engine = FakeVideoPlaybackEngine()
            val p: VideoPlayer = player(engine)
            p.prepare(VideoSource.File("x"))
            p.release()
            engine.completePlayback(); engine.failPlayback(RuntimeException("late")); engine.reportBuffering(true)
            engine.reportVideoSize(VideoSize(2, 2)); engine.finishLoad(); engine.failLoad(RuntimeException())
            assertFalse(p.isBufferingFlow.value)
            assertEquals(null, p.videoSizeFlow.value)
            assertTrue(p.stateFlow.value !is VideoPlayerState.Error, "a late failure after release must not show: ${p.stateFlow.value}")
            val shared = FakeVideoPlaybackEngine()
            val a: VideoPlayer = player(shared)
            val b: VideoPlayer = player(shared) // the second player takes over the single listener slot
            a.prepare(VideoSource.File("x")); b.prepare(VideoSource.File("y"))
            shared.completePlayback()
            a.release(); b.release()
        }
    }
}
