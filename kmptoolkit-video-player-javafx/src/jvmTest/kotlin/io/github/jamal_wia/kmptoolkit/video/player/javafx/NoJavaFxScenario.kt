package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.frameSourceOrNull
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs inside a class loader that cannot see OpenJFX (see [NoToolkitTest]), as an app without the
 * OpenJFX jars would: the factory must not touch JavaFX, and the failure must arrive as a typed
 * [VideoPlayerState.Error] from prepare, not as a crash.
 */
@OptIn(ToolkitInternalApi::class)
internal class NoJavaFxScenario : Callable<String> {

    override fun call(): String {
        assertFailsWith<ClassNotFoundException>("OpenJFX is visible to the scenario") {
            Class.forName("javafx.scene.media.MediaPlayer", false, javaClass.classLoader)
        }

        val player: VideoPlayer = createJavaFxVideoPlayer()
        try {
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNotNull(player.frameSourceOrNull(), "a JavaFX player renders to memory")

            runBlocking { withTimeout(10_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            val state: VideoPlayerState = player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state)
            assertIs<JavaFxVideoPlayerException.RuntimeUnavailable>(state.cause)
            assertIs<ClassNotFoundException>(state.cause.cause, "cause ${state.cause.cause}")

            assertFalse(isJavaFxMediaAvailable())
            // Remembered: a second prepare fails the same way, without retrying.
            runBlocking { withTimeout(10_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
            assertIs<JavaFxVideoPlayerException.RuntimeUnavailable>(
                (player.stateFlow.value as VideoPlayerState.Error).cause,
            )
        } finally {
            player.release()
        }
        return PASSED
    }

    companion object {
        const val PASSED: String = "passed"
    }
}
