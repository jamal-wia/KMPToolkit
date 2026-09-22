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
 * Each frame owns its [pixels]: a producer hands over a fresh array with every frame and never writes
 * to it after emitting it, so a frame stays valid for as long as a consumer holds it —
 * [VideoFrameSource.frames] is conflated and read on another thread, so a reused buffer could be
 * overwritten mid-draw. Consumers must not modify [pixels] either. A plain class, not a data class:
 * two frames are never compared by content.
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
