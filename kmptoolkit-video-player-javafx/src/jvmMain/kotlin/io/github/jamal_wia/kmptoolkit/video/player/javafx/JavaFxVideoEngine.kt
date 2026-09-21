package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [VideoPlaybackEngine] over JavaFX Media, rendering into memory as a [VideoFrameSource].
 *
 * Holds no JavaFX type itself — those live in [FxPlayback] and [FxThread] — so the class loads, and
 * [load] reports [JavaFxVideoPlayerException.RuntimeUnavailable], on a classpath without OpenJFX.
 *
 * Each [load] opens a [Session]; [release] (or the next [load]) closes it. Every listener call and
 * frame goes through the session under [lock] and is dropped once the session is closed, so nothing
 * reaches the listener after [release] returns, even an event JavaFX had already queued.
 *
 * Keeps the SPI's no-op `dispose()`: everything this engine holds belongs to one source and is freed
 * by [release], and the JavaFX toolkit is process-wide — not this engine's to shut down.
 *
 * @param runtime starts the JavaFX toolkit; a test seam.
 * @param classLoader resolves [VideoSource.Asset] paths as classpath resources.
 * @param maxFrameRate the most snapshots per second copied into [frames] while playing.
 * @param readyTimeoutMs how long [load] waits for JavaFX to report the source ready or failed.
 */
@OptIn(ToolkitInternalApi::class)
internal class JavaFxVideoEngine(
    private val runtime: JavaFxRuntime = SystemJavaFxRuntime,
    private val classLoader: ClassLoader = defaultClassLoader(),
    private val maxFrameRate: Int = DEFAULT_MAX_FRAME_RATE,
    private val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) : VideoPlaybackEngine, VideoFrameSource {

    init {
        require(maxFrameRate > 0) { "maxFrameRate must be positive, was $maxFrameRate" }
        require(readyTimeoutMs > 0L) { "readyTimeoutMs must be positive, was $readyTimeoutMs" }
    }

    private val lock = Any()

    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

    /** The current source; guarded by [lock]. */
    private var session: Session? = null

    @Volatile private var volume: Float = 1f
    @Volatile private var speed: Float = 1f
    @Volatile private var looping: Boolean = false

    private val mutableFrames: MutableStateFlow<VideoFrame?> = MutableStateFlow(null)
    override val frames: StateFlow<VideoFrame?> = mutableFrames.asStateFlow()

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: VideoSource) {
        // Both may block: resolving touches the disk, and the first start of the toolkit waits up to
        // FxThread's start timeout for the FX thread. The player calls load() from whatever
        // dispatcher prepare() runs on — in a Compose app, the UI thread — so neither runs there.
        val uri: String = withContext(Dispatchers.IO) {
            resolveSourceUri(source, classLoader).also { runtime.ensureStarted() }
        }
        currentCoroutineContext().ensureActive()

        // A new source replaces the loaded one, which stops talking to the listener right here.
        currentSession()?.close()
        val session = Session()
        synchronized(lock) { this.session = session }

        try {
            FxPlayback.open(uri, session, maxFrameRate, readyTimeoutMs, onCreated = session::attach)
        } catch (e: Throwable) {
            val releasedMeanwhile: Boolean = session.releasedWhileLoading
            session.close()
            // release() disposing the half-built player cancels the wait inside open(); report that
            // as what it is — unless the caller's own coroutine was cancelled, which wins.
            if (releasedMeanwhile && e is CancellationException && currentCoroutineContext().isActive) {
                throw VideoPlayerReleasedException()
            }
            throw e
        }
        val playback: FxPlayback = synchronized(lock) {
            session.loading = false
            if (!session.active) null else session.playback
        } ?: throw VideoPlayerReleasedException()
        // Settings may have changed while the player was being built; apply the latest.
        playback.setVolume(volume)
        playback.setSpeed(speed)
        playback.setLooping(looping)
    }

    override fun start() {
        activePlayback()?.start()
    }

    override fun pause() {
        activePlayback()?.pause()
    }

    override fun seekTo(positionMs: Long) {
        val session: Session = synchronized(lock) { session } ?: return
        session.positionMs = positionMs
        session.playback?.seekTo(positionMs)
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed
        activePlayback()?.setSpeed(speed)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        activePlayback()?.setVolume(volume)
    }

    override fun setLooping(looping: Boolean) {
        this.looping = looping
        activePlayback()?.setLooping(looping)
    }

    override fun durationMs(): Long = currentSession()?.durationMs ?: 0L

    override fun positionMs(): Long = currentSession()?.positionMs ?: 0L

    override fun bufferedPositionMs(): Long = currentSession()?.bufferedMs ?: 0L

    override fun release() {
        synchronized(lock) { session }?.close()
    }

    /** The loaded player's settings and status as JavaFX holds them, or `null`; a test seam. */
    internal suspend fun inspectPlayback(): FxPlayback.Inspection? = activePlayback()?.inspect()

    private fun currentSession(): Session? = synchronized(lock) { session }

    private fun activePlayback(): FxPlayback? = currentSession()?.playback

    /** One source's lifetime, and the gate every event from it passes through. */
    private inner class Session : FxPlayback.Sink {

        /** Written under [lock]. */
        @Volatile var active: Boolean = true

        /** Whether [load] is still building this session's player. Written under [lock]. */
        @Volatile var loading: Boolean = true

        /** Set when [close] ran while [loading]. */
        @Volatile var releasedWhileLoading: Boolean = false

        @Volatile var playback: FxPlayback? = null
        @Volatile var durationMs: Long = 0L
        @Volatile var positionMs: Long = 0L
        @Volatile var bufferedMs: Long = 0L

        /** Called on the FX thread once the player exists; disposes it if already closed. */
        fun attach(playback: FxPlayback) {
            val open: Boolean = synchronized(lock) {
                if (active) this.playback = playback
                active
            }
            if (!open) playback.dispose()
        }

        /** Closes this session if still open: no event from it is delivered after this returns. */
        fun close() {
            val toDispose: FxPlayback? = synchronized(lock) {
                if (!active) return
                active = false
                if (loading) releasedWhileLoading = true
                if (session === this) {
                    session = null
                    mutableFrames.value = null
                }
                playback
            }
            toDispose?.dispose()
        }

        private inline fun deliver(block: (VideoPlaybackEngineListener) -> Unit) {
            synchronized(lock) {
                if (active) listener?.let(block)
            }
        }

        override fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long) {
            this.durationMs = durationMs
            this.positionMs = positionMs
            this.bufferedMs = bufferedMs
        }

        override fun onVideoSize(size: VideoSize?) = deliver { it.onVideoSizeChanged(size) }

        override fun onBuffering(isBuffering: Boolean) = deliver { it.onBufferingChanged(isBuffering) }

        override fun onCompleted() = deliver { it.onCompleted() }

        override fun onFailed(cause: Throwable) = deliver { it.onFailed(cause) }

        override fun onFrame(frame: VideoFrame) {
            synchronized(lock) {
                if (active) mutableFrames.value = frame
            }
        }
    }

    internal companion object {

        /** Enough for smooth motion; every snapshot costs a render and a full-frame copy. */
        const val DEFAULT_MAX_FRAME_RATE: Int = 30

        /** Generous for a slow network; only a source JavaFX silently cannot open ever hits it. */
        const val DEFAULT_READY_TIMEOUT_MS: Long = 30_000L

        private fun defaultClassLoader(): ClassLoader =
            Thread.currentThread().contextClassLoader ?: JavaFxVideoEngine::class.java.classLoader
    }
}
