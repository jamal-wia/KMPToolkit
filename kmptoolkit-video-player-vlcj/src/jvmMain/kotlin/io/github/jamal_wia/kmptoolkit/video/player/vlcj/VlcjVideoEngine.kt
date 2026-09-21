package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.Cleaner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The [VideoPlaybackEngine] over VLC (through VLCJ), rendering into memory for
 * [VideoFrameSource.frames].
 *
 * ### Lifecycle
 *
 * - One libvlc instance ([VlcRuntime]) per engine, created by the first [load] and **kept across
 *   [release]**: the player calls [release] on every unload and every abandoned prepare, and
 *   rebuilding libvlc (a plugin scan) each time would make those slow. It is freed by [dispose],
 *   which the player calls once from `VideoPlayer.release()`. A [Cleaner] stays registered only as
 *   a safety net for a player that is dropped without being released: it frees the instance when
 *   the engine is garbage-collected rather than leaking a native library instance for the life of
 *   the process.
 * - One native media player per loaded source (a *session*), so events still queued from a
 *   previous source can be recognised and dropped by identity.
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
 * - Nothing slow runs on the caller's thread, which is usually the UI thread: resolving the source,
 *   locating and loading libvlc, and creating the native player run on [Dispatchers.IO]; tearing a
 *   session down (stopping a stalled network input can take libvlc's whole timeout) and freeing
 *   libvlc run on one serial [teardown] thread. Only the barrier that makes a closed session
 *   unreachable is synchronous.
 * - Native calls from the caller's thread go through [lock], and every one checks the session is
 *   still open first, so no call can reach a native player after its session closed.
 * - VLCJ delivers events on its own event thread and pictures on libvlc's video-output thread;
 *   neither may call back into libvlc (VLCJ's documented rule). Events therefore only update cached
 *   values and notify the listener; a transport call that arrives on one of those threads (a
 *   listener reacting synchronously) is re-submitted to VLCJ's task thread with
 *   [VlcNativePlayer.submit], and a getter answers from the cache.
 * - The listener is only invoked under [listenerLock] for the current, open session; closing a
 *   session takes that lock first, so no callback — queued or in flight — reaches the listener
 *   after [release] returns.
 *
 * @param createRuntime builds the libvlc instance; the seam tests replace.
 * @param teardown the serial executor native teardown runs on.
 * @param resolve maps a source onto what VLC opens; blocking, run on [Dispatchers.IO].
 */
@OptIn(ToolkitInternalApi::class)
internal class VlcjVideoEngine(
    vlcArgs: List<String>,
    private val discover: () -> Boolean = VlcNativeDiscovery::discover,
    private val classLoader: ClassLoader? = null,
    createRuntime: (List<String>) -> VlcRuntime = ::VlcjRuntime,
    private val teardown: Executor = newTeardownExecutor(),
    private val resolve: (VideoSource, ClassLoader) -> ResolvedMedia = VlcMediaResolver::resolve,
) : VideoPlaybackEngine, VideoFrameSource {

    private val lock = Any()
    private val listenerLock = Any()
    private val frameLock = Any()

    /** True on VLC's event and video-output threads while this engine handles a callback. */
    private val onVlcThread: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    // Captures only constructor parameters, never `this`: it is the Cleaner's action.
    private val runtime: RuntimeHolder = RuntimeHolder({ createRuntime(vlcArgs) }, teardown)
    private val cleanable: Cleaner.Cleanable = CLEANER.register(this, runtime)

    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

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
        val media: ResolvedMedia = resolveMedia(source, loader)
        // No suspension point between here and the try: from now on the session owns the media.
        val opened = Session(media)
        try {
            val found: Boolean = withContext(Dispatchers.IO) { discover() }
            if (!found) {
                throw VlcUnavailableException(
                    "libvlc was not found, or does not match this JVM's CPU architecture. " +
                        "Install VLC 3.x or bundle libvlc with the application."
                )
            }
            session = opened
            withContext(Dispatchers.IO) { opened.open() }
            when (opened.parsed.await()) {
                VlcParseStatus.DONE, VlcParseStatus.SKIPPED -> Unit
                VlcParseStatus.FAILED -> throw VlcPlaybackException("VLC could not open the source")
                VlcParseStatus.TIMEOUT -> throw VlcPlaybackException("VLC timed out opening the source")
            }
            val parsedSize: VideoSize? = opened.inspectParsedMedia()
            opened.parsedSize = parsedSize
            opened.loaded = true
            if (parsedSize != null) opened.reportVideoSize(parsedSize)
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
            s.player?.play()
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
            s.player?.pause()
        }
    }

    override fun seekTo(positionMs: Long) {
        withOpenSession { s ->
            if (!s.loaded) return
            val target: Long = positionMs.coerceAtLeast(0L)
            // Before the first start, and after the end, libvlc has no running input to seek in:
            // remember the target and apply it when playback begins. Nothing starts playing here.
            if (!s.started || s.ended) {
                s.pendingSeekMs = target
                s.timeMs = target
                return
            }
            if (onVlcThread.get()) {
                s.player?.submit { seekTo(target) }
                return
            }
            s.player?.setTime(target)
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
        withOpenSession { s -> s.player?.setRepeat(looping) }
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
                val time: Long = open.player?.time() ?: -1L
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

    /** Ends the current session; the libvlc instance stays for the next [load] (see [dispose]). */
    override fun release() {
        closeSession(session)
        synchronized(frameLock) { frameFlow.value = null }
    }

    /**
     * Releases, then frees the libvlc instance — after every native player, on the [teardown]
     * thread. Final and idempotent: a [load] after it throws.
     */
    override fun dispose() {
        release()
        cleanable.clean()
    }

    // --- internals ---------------------------------------------------------------------------

    /**
     * Resolves [source] on [Dispatchers.IO] without leaking what it produced: when cancellation
     * lands after the resolution finished, `withContext` still throws, and the temporary asset copy
     * it made would otherwise be orphaned until JVM exit.
     */
    private suspend fun resolveMedia(source: VideoSource, loader: ClassLoader): ResolvedMedia {
        val result: AtomicReference<ResolvedMedia?> = AtomicReference(null)
        try {
            withContext(Dispatchers.IO) { result.set(resolve(source, loader)) }
        } catch (error: Throwable) {
            result.get()?.discard()
            throw error
        }
        return checkNotNull(result.get())
    }

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
        // 3. No frame of this session can be published after this block (see publishFrame).
        synchronized(frameLock) { frameFlow.value = null }
        // 4. The native teardown itself may block for as long as libvlc takes to give up on an
        //    input — never on the caller's thread, and never on a VLC thread (it would wait for
        //    that very thread).
        teardown.execute { s.teardown() }
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

    private fun publishFrame(s: Session, frame: VideoFrame) {
        synchronized(frameLock) {
            if (!s.closed) frameFlow.value = frame
        }
    }

    /** One loaded source on its own native media player. */
    private inner class Session(val media: ResolvedMedia) {

        val closing: AtomicBoolean = AtomicBoolean(false)
        val parsed: CompletableDeferred<VlcParseStatus> = CompletableDeferred()

        @Volatile var player: VlcNativePlayer? = null
        @Volatile var closed: Boolean = false
        @Volatile var loaded: Boolean = false
        @Volatile var started: Boolean = false
        @Volatile var ended: Boolean = false
        @Volatile var pendingSeekMs: Long = -1L
        @Volatile var lengthMs: Long = 0L
        @Volatile var timeMs: Long = 0L
        @Volatile var bufferingReported: Boolean = false
        @Volatile var reportedSize: VideoSize? = null

        /** The displayed size parsing found; when known, the decoder's buffer size never replaces it. */
        @Volatile var parsedSize: VideoSize? = null

        private val callbacks: Callbacks = Callbacks(this)

        /** Creates the native player and starts parsing. Blocking; call off the main thread. */
        fun open() {
            val libvlc: VlcRuntime = runtime.acquire()
            synchronized(lock) {
                if (closed) throw CancellationException("The load was abandoned")
                val created: VlcNativePlayer = libvlc.newPlayer(callbacks)
                player = created
                created.setRepeat(looping)
                if (!created.prepare(media.mrl, media.options)) {
                    throw VlcPlaybackException("VLC rejected the media locator")
                }
                if (!created.parse()) {
                    throw VlcPlaybackException("VLC could not start opening the source")
                }
            }
        }

        /**
         * Reads what parsing learned: the duration, and the displayed picture size of the first
         * video track. A local source with no track at all is not media VLC can play.
         */
        fun inspectParsedMedia(): VideoSize? {
            var size: VideoSize? = null
            withOpenSession { s ->
                if (s !== this) throw CancellationException("The load was abandoned")
                val info: VlcParsedInfo = player?.parsedInfo() ?: return@withOpenSession
                if (info.durationMs > 0L) lengthMs = info.durationMs
                if (info.trackCount == 0 && media.isLocal) {
                    throw VlcPlaybackException("VLC found no playable track in the source")
                }
                size = info.videoSize
            }
            if (closed) throw CancellationException("The load was abandoned")
            return size
        }

        /** Re-applies volume and rate, which libvlc only accepts once an output exists. */
        fun applySettings() {
            val current: VlcNativePlayer = player ?: return
            if (onVlcThread.get()) {
                current.submit { withOpenSession { s -> if (s === this) applySettings() } }
                return
            }
            current.setVolume(volumePercent(volume))
            current.setRate(speed)
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

        /** Runs on the [teardown] thread: native player first, then the media it had open. */
        fun teardown() {
            player?.let { p: VlcNativePlayer ->
                runCatching { p.stopParsing() }
                runCatching { p.stop() }
                runCatching { p.release() }
            }
            media.discard()
        }
    }

    private inner class Callbacks(private val s: Session) : VlcPlayerCallbacks {

        override fun playing() = onVlcCallback {
            s.ended = false
            // libvlc accepts a volume and a seek only once the output exists — i.e. now. Not from
            // this thread, though: VLCJ's task thread may call into libvlc.
            s.player?.submit {
                withOpenSession { open ->
                    if (open !== s) return@withOpenSession
                    s.applySettings()
                    val seek: Long = s.pendingSeekMs
                    if (seek >= 0L) {
                        s.player?.setTime(seek)
                        s.timeMs = seek
                        s.pendingSeekMs = -1L
                    }
                }
            }
        }

        override fun paused() = onVlcCallback { s.setBuffering(false) }

        override fun stopped() = onVlcCallback { s.setBuffering(false) }

        override fun finished() = onVlcCallback {
            s.setBuffering(false)
            // While looping VLCJ's own handler replays the media; the end is not an end.
            if (s.player?.repeat() == true) return@onVlcCallback
            s.ended = true
            s.timeMs = s.lengthMs
            notify(s) { it.onCompleted() }
        }

        override fun error() = onVlcCallback {
            s.setBuffering(false)
            s.ended = true
            notify(s) { it.onFailed(VlcPlaybackException("VLC reported an error while playing the source")) }
        }

        override fun buffering(cachePercent: Float) = onVlcCallback {
            if (s.loaded) s.setBuffering(cachePercent < FULL_CACHE_PERCENT)
        }

        override fun lengthChanged(lengthMs: Long) = onVlcCallback {
            if (lengthMs > 0L) s.lengthMs = lengthMs
        }

        override fun timeChanged(timeMs: Long) = onVlcCallback {
            if (timeMs >= 0L && s.pendingSeekMs < 0L) s.timeMs = timeMs
        }

        override fun parsed(status: VlcParseStatus) = onVlcCallback {
            s.parsed.complete(status)
        }

        override fun durationChanged(durationMs: Long) = onVlcCallback {
            if (durationMs > 0L) s.lengthMs = durationMs
        }

        /**
         * The buffer is the *stored* picture — for an anamorphic source narrower than it is shown,
         * since VLC hands pictures over without applying the sample aspect ratio. It is the
         * reported size only when parsing found none (some network streams).
         */
        override fun bufferFormat(width: Int, height: Int) = onVlcCallback {
            if (width > 0 && height > 0 && s.parsedSize == null) s.reportVideoSize(VideoSize(width, height))
        }

        /**
         * Copies into a fresh array per picture: the frame flow is conflated and its consumer may
         * hold a frame for any length of time, so no buffer can be reused safely.
         */
        override fun display(pixels: ByteBuffer, width: Int, height: Int) {
            if (s.closed || width <= 0 || height <= 0) return
            val argb = IntArray(width * height)
            copyRv32ToArgb(pixels, argb, width * height)
            publishFrame(s, VideoFrame(width, height, argb))
        }
    }

    /**
     * Owns the libvlc instance. Also the [Cleaner] action, so it must never reference the engine.
     */
    private class RuntimeHolder(
        private val create: () -> VlcRuntime,
        private val teardown: Executor,
    ) : Runnable {

        private var runtime: VlcRuntime? = null
        private var disposed: Boolean = false

        /** The libvlc instance, created on first use. Blocking; call off the main thread. */
        @Synchronized
        fun acquire(): VlcRuntime {
            check(!disposed) { "The engine was disposed" }
            return runtime ?: create().also { runtime = it }
        }

        /** Frees the instance on [teardown], after every session teardown queued before it. */
        override fun run() {
            val released: VlcRuntime = synchronized(this) {
                disposed = true
                runtime.also { runtime = null }
            } ?: return
            teardown.execute { runCatching { released.release() } }
        }
    }

    internal companion object {
        private const val FULL_CACHE_PERCENT = 100f
        private const val TEARDOWN_KEEP_ALIVE_SECONDS = 5L
        private val CLEANER: Cleaner = Cleaner.create()

        /** One serial daemon thread per engine, started on demand and gone when idle. */
        fun newTeardownExecutor(): ExecutorService = ThreadPoolExecutor(
            1,
            1,
            TEARDOWN_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { task: Runnable -> Thread(task, "kmptoolkit-vlcj-teardown").apply { isDaemon = true } }
            .apply { allowCoreThreadTimeOut(true) }
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
