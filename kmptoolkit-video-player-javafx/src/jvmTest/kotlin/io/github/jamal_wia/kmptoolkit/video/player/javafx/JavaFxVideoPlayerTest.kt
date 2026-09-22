package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.frameSourceOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The whole [VideoPlayer] over the real JavaFX engine: what an app gets from
 * [createJavaFxVideoPlayer]. Skipped where the toolkit cannot start (see [assumeJavaFxMedia]).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxVideoPlayerTest {

    private lateinit var engine: JavaFxVideoEngine
    private lateinit var player: VideoPlayer

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        engine = JavaFxVideoEngine()
        player = createVideoPlayer(engine)
    }

    @AfterTest
    fun tearDown() {
        if (::player.isInitialized) player.release()
    }

    private fun prepare() = runBlocking {
        withTimeout(10_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
        assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
    }

    @Test
    fun thePublicFactoryBuildsAPlayerThatRendersToMemory() {
        val created: VideoPlayer = createJavaFxVideoPlayer()
        try {
            assertEquals(VideoPlayerState.Idle, created.stateFlow.value)
            assertNotNull(created.frameSourceOrNull())
        } finally {
            created.release()
        }
    }

    @Test
    fun theFrameSourceIsTheEngineAndStableAcrossSources() {
        assertSame(engine, player.frameSourceOrNull())
        prepare()
        player.unload()
        assertSame(engine, player.frameSourceOrNull())
    }

    @Test
    fun seekAfterCompletionStaysPausedAndPlayResumesFromThere() {
        prepare()
        player.seekTo(2_600L)
        player.play()
        awaitTrue(message = { "state ${player.stateFlow.value}" }) {
            player.stateFlow.value is VideoPlayerState.Completed
        }

        val completed: VideoPlayerState.Completed = player.stateFlow.value as VideoPlayerState.Completed

        player.seekTo(1_000L)
        assertEquals(VideoPlayerState.Paused(completed.duration, 1_000L), player.stateFlow.value)
        Thread.sleep(700L)
        // Still paused, in the state and in JavaFX: the playhead has not moved on.
        assertIs<VideoPlayerState.Paused>(player.stateFlow.value)
        assertTrue(engine.positionMs() in 900L..1_100L, "moved on to ${engine.positionMs()}")

        player.play()
        assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
        awaitTrue(message = { "position ${engine.positionMs()}" }) { engine.positionMs() >= 1_300L }
    }
}
