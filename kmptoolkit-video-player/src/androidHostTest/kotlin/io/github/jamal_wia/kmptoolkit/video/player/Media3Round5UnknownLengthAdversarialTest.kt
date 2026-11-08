package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.anamorphicVideo
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.newEngine
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runMainLooperUntil
import io.github.jamal_wia.kmptoolkit.video.player.Media3TestSupport.runSuspending
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 5: the real Media3 engine under a re-prepare close to the end of the previous source, and
 * under platform objects that cannot be built.
 */
@OptIn(ToolkitInternalApi::class)
@RunWith(AndroidJUnit4::class)
class Media3Round5UnknownLengthAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sourceA: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    private val sourceB: VideoSource = VideoSource.Remote("https://example.test/b.mp4")
    private val players: MutableList<VideoPlayer> = mutableListOf()

    @After
    fun tearDown() {
        players.forEach { it.release() }
        repeat(10) { ShadowLooper.idleMainLooper() }
    }

    private fun track(player: VideoPlayer): VideoPlayer = player.also { players += it }

    @Test
    fun `a source whose length is unknown that plays to its end completes with the playhead where it ended`() {
        val unknownLength: (Context, VideoSource) -> MediaSource = { _, _ ->
            FakeMediaSource(
                FakeTimeline(
                    FakeTimeline.TimelineWindowDefinition(
                        /* isSeekable = */ true,
                        /* isDynamic = */ false,
                        /* durationUs = */ 3_000_000L,
                    ),
                ),
                anamorphicVideo,
            )
        }
        // The window knows its length; the engine's snapshot is what the player sees. Force the
        // player's view of the length to unknown by asking the core through a wrapping engine.
        val inner: Media3VideoEngine = newEngine(context, mediaSources = unknownLength)
        val engine: VideoPlaybackEngine = object : VideoPlaybackEngine by inner {
            override fun durationMs(): Long = 0L
        }
        val player: VideoPlayer = track(createVideoPlayer(engine = engine, coroutineContext = Dispatchers.Unconfined))
        runSuspending { player.prepare(sourceA) }.getOrThrow()
        player.play()
        runMainLooperUntil { player.stateFlow.value is VideoPlayerState.Completed }
        // On Media3 the engine's position at the end is the end; the core must not throw it away.
        assertTrue(
            player.playbackPositionFlow.value > 0L,
            "completed on a source of unknown length with the playhead at ${player.playbackPositionFlow.value}",
        )
    }
}
