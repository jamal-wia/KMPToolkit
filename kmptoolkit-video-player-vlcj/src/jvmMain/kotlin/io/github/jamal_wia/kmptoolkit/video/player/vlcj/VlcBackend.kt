package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.nio.ByteBuffer

/*
 * The seam between VlcjVideoEngine's session logic and libvlc. Everything the engine needs from VLC
 * goes through these three types and nothing else, so the engine's rules (what may run on which
 * thread, what is remembered until playback starts, what is dropped after a session closes) are
 * testable against a fake on a machine without VLC. The production implementation, VlcjRuntime, is
 * a direct translation onto VLCJ and holds no logic of its own.
 */

/** One libvlc instance: the expensive, shared part, kept for the engine's whole life. */
internal interface VlcRuntime {

    /**
     * Creates a native media player whose events and decoded pictures go to [callbacks]. Blocking;
     * never called on a VLC thread.
     */
    fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer

    /** Frees the libvlc instance. Called once, after every player it created was released. */
    fun release()
}

/** One native media player. Calls from a VLC callback thread are not allowed (VLCJ's rule). */
internal interface VlcNativePlayer {

    /** Sets the media to play; `false` when VLC rejects the locator. */
    fun prepare(mrl: String, options: List<String>): Boolean

    /** Starts VLC's asynchronous parser (local and network); `false` if it could not start. */
    fun parse(): Boolean

    /** What parsing learned; meaningful once [VlcPlayerCallbacks.parsed] reported a result. */
    fun parsedInfo(): VlcParsedInfo

    fun play()

    fun pause()

    fun setTime(timeMs: Long)

    /** The playhead, or a negative value when VLC has none. */
    fun time(): Long

    fun setRate(rate: Float)

    fun setVolume(percent: Int)

    /** The rate libvlc has, read back — lets the real-VLC tests see what actually took effect. */
    fun rate(): Float

    /** The volume libvlc has in percent, or a negative value before an audio output exists. */
    fun volume(): Int

    /** VLCJ's own replay-at-end flag; a Java-side value, safe from any thread. */
    fun setRepeat(repeat: Boolean)

    fun repeat(): Boolean

    /** Runs [task] on VLCJ's task thread, where calling into libvlc is allowed. */
    fun submit(task: () -> Unit)

    fun stopParsing()

    fun stop()

    fun release()
}

/** The events and pictures of one [VlcNativePlayer], delivered on VLC's own threads. */
internal interface VlcPlayerCallbacks {
    fun playing()
    fun paused()
    fun stopped()
    fun finished()
    fun error()
    fun buffering(cachePercent: Float)
    fun lengthChanged(lengthMs: Long)
    fun timeChanged(timeMs: Long)
    fun parsed(status: VlcParseStatus)
    fun durationChanged(durationMs: Long)

    /** The decoded picture's buffer size, before the first picture and on every size change. */
    fun bufferFormat(width: Int, height: Int)

    /** One decoded picture: [width] × [height] RV32 words in [pixels]. Valid only during the call. */
    fun display(pixels: ByteBuffer, width: Int, height: Int)
}

internal enum class VlcParseStatus { DONE, SKIPPED, FAILED, TIMEOUT }

/**
 * @property durationMs the parsed duration, `0` or less when unknown.
 * @property trackCount how many tracks of any kind were found.
 * @property videoSize the displayed size of the first video track (see [displaySize]).
 */
internal class VlcParsedInfo(
    val durationMs: Long,
    val trackCount: Int,
    val videoSize: VideoSize?,
)

/**
 * The displayed picture size of a video track stored as [width] × [height] pixels: the sample
 * aspect ratio [sar]/[sarBase] applied to the width (anamorphic sources store fewer, wider pixels),
 * and the sides swapped when [orientation] — the name of VLCJ's `VideoOrientation` — says the
 * picture is shown rotated by 90°. `null` for a track without a size.
 */
internal fun displaySize(width: Int, height: Int, sar: Int, sarBase: Int, orientation: String?): VideoSize? {
    if (width <= 0 || height <= 0) return null
    var displayWidth: Int = width
    if (sar > 0 && sarBase > 0 && sar != sarBase) {
        displayWidth = (width.toLong() * sar / sarBase).toInt().coerceAtLeast(1)
    }
    return when (orientation) {
        "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM" -> VideoSize(height, displayWidth)
        else -> VideoSize(displayWidth, height)
    }
}
