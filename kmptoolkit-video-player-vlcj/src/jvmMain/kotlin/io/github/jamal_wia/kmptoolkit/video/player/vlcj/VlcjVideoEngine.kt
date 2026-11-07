package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.Cleaner
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
 *   a safety net for a player that is dropped without being released: once the engine is
 *   garbage-collected it releases every native player still open and then the instance, rather
 *   than leaking them for the life of the process. A player that is still playing is not
 *   collectable (the position poller keeps it reachable), so the net does not stop it: release it.
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
 *   listener reacting synchronously) is submitted to VLCJ's task thread with
 *   [VlcNativePlayer.submit], and a getter answers from the cache.
 * - Transport calls reach libvlc in the order they were made, whichever threads made them. A call
 *   runs at once only when nothing of its session is still waiting on the task thread; otherwise it
 *   is submitted behind what is (see [inOrder]). Each call's guards — not started yet, already at
 *   the end — are decided when it runs, against the state the calls before it left, never when it
 *   was made. Running a later call at once instead would let it overtake: a restart submitted from
 *   `onCompleted` on the event thread, then a `pause()` from the UI that found the source still
 *   ended and was dropped, then the restart playing on while the player above reported Paused.
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
    private val nanoTime: () -> Long = System::nanoTime,
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
            inOrder(s) {
                if (!s.loaded) return@inOrder
                s.player?.play()
                s.started = true
                // From the end, play() replays the source: it is not at the end any more from here
                // on, so a pause() made after this start is applied rather than dropped as "ended".
                s.ended = false
            }
        }
    }

    override fun pause() {
        withOpenSession { s ->
            inOrder(s) {
                if (!s.loaded || !s.started || s.ended) return@inOrder
                s.player?.pause()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val target: Long = positionMs.coerceAtLeast(0L)
        withOpenSession { s ->
            if (!s.loaded) return@withOpenSession
            // Reported at once, and held until libvlc has applied the seek (see Session.holdsSeekTarget):
            // whatever else of this session is still queued, or a clock reading taken before the
            // seek, would otherwise put the old playhead back over the target.
            s.seeksInFlight++
            s.seekTargetMs = target
            s.seekHoldUntilNanos = 0L
            s.timeMs = target
            inOrder(s) {
                try {
                    // Before the first start, and after the end, libvlc has no running input to seek
                    // in: remember the target and apply it when playback begins. Nothing starts
                    // playing here.
                    if (!s.started || s.ended) {
                        s.pendingSeekMs = target
                        s.timeMs = target
                    } else {
                        s.player?.setTime(target)
                        s.timeMs = target
                        // libvlc 3 applies set_time asynchronously on its input thread, so the clock
                        // may still read the old time for a moment after the call returned.
                        s.seekHoldUntilNanos = nanoTime() + SEEK_HOLD_NANOS
                    }
                } finally {
                    s.seeksInFlight--
                }
            }
        }
    }

    // The value is stored first and applySettings() reads the latest one when it runs, so whichever
    // call runs last applies the last value set.

    override fun setSpeed(speed: Float) {
        this.speed = speed
        withOpenSession { s -> inOrder(s) { if (s.started) s.applySettings() } }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        withOpenSession { s -> inOrder(s) { if (s.started) s.applySettings() } }
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
        // A seek still queued (RepeatMode.One restarts from the end this way) outranks "ended".
        val inFlight: Long? = synchronized(lock) { if (s.seeksInFlight > 0) s.seekTargetMs else null }
        if (inFlight != null) return inFlight
        if (s.ended) return durationMs()
        if (s.started && !onVlcThread.get()) {
            withOpenSession { open ->
                // The seek target stands until libvlc reports a time near it (see holdsSeekTarget).
                if (open.holdsSeekTarget()) return@withOpenSession
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

    /**
     * [lock] held, [s] open. Runs [action] for [s] after every call of [s] made before it: at once
     * when nothing of [s] is waiting on VLCJ's task thread (and this is not a VLC thread, which must
     * not call into libvlc), otherwise submitted to that thread — one thread, first in first out —
     * behind what is. The count is changed only under [lock], so a call checking it and a submitted
     * action starting cannot interleave; a submitted action runs under [lock] too, and only while
     * [s] is still the open session.
     */
    private inline fun inOrder(s: Session, crossinline action: () -> Unit) {
        if (!onVlcThread.get() && s.queued == 0) {
            action()
            return
        }
        val player: VlcNativePlayer = s.player ?: return
        s.queued++
        player.submit {
            synchronized(lock) {
                s.queued--
                if (session === s && !s.closed) action()
            }
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
        // Written by the loading thread under [lock], read by the teardown thread without it; both
        // are blocking threads outside any coroutine, so a volatile is the whole need (D-21).
        @Volatile var nativeSession: NativeSession? = null
        @Volatile var closed: Boolean = false
        @Volatile var loaded: Boolean = false
        @Volatile var started: Boolean = false
        @Volatile var ended: Boolean = false
        @Volatile var pendingSeekMs: Long = -1L
        @Volatile var lengthMs: Long = 0L
        @Volatile var timeMs: Long = 0L
        @Volatile var bufferingReported: Boolean = false
        @Volatile var reportedSize: VideoSize? = null

        /** This session's calls submitted by [inOrder] that have not started yet. Guarded by [lock]. */
        var queued: Int = 0

        /**
         * Seeks [seekTo] has accepted whose `setTime` has not run yet, and the target of the latest.
         * Guarded by [lock], like [seekHoldUntilNanos].
         */
        var seeksInFlight: Int = 0
        var seekTargetMs: Long = 0L

        /** `System.nanoTime()` until which the target is still held after the seek ran; 0 for none. */
        var seekHoldUntilNanos: Long = 0L

        /**
         * Whether the playhead is the latest seek's target rather than a clock reading: from the
         * call until libvlc reports a time near the target, or [SEEK_HOLD_NANOS] after `setTime`
         * ran, whichever comes first. [lock] held.
         */
        fun holdsSeekTarget(): Boolean =
            seeksInFlight > 0 || (seekHoldUntilNanos != 0L && nanoTime() - seekHoldUntilNanos < 0L)

        /** The displayed size parsing found; when known, the decoder's buffer size never replaces it. */
        @Volatile var parsedSize: VideoSize? = null

        private val callbacks: Callbacks = Callbacks(this)

        /** Creates the native player and starts parsing. Blocking; call off the main thread. */
        fun open() {
            val libvlc: VlcRuntime = runtime.acquire()
            synchronized(lock) {
                if (closed) throw CancellationException("The load was abandoned")
                // The native side gets only a weak forwarder: whatever libvlc, VLCJ or JNA hold must
                // not keep this engine reachable, or the Cleaner could never run (see RuntimeHolder).
                val created: VlcNativePlayer = libvlc.newPlayer(WeakVlcCallbacks(callbacks))
                player = created
                val opened = NativeSession(created, media)
                nativeSession = opened
                runtime.track(opened)
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

        /**
         * Re-applies the latest volume and rate, which libvlc only accepts once an output exists.
         * Never on a VLC thread: callers go through [inOrder].
         */
        fun applySettings() {
            val current: VlcNativePlayer = player ?: return
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

        /**
         * Runs on the [teardown] thread: native player first, then the media it had open. The
         * [RuntimeHolder] may have claimed the native session already: [dispose] queues the
         * holder's own close of every open session right behind this task, and whichever of the two
         * runs first wins [RuntimeHolder.claim]. (Not because the engine was collected: this queued
         * task holds the session, and so the engine.)
         */
        fun teardown() {
            val opened: NativeSession? = nativeSession
            if (opened == null) {
                media.discard()
            } else if (runtime.claim(opened)) {
                opened.close()
            }
        }
    }

    private inner class Callbacks(private val s: Session) : VlcPlayerCallbacks {

        override fun playing() = onVlcCallback {
            s.ended = false
            // libvlc accepts a volume and a seek only once the output exists — i.e. now. Not from
            // this thread, though: VLCJ's task thread may call into libvlc.
            // Through the same ordered queue as the transport calls, so a seek the app makes once
            // output began cannot be overtaken, and then undone, by the pending one applied here.
            withOpenSession { open ->
                if (open !== s) return@withOpenSession
                inOrder(s) {
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
            if (timeMs < 0L || s.pendingSeekMs >= 0L) return@onVlcCallback
            synchronized(lock) {
                if (s.holdsSeekTarget()) {
                    // A reading taken before the seek was applied lands after it: not the playhead.
                    if (s.seeksInFlight > 0 || abs(timeMs - s.seekTargetMs) > SEEK_SETTLED_TOLERANCE_MS) {
                        return@onVlcCallback
                    }
                    s.seekHoldUntilNanos = 0L
                }
                s.timeMs = timeMs
            }
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
     * Owns the libvlc instance and every native player still open. Also the [Cleaner] action, so
     * it must never reference the engine; that is also what keeps the native players, and the JNA
     * callbacks inside them, strongly reachable until they are released.
     */
    private class RuntimeHolder(
        private val create: () -> VlcRuntime,
        private val teardown: Executor,
    ) : Runnable {

        private var runtime: VlcRuntime? = null
        private var disposed: Boolean = false
        // A concurrent set: written by loading threads, read by the Cleaner thread or the dispose()
        // caller (which only iterate it and queue claim tasks), emptied by the teardown thread.
        // None of them is a coroutine or may suspend, so a Mutex does not fit; `remove` returning true is what makes [claim] an atomic one-winner guard (D-21).
        private val openSessions: MutableSet<NativeSession> = ConcurrentHashMap.newKeySet()

        /** Registers a native player created from this holder's libvlc instance. */
        fun track(session: NativeSession) {
            openSessions += session
        }

        /** Takes [session] over for its teardown; `false` when someone else already did. */
        fun claim(session: NativeSession): Boolean = openSessions.remove(session)

        /** The libvlc instance, created on first use. Blocking; call off the main thread. */
        @Synchronized
        fun acquire(): VlcRuntime {
            check(!disposed) { "The engine was disposed" }
            return runtime ?: create().also { runtime = it }
        }

        /**
         * Frees what is left on [teardown]: every native player still open (an engine collected
         * without `release()`), then the instance, after every session teardown queued before it.
         */
        override fun run() {
            // Iterated directly, not copied with toList(): that checks the size and then reads the
            // first element, and throws NoSuchElementException when the teardown thread claims the
            // last session in between. The set's iterator is weakly consistent and never throws.
            for (session: NativeSession in openSessions) {
                teardown.execute { if (claim(session)) session.close() }
            }
            val released: VlcRuntime = synchronized(this) {
                disposed = true
                runtime.also { runtime = null }
            } ?: return
            teardown.execute { runCatching { released.release() } }
        }
    }

    internal companion object {
        private const val FULL_CACHE_PERCENT = 100f

        /** How long a seek's target is held after `setTime` ran, if libvlc reports no time near it. */
        private const val SEEK_HOLD_NANOS = 500_000_000L

        /** A reported time this close to the target (a keyframe may round it) ends the hold. */
        private const val SEEK_SETTLED_TOLERANCE_MS = 1_000L
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

/** A native player and the media it has open: what must be freed together. */
internal class NativeSession(private val player: VlcNativePlayer, private val media: ResolvedMedia) {

    /** Stops and releases the native player, then discards the media. Call once, after claiming. */
    fun close() {
        runCatching { player.stopParsing() }
        runCatching { player.stop() }
        runCatching { player.release() }
        media.discard()
    }
}

/**
 * Forwards to [target] while it is alive and does nothing after. What libvlc's event and render
 * callbacks reach, so that no native-side structure keeps the engine reachable.
 */
internal class WeakVlcCallbacks(target: VlcPlayerCallbacks) : VlcPlayerCallbacks {

    private val reference: WeakReference<VlcPlayerCallbacks> = WeakReference(target)

    private inline fun forward(block: VlcPlayerCallbacks.() -> Unit) {
        reference.get()?.block()
    }

    override fun playing() = forward { playing() }
    override fun paused() = forward { paused() }
    override fun stopped() = forward { stopped() }
    override fun finished() = forward { finished() }
    override fun error() = forward { error() }
    override fun buffering(cachePercent: Float) = forward { buffering(cachePercent) }
    override fun lengthChanged(lengthMs: Long) = forward { lengthChanged(lengthMs) }
    override fun timeChanged(timeMs: Long) = forward { timeChanged(timeMs) }
    override fun parsed(status: VlcParseStatus) = forward { parsed(status) }
    override fun durationChanged(durationMs: Long) = forward { durationChanged(durationMs) }
    override fun bufferFormat(width: Int, height: Int) = forward { bufferFormat(width, height) }
    override fun display(pixels: ByteBuffer, width: Int, height: Int) = forward { display(pixels, width, height) }
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
