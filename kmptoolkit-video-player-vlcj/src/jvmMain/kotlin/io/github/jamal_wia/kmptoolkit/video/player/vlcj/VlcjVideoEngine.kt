package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.Media
import uk.co.caprica.vlcj.media.MediaEventAdapter
import uk.co.caprica.vlcj.media.MediaParsedStatus
import uk.co.caprica.vlcj.media.ParseFlag
import uk.co.caprica.vlcj.media.TrackInfo
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
 * The [VideoPlaybackEngine] over VLC (through VLCJ), rendering into memory for
 * [VideoFrameSource.frames].
 *
 * ### Lifecycle
 *
 * One libvlc instance ([MediaPlayerFactory]) per engine, created by the first [load] and freed by
 * [release]; one native media player per loaded source (a *session*), so events still queued from a
 * previous source can be recognised and dropped by identity. [load] after [release] builds a new
 * libvlc instance.
 *
 * ### Readiness
 *
 * [load] prepares the media and waits for libvlc's parser (local and network) to finish, which is
 * what opens the source and fails it when it cannot be opened. Output does not start until
 * [start], so there is no picture before the first [start]; seeks made before it are remembered and
 * applied once playback begins.
 *
 * ### Threading
 *
 * - Native calls from the caller's thread go through [lock], and every one checks the session is
 *   still open first, so no call can reach a native player after its release.
 * - VLCJ delivers events on its own event thread and pictures on libvlc's video-output thread;
 *   neither may call back into libvlc (VLCJ's documented rule). Events therefore only update cached
 *   values and notify the listener; a transport call that arrives on one of those threads (a
 *   listener reacting synchronously) is re-submitted to VLCJ's task thread with
 *   [MediaPlayer.submit], and a getter answers from the cache.
 * - The listener is only invoked under [listenerLock] for the current, open session; closing a
 *   session takes that lock first, so no callback — queued or in flight — reaches the listener
 *   after [release] returns.
 */
@OptIn(ToolkitInternalApi::class)
internal class VlcjVideoEngine(
    private val vlcArgs: List<String>,
    private val discover: () -> Boolean = VlcNativeDiscovery::discover,
    private val classLoader: ClassLoader? = null,
) : VideoPlaybackEngine, VideoFrameSource {

    private val lock = Any()
    private val listenerLock = Any()

    /** True on VLCJ's event thread and libvlc's video-output thread while this engine handles a callback. */
    private val onVlcThread: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

    /** Guarded by [lock]. */
    private var factory: MediaPlayerFactory? = null

    @Volatile
    private var session: Session? = null

    @Volatile
    private var speed: Float = 1f

    @Volatile
    private var volume: Float = 1f

    @Volatile
    private var looping: Boolean = false

    private val frameFlow: MutableStateFlow<VideoFrame?> = MutableStateFlow(null)

    override val frames: StateFlow<VideoFrame?> = frameFlow.asStateFlow()

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        synchronized(listenerLock) { this.listener = listener }
    }

    override suspend fun load(source: VideoSource) {
        closeSession(session)
        val loader: ClassLoader = classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: VlcjVideoEngine::class.java.classLoader
        val media: ResolvedMedia = withContext(Dispatchers.IO) { VlcMediaResolver.resolve(source, loader) }
        val opened = Session(media)
        try {
            if (!discover()) {
                throw VlcUnavailableException(
                    "libvlc was not found, or does not match this JVM's CPU architecture. " +
                        "Install VLC 3.x or bundle libvlc with the application."
                )
            }
            session = opened
            withContext(Dispatchers.IO) { opened.open() }
            when (opened.parsed.await()) {
                MediaParsedStatus.DONE, MediaParsedStatus.SKIPPED -> Unit
                MediaParsedStatus.FAILED -> throw VlcPlaybackException("VLC could not open the source")
                MediaParsedStatus.TIMEOUT -> throw VlcPlaybackException("VLC timed out opening the source")
            }
            val initialSize: VideoSize? = opened.inspectParsedMedia()
            opened.loaded = true
            if (initialSize != null) opened.reportVideoSize(initialSize)
        } catch (error: Throwable) {
            closeSession(opened)
            throw error
        }
    }

    override fun start() {
        withOpenSession { s ->
            if (!s.loaded) return
            if (onVlcThread.get()) {
                s.player?.submit { start() }
                return
            }
            s.player?.controls()?.play()
            s.started = true
        }
    }

    override fun pause() {
        withOpenSession { s ->
            if (!s.loaded || !s.started || s.ended) return
            if (onVlcThread.get()) {
                s.player?.submit { pause() }
                return
            }
            s.player?.controls()?.setPause(true)
        }
    }

    override fun seekTo(positionMs: Long) {
        withOpenSession { s ->
            if (!s.loaded) return
            val target: Long = positionMs.coerceAtLeast(0L)
            // Before the first start, and after the end, libvlc has no running input to seek in:
            // remember the target and apply it when playback begins.
            if (!s.started || s.ended) {
                s.pendingSeekMs = target
                s.timeMs = target
                return
            }
            if (onVlcThread.get()) {
                s.player?.submit { seekTo(target) }
                return
            }
            s.player?.controls()?.setTime(target)
            s.timeMs = target
        }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        withOpenSession { s -> if (s.started) s.applySettings() }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        withOpenSession { s -> if (s.started) s.applySettings() }
    }

    override fun setLooping(looping: Boolean) {
        this.looping = looping
        // A VLCJ-side flag (its own finished-event handler replays the media), not a native call.
        withOpenSession { s -> s.player?.controls()?.setRepeat(looping) }
    }

    override fun durationMs(): Long {
        val s: Session = session ?: return 0L
        if (s.closed || !s.loaded) return 0L
        return s.lengthMs.coerceAtLeast(0L)
    }

    override fun positionMs(): Long {
        val s: Session = session ?: return 0L
        if (s.closed || !s.loaded) return 0L
        if (s.pendingSeekMs >= 0L) return s.pendingSeekMs
        if (s.ended) return durationMs()
        if (s.started && !onVlcThread.get()) {
            withOpenSession { open ->
                val time: Long = open.player?.status()?.time() ?: -1L
                if (time >= 0L) open.timeMs = time
            }
        }
        return s.timeMs.coerceAtLeast(0L)
    }

    /**
     * VLC exposes a cache fill level, not how far ahead it has buffered, so this reports only what
     * is certain: the duration for a local source (all of it is there), the playhead for a remote
     * one (at least that much was downloaded).
     */
    override fun bufferedPositionMs(): Long {
        val s: Session = session ?: return 0L
        if (s.closed || !s.loaded) return 0L
        return if (s.media.isLocal) durationMs() else positionMs()
    }

    override fun release() {
        closeSession(session)
        val released: MediaPlayerFactory? = synchronized(lock) { factory.also { factory = null } }
        if (released != null) runOffVlcThread { runCatching { released.release() } }
        frameFlow.value = null
    }

    // --- internals ---------------------------------------------------------------------------

    /** Runs [block] under [lock] with the current session, if there is one and it is still open. */
    private inline fun withOpenSession(block: (Session) -> Unit) {
        synchronized(lock) {
            val s: Session = session ?: return
            if (s.closed) return
            block(s)
        }
    }

    private fun closeSession(s: Session?) {
        if (s == null || !s.closing.compareAndSet(false, true)) return
        // 1. No listener call can start after this, and none is in flight once the lock is ours.
        synchronized(listenerLock) { s.closed = true }
        // 2. Barrier: every native call under [lock] checks `closed`, so none can run on this
        //    session after this block.
        synchronized(lock) { if (session === s) session = null }
        s.parsed.cancel()
        runOffVlcThread {
            s.player?.let { player: EmbeddedMediaPlayer ->
                runCatching { player.media().parsing().stop() }
                runCatching { player.controls().stop() }
                runCatching { player.release() }
            }
            s.media.discard()
        }
        frameFlow.value = null
    }

    /**
     * Native teardown must not run on a VLC thread (it would wait for that very thread), which can
     * happen when a listener releases the player from inside a callback — hand it to a new thread
     * then.
     */
    private fun runOffVlcThread(block: () -> Unit) {
        if (onVlcThread.get()) {
            thread(name = "kmptoolkit-vlcj-release", isDaemon = true) { block() }
        } else {
            block()
        }
    }

    private inline fun onVlcCallback(block: () -> Unit) {
        val previous: Boolean = onVlcThread.get()
        onVlcThread.set(true)
        try {
            block()
        } finally {
            onVlcThread.set(previous)
        }
    }

    private fun notify(s: Session, event: (VideoPlaybackEngineListener) -> Unit) {
        synchronized(listenerLock) {
            if (s.closed || session !== s) return
            val current: VideoPlaybackEngineListener = listener ?: return
            event(current)
        }
    }

    private fun createFactory(): MediaPlayerFactory = try {
        MediaPlayerFactory(NativeDiscovery(), vlcArgs)
    } catch (error: LinkageError) {
        throw VlcUnavailableException("libvlc could not be loaded", error)
    } catch (error: RuntimeException) {
        throw VlcUnavailableException("libvlc could not be initialised with arguments $vlcArgs", error)
    }

    /** One loaded source on its own native media player. */
    private inner class Session(val media: ResolvedMedia) {

        val closing: AtomicBoolean = AtomicBoolean(false)
        val parsed: CompletableDeferred<MediaParsedStatus> = CompletableDeferred()

        @Volatile var player: EmbeddedMediaPlayer? = null
        @Volatile var closed: Boolean = false
        @Volatile var loaded: Boolean = false
        @Volatile var started: Boolean = false
        @Volatile var ended: Boolean = false
        @Volatile var pendingSeekMs: Long = -1L
        @Volatile var lengthMs: Long = 0L
        @Volatile var timeMs: Long = 0L
        @Volatile var bufferingReported: Boolean = false
        @Volatile var reportedSize: VideoSize? = null

        // Held so the JNA callbacks inside cannot be garbage-collected while libvlc uses them.
        private var surface: CallbackVideoSurface? = null
        private val frameSink: FrameSink = FrameSink(this)
        private val playerEvents: PlayerEvents = PlayerEvents(this)
        private val mediaEvents: MediaEvents = MediaEvents(this)

        /** Creates the native player and starts parsing. Blocking; call off the main thread. */
        fun open() {
            synchronized(lock) {
                if (closed) throw CancellationException("The load was abandoned")
                val libvlc: MediaPlayerFactory = factory ?: createFactory().also { factory = it }
                val created: EmbeddedMediaPlayer = libvlc.mediaPlayers().newEmbeddedMediaPlayer()
                player = created
                val videoSurface: CallbackVideoSurface =
                    libvlc.videoSurfaces().newVideoSurface(frameSink, frameSink, true)
                surface = videoSurface
                created.videoSurface().set(videoSurface)
                created.events().addMediaPlayerEventListener(playerEvents)
                created.events().addMediaEventListener(mediaEvents)
                created.controls().setRepeat(looping)
                if (!created.media().prepare(media.mrl, *media.options.toTypedArray())) {
                    throw VlcPlaybackException("VLC rejected the media locator")
                }
                if (!created.media().parsing().parse(0, ParseFlag.PARSE_LOCAL, ParseFlag.PARSE_NETWORK)) {
                    throw VlcPlaybackException("VLC could not start opening the source")
                }
            }
        }

        /**
         * Reads what parsing learned: the duration, and the picture size of the first video
         * track. A local source with no track at all is not media VLC can play.
         */
        fun inspectParsedMedia(): VideoSize? {
            var size: VideoSize? = null
            withOpenSession { s ->
                if (s !== this) throw CancellationException("The load was abandoned")
                val parsedMedia = player?.media() ?: return@withOpenSession
                val duration: Long = parsedMedia.info().duration()
                if (duration > 0L) lengthMs = duration
                val tracks: List<TrackInfo> = parsedMedia.info().tracks().orEmpty()
                if (tracks.isEmpty() && media.isLocal) {
                    throw VlcPlaybackException("VLC found no playable track in the source")
                }
                val video: VideoTrackInfo? = parsedMedia.info().videoTracks().orEmpty().firstOrNull()
                size = video?.let(::displaySize)
            }
            if (closed) throw CancellationException("The load was abandoned")
            return size
        }

        /** Re-applies volume and rate, which libvlc only accepts once an output exists. */
        fun applySettings() {
            val current: EmbeddedMediaPlayer = player ?: return
            if (onVlcThread.get()) {
                current.submit { withOpenSession { s -> if (s === this) applySettings() } }
                return
            }
            current.audio().setVolume(volumePercent(volume))
            current.controls().setRate(speed)
        }

        fun setBuffering(buffering: Boolean) {
            if (bufferingReported == buffering) return
            bufferingReported = buffering
            notify(this) { it.onBufferingChanged(buffering) }
        }

        fun reportVideoSize(size: VideoSize) {
            if (reportedSize == size) return
            reportedSize = size
            notify(this) { it.onVideoSizeChanged(size) }
        }

        // Frame buffers: a small ring, so the consumer can still be copying frame N while N+1 is
        // decoded. Touched only from libvlc's single video-output thread.
        private var ring: Array<IntArray> = emptyArray()
        private var ringIndex: Int = 0

        fun allocateFrames(pixelCount: Int) {
            ring = Array(FRAME_RING_SIZE) { IntArray(pixelCount) }
            ringIndex = 0
        }

        fun nextFrameBuffer(pixelCount: Int): IntArray {
            if (ring.isEmpty() || ring[0].size != pixelCount) allocateFrames(pixelCount)
            val buffer: IntArray = ring[ringIndex]
            ringIndex = (ringIndex + 1) % ring.size
            return buffer
        }
    }

    private inner class PlayerEvents(private val s: Session) : MediaPlayerEventAdapter() {

        override fun playing(mediaPlayer: MediaPlayer) = onVlcCallback {
            s.ended = false
            // libvlc accepts a volume and a seek only once the output exists — i.e. now. Not from
            // this thread, though: VLCJ's task thread may call into libvlc.
            mediaPlayer.submit {
                withOpenSession { open ->
                    if (open !== s) return@withOpenSession
                    s.applySettings()
                    val seek: Long = s.pendingSeekMs
                    if (seek >= 0L) {
                        mediaPlayer.controls().setTime(seek)
                        s.timeMs = seek
                        s.pendingSeekMs = -1L
                    }
                }
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) = onVlcCallback { s.setBuffering(false) }

        override fun stopped(mediaPlayer: MediaPlayer) = onVlcCallback { s.setBuffering(false) }

        override fun finished(mediaPlayer: MediaPlayer) = onVlcCallback {
            s.setBuffering(false)
            // While looping VLCJ's own handler replays the media; the end is not an end.
            if (mediaPlayer.controls().getRepeat()) return@onVlcCallback
            s.ended = true
            s.timeMs = s.lengthMs
            notify(s) { it.onCompleted() }
        }

        override fun error(mediaPlayer: MediaPlayer) = onVlcCallback {
            s.setBuffering(false)
            s.ended = true
            notify(s) { it.onFailed(VlcPlaybackException("VLC reported an error while playing the source")) }
        }

        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = onVlcCallback {
            if (s.loaded) s.setBuffering(newCache < FULL_CACHE_PERCENT)
        }

        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = onVlcCallback {
            if (newLength > 0L) s.lengthMs = newLength
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = onVlcCallback {
            if (newTime >= 0L && s.pendingSeekMs < 0L) s.timeMs = newTime
        }
    }

    private inner class MediaEvents(private val s: Session) : MediaEventAdapter() {

        override fun mediaParsedChanged(media: Media, newStatus: MediaParsedStatus) = onVlcCallback {
            s.parsed.complete(newStatus)
        }

        override fun mediaDurationChanged(media: Media, newDuration: Long) = onVlcCallback {
            if (newDuration > 0L) s.lengthMs = newDuration
        }
    }

    private inner class FrameSink(private val s: Session) : BufferFormatCallback, RenderCallback {

        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            onVlcCallback {
                s.allocateFrames(sourceWidth * sourceHeight)
                if (sourceWidth > 0 && sourceHeight > 0) s.reportVideoSize(VideoSize(sourceWidth, sourceHeight))
            }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
            onVlcCallback {
                if (displayWidth > 0 && displayHeight > 0) s.reportVideoSize(VideoSize(displayWidth, displayHeight))
            }
        }

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
            if (s.closed || nativeBuffers.isEmpty()) return
            val width: Int = bufferFormat.width
            val height: Int = bufferFormat.height
            if (width <= 0 || height <= 0) return
            val pixels: IntArray = s.nextFrameBuffer(width * height)
            copyRv32ToArgb(nativeBuffers[0], pixels, width * height)
            if (!s.closed) frameFlow.value = VideoFrame(width, height, pixels)
        }
    }

    internal companion object {
        private const val FRAME_RING_SIZE = 3
        private const val FULL_CACHE_PERCENT = 100f
        private const val MAX_VLC_VOLUME_PERCENT = 100
    }
}

