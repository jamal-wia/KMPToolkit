package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.isPlayable
import io.github.jamal_wia.kmptoolkit.video.player.isPlaying
import io.github.jamal_wia.kmptoolkit.video.player.progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 8: the examples of docs/kmptoolkit-video-player/06-testing.md and 02-getting-started.md, written out as the
 * documents show them (with the consumer's own types replaced by the state they would hold), compile and give the
 * results the documents promise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocsExamplesR8AdversarialTest {

    private val source: VideoSource = VideoSource.Remote("https://example.test/lesson.m3u8")

    private class LessonVideo(private val player: VideoPlayer, scope: CoroutineScope) {
        val state: StateFlow<VideoPlayerState> = player.stateFlow
        val watchedEnough: StateFlow<Boolean> = player.stateFlow
            .map { it.progress >= 0.95f || it is VideoPlayerState.Completed }
            .runningReduce { seen: Boolean, now: Boolean -> seen || now }
            .stateIn(scope, SharingStarted.Eagerly, false)

        suspend fun open(url: String, autoPlay: Boolean) {
            player.prepare(VideoSource.Remote(url))
            if (autoPlay && player.stateFlow.value.isPlayable) player.play()
        }

        fun toggle() = if (player.stateFlow.value.isPlaying) player.pause() else player.play()
        fun dispose() = player.release()
    }

    @Test
    fun `an expired link shows the retry state`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { loadFailure = IllegalStateException("403") }
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
        player.prepare(source)
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        player.release()
    }

    @Test
    fun `the documented position poll shows four seconds and a twelve second buffer after 251 ms`() = runTest {
        val engine = FakeVideoPlaybackEngine(durationMs = 100_000L)
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.advancePositionBy(4_000)
            engine.bufferedPositionMs = 12_000
            advanceTimeBy(251)
            assertEquals(4_000L, player.playbackPositionFlow.value)
            assertEquals(12_000L, player.bufferedPositionFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `the documented buffering size and watched enough examples hold`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        val lesson = LessonVideo(player, backgroundScope)
        try {
            lesson.open("https://example.test/a.m3u8", autoPlay = true)
            assertTrue(lesson.state.value.isPlaying)
            engine.reportBuffering(true)
            assertTrue(player.isBufferingFlow.value)
            engine.reportVideoSize(VideoSize(1080, 1920))
            assertEquals(9f / 16f, player.videoSizeFlow.value?.aspectRatio)
            engine.advancePositionTo(engine.durationMs * 96 / 100)
            advanceTimeBy(251)
            runCurrent()
            assertTrue(lesson.watchedEnough.value)
            lesson.toggle()
            assertTrue(player.stateFlow.value is VideoPlayerState.Paused)
            engine.advancePositionTo(0L)
            advanceTimeBy(251)
            runCurrent()
            assertTrue(lesson.watchedEnough.value, "stays true")
        } finally {
            lesson.dispose()
            lesson.dispose()
        }
        assertEquals(1, engine.releaseCount)
        assertEquals(1, engine.disposeCount)
        assertFalse(engine.hasListener)
    }

    @Test
    fun `the documented suspended load shows preparing and a failed load shows an error`() = runTest {
        val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
        val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        val loading = launch { player.prepare(source) }
        runCurrent()
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        engine.failLoad(java.io.IOException("offline"))
        loading.join()
        assertIs<VideoPlayerState.Error>(player.stateFlow.value)
        player.release()
    }
}
