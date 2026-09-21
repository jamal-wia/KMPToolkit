package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * `frameSourceOrNull()` is how the Compose surface finds the pictures of a desktop engine without
 * depending on that engine's artifact: it answers for any engine that renders to memory, and only
 * for those.
 */
@OptIn(ToolkitInternalApi::class)
class FrameSourceAccessorTest {

    private class FrameEngine(
        private val delegate: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
    ) : VideoPlaybackEngine by delegate, VideoFrameSource {
        override val frames: StateFlow<VideoFrame?> = MutableStateFlow(null)
    }

    @Test
    fun `a player over a frame-rendering engine exposes that engine as its frame source`() {
        val engine = FrameEngine()
        val player: VideoPlayer = createVideoPlayer(engine)

        assertSame<VideoFrameSource?>(engine, player.frameSourceOrNull())
        player.release()
    }

    @Test
    fun `a player over any other engine has no frame source`() {
        val player: VideoPlayer = createVideoPlayer(RecordingVideoPlaybackEngine())

        assertNull(player.frameSourceOrNull())
        player.release()
    }
}