/**
 * VLC's volume scale is `0..200` percent, where `100` is the source's own level and above it is
 * software amplification. `1f` maps to `100`, never beyond: full volume means what it means on
 * Android and iOS, and amplifying would clip.
 */
internal fun volumePercent(volume: Float): Int = (volume.coerceIn(0f, 1f) * 100f).roundToInt()

/**
 * Copies [pixelCount] RV32 pixels into [target] as opaque ARGB. RV32 is one native-endian 32-bit
 * word per pixel holding `0x??RRGGBB` — the top byte is padding, not alpha, so it is forced opaque.
 */
internal fun copyRv32ToArgb(source: ByteBuffer, target: IntArray, pixelCount: Int) {
    val words: IntBuffer = source.duplicate().order(ByteOrder.nativeOrder()).also { it.rewind() }.asIntBuffer()
    words.get(target, 0, pixelCount)
    for (index: Int in 0 until pixelCount) {
        target[index] = target[index] or OPAQUE_ALPHA
    }
}

private const val OPAQUE_ALPHA: Int = -0x1000000 // 0xFF000000

/** The displayed picture size of a parsed video track: sample aspect ratio applied, rotation honoured. */
internal fun displaySize(track: VideoTrackInfo): VideoSize? {
    var width: Int = track.width()
    val height: Int = track.height()
    if (width <= 0 || height <= 0) return null
    val sar: Int = track.sampleAspectRatio()
    val sarBase: Int = track.sampleAspectRatioBase()
    if (sar > 0 && sarBase > 0 && sar != sarBase) {
        width = (width.toLong() * sar / sarBase).toInt().coerceAtLeast(1)
    }
    return when (track.orientation()?.name) {
        "LEFT_TOP", "LEFT_BOTTOM", "RIGHT_TOP", "RIGHT_BOTTOM" -> VideoSize(height, width)
        else -> VideoSize(width, height)
    }
}
