package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.flow.StateFlow

/**
 * A desktop engine that renders decoded pictures into memory rather than into a native view, so
 * `kmptoolkit-video-player-compose` can draw them without depending on the engine's library. The
 * VLCJ engine in `kmptoolkit-video-player-vlcj` implements it.
 */
@ToolkitInternalApi
public interface VideoFrameSource {

    /** The latest decoded frame, or `null` when there is none. Emitted from a decoder thread. */
    public val frames: StateFlow<VideoFrame?>
}

/**
 * One decoded picture in 32-bit ARGB, row-major, [width] × [height] pixels.
 *
 * Each frame owns its [pixels]: a producer never writes to the array again once it has emitted the
 * frame, so a consumer may read it for as long as it holds the frame — [VideoFrameSource.frames] is
 * conflated, and the consumer draws whenever it gets to it. A plain class, not a data class: two
 * frames are never compared by content.
 */
@ToolkitInternalApi
public class VideoFrame(
    public val width: Int,
    public val height: Int,
    public val pixels: IntArray,
)

/**
 * The [VideoFrameSource] behind a player whose engine renders to memory; `null` for any other engine.
 */
@ToolkitInternalApi
public fun VideoPlayer.frameSourceOrNull(): VideoFrameSource? =
    (this as? EngineVideoPlayer)?.engine as? VideoFrameSource
