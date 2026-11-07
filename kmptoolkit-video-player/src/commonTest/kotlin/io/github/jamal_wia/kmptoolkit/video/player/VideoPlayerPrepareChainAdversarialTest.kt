package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Cancellation mid-operation in a chain of prepares: a load is unwinding in the engine, a second
 * prepare waits for it, a third replaces the second, and then the second's caller is cancelled. The
 * engine must still see one load at a time: the third may not start before the first has unwound.
 */
class VideoPlayerPrepareChainAdversarialTest {

    private val first: VideoSource = VideoSource.Remote("https://example.test/first.mp4")
    private val second: VideoSource = VideoSource.Remote("https://example.test/second.mp4")
    private val third: VideoSource = VideoSource.Remote("https://example.test/third.mp4")

    @Test
    fun `a waiting prepare that is replaced and then cancelled does not let the next load start under the first`() =
        runTest(timeout = 30.seconds) {
            val engine = RecordingVideoPlaybackEngine().apply {
                loadDelayMs = 1_000L
                unwindDelayMs = 500L
            }
            val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))

            launch { player.prepare(first) }
            runCurrent()
            val waiting: Job = launch { player.prepare(second) }
            runCurrent()
            launch { player.prepare(third) }
            runCurrent()
            // The second is superseded by the third and now its own caller goes away, while the
            // first is still unwinding.
            waiting.cancel()
            advanceUntilIdle()

            assertEquals(1, engine.maxConcurrentLoads, "the third load started while the first was still unwinding")
            assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
            player.release()
        }
}
