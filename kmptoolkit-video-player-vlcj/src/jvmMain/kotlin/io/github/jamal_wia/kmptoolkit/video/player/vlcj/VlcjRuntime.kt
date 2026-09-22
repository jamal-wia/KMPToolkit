package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.nio.ByteBuffer
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.ParseFlag
import uk.co.caprica.vlcj.media.VideoTrackInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * [VlcRuntime] over VLCJ: one [MediaPlayerFactory] (one libvlc instance). A translation layer only —
 * every rule lives in [VlcjVideoEngine], which is tested against a fake of this seam.
 *
 * @throws VlcUnavailableException when libvlc cannot be loaded or refuses [vlcArgs].
 */
internal class VlcjRuntime(vlcArgs: List<String>) : VlcRuntime {

    private val factory: MediaPlayerFactory = try {
        MediaPlayerFactory(NativeDiscovery(), vlcArgs)
    } catch (error: LinkageError) {
        throw VlcUnavailableException("libvlc could not be loaded", error)
    } catch (error: RuntimeException) {
        throw VlcUnavailableException("libvlc could not be initialised with arguments $vlcArgs", error)
    }

    override fun newPlayer(callbacks: VlcPlayerCallbacks): VlcNativePlayer {
        val player: EmbeddedMediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val sink = FrameSink(callbacks)
        val surface: CallbackVideoSurface = factory.videoSurfaces().newVideoSurface(sink, sink, true)
        player.videoSurface().set(surface)
        player.events().addMediaPlayerEventListener(PlayerEvents(callbacks))
        player.events().addMediaEventListener(MediaEvents(callbacks))
        return VlcjNativePlayer(player, surface)
    }

    override fun release() {
        factory.release()
    }
}

/**
 * @property surface held so the JNA callbacks inside it cannot be garbage-collected while libvlc
 *   still calls them.
 */
private class VlcjNativePlayer(
    private val player: EmbeddedMediaPlayer,
    @Suppress("unused") private val surface: CallbackVideoSurface,
) : VlcNativePlayer {

    override fun prepare(mrl: String, options: List<String>): Boolean =
        player.media().prepare(mrl, *options.toTypedArray())

    override fun parse(): Boolean = player.media().parsing().parse(0, ParseFlag.PARSE_LOCAL, ParseFlag.PARSE_NETWORK)

    override fun parsedInfo(): VlcParsedInfo {
        val info = player.media().info()
        val video: VideoTrackInfo? = info.videoTracks().orEmpty().firstOrNull()
        return VlcParsedInfo(
            durationMs = info.duration(),
            trackCount = info.tracks().orEmpty().size,
            videoSize = video?.let {
                displaySize(
                    width = it.width(),
                    height = it.height(),
                    sar = it.sampleAspectRatio(),
                    sarBase = it.sampleAspectRatioBase(),
                    orientation = it.orientation()?.name,
                )
            },
        )
    }

    override fun play() = player.controls().play()

    override fun pause() = player.controls().setPause(true)

    override fun setTime(timeMs: Long) = player.controls().setTime(timeMs)

    override fun time(): Long = player.status().time()

    override fun setRate(rate: Float) {
        player.controls().setRate(rate)
    }

    override fun setVolume(percent: Int) {
        player.audio().setVolume(percent)
    }

    override fun rate(): Float = player.status().rate()

    override fun volume(): Int = player.audio().volume()

    override fun setRepeat(repeat: Boolean) = player.controls().setRepeat(repeat)

    override fun repeat(): Boolean = player.controls().getRepeat()

    override fun submit(task: () -> Unit) = player.submit(task)

    override fun stopParsing() = player.media().parsing().stop()

    override fun stop() = player.controls().stop()

    override fun release() = player.release()
}

private class PlayerEvents(private val callbacks: VlcPlayerCallbacks) : MediaPlayerEventAdapter() {
    override fun playing(mediaPlayer: MediaPlayer) = callbacks.playing()
    override fun paused(mediaPlayer: MediaPlayer) = callbacks.paused()
    override fun stopped(mediaPlayer: MediaPlayer) = callbacks.stopped()
    override fun finished(mediaPlayer: MediaPlayer) = callbacks.finished()
    override fun error(mediaPlayer: MediaPlayer) = callbacks.error()
    override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = callbacks.buffering(newCache)
    override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = callbacks.lengthChanged(newLength)
    override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = callbacks.timeChanged(newTime)
}

private class MediaEvents(private val callbacks: VlcPlayerCallbacks) : MediaEventAdapter() {
    override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) = callbacks.parsed(
        when (newStatus) {
            MediaParsedStatus.DONE -> VlcParseStatus.DONE
            MediaParsedStatus.SKIPPED -> VlcParseStatus.SKIPPED
            MediaParsedStatus.FAILED -> VlcParseStatus.FAILED
            MediaParsedStatus.TIMEOUT -> VlcParseStatus.TIMEOUT
        },
    )

    override fun mediaDurationChanged(media: Media, newDuration: Long) = callbacks.durationChanged(newDuration)
}

/**
 * Asks VLC for RV32 at the decoded buffer size and hands each picture on. VLCJ reports the buffer
 * size as the "display" size too (no sample aspect ratio applied), so only the buffer size is passed.
 */
private class FrameSink(private val callbacks: VlcPlayerCallbacks) : BufferFormatCallback, RenderCallback {

    override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
        callbacks.bufferFormat(sourceWidth, sourceHeight)
        return RV32BufferFormat(sourceWidth, sourceHeight)
    }

    override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit

    override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit

    override fun lock(mediaPlayer: MediaPlayer) = Unit

    override fun unlock(mediaPlayer: MediaPlayer) = Unit

    override fun display(
        mediaPlayer: MediaPlayer,
        nativeBuffers: Array<out ByteBuffer>,
        bufferFormat: BufferFormat,
        displayWidth: Int,
        displayHeight: Int,
    ) {
        if (nativeBuffers.isEmpty()) return
        callbacks.display(nativeBuffers[0], bufferFormat.width, bufferFormat.height)
    }
}
