package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round 5 (lifecycle chaos + starvation + concurrency storm), core contract: what completion does to
 * the playhead and the length, and what a source that is still in the engine can say to the next
 * prepare.
 */
class VideoPlayerRound5AdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(engine, VideoPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    // --- Completion on a source whose length is unknown ---

    @Test
    fun `completion of a source of unknown length does not throw the playhead back to zero`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 45_000L
        engine.listener!!.onCompleted()
        assertIs<VideoPlayerState.Completed>(player.stateFlow.value)
        assertNotEquals(
            0L,
            player.playbackPositionFlow.value,
            "a live stream that played 45 s completed with the playhead at ${player.playbackPositionFlow.value}",
        )
    }

    @Test
    fun `a length known while playing is not lost when the engine reports zero at the end`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        assertEquals(10_000L, player.stateFlow.value.duration)
        engine.duration = 0L // some platforms drop the length when the item ends
        engine.position = 10_000L
        engine.listener!!.onCompleted()
        assertEquals(
            VideoPlayerState.Completed(10_000L),
            player.stateFlow.value,
            "the known length was replaced by ${player.stateFlow.value}",
        )
        assertEquals(10_000L, player.playbackPositionFlow.value)
    }

    @Test
    fun `control - a length first reported at the end is used by the completion`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.duration = 8_000L
        engine.position = 8_000L
        engine.listener!!.onCompleted()
        assertEquals(VideoPlayerState.Completed(8_000L), player.stateFlow.value)
        assertEquals(8_000L, player.playbackPositionFlow.value)
    }

    @Test
    fun `replay after an unknown-length completion keeps the end as the length until the platform reports one`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 0L),
    ) { engine, player ->
        player.prepare(source)
        player.play()
        engine.position = 45_000L
        engine.listener!!.onCompleted()
        assertEquals(VideoPlayerState.Completed(45_000L), player.stateFlow.value)
        assertEquals(45_000L, player.playbackPositionFlow.value)
        player.replay()
        assertEquals(VideoPlayerState.Playing(45_000L, 0L), player.stateFlow.value)
        engine.duration = 60_000L
        engine.position = 1_000L
        player.pause()
        assertEquals(VideoPlayerState.Paused(60_000L, 1_000L), player.stateFlow.value)
    }
}
