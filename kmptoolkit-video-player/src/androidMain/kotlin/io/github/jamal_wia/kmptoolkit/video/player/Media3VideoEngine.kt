package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.VideoSize as Media3VideoSize

/**
 * [VideoPlaybackEngine] backed by Media3 ExoPlayer.
 *
 * ### Threading
 *
 * ExoPlayer is confined to its *application looper*: it must be created and called on one thread.
 * This engine uses [looper] (the main looper in production — where a `PlayerView` or surface lives)
 * and marshals every call onto it, in the order the calls were made: a call from anywhere else is
 * posted, and a call made on that thread runs at once — unless calls posted earlier are still
 * queued, in which case it is posted behind them. Running it at once then would let it overtake
 * them: a `pause()` on the main thread would land before a `start()` posted from a background
 * thread a moment earlier, and the source would play on while the player above reports Paused.
 * Nothing blocks waiting for the main thread.
 *
 * The SPI polls [durationMs], [positionMs] and [bufferedPositionMs] from the player's coroutine
 * context, which is not the main thread, so they are answered from a [Snapshot] this engine
 * refreshes on the main thread on every player event and on every off-thread poll. Between refreshes
 * the playhead is extrapolated from the snapshot's wall-clock anchor and speed, the way ExoPlayer
 * itself extrapolates — so a poll is exact to within one refresh, not one poll interval, late.
 *
 * ### One ExoPlayer for the engine's lifetime
 *
 * [release] frees the loaded source (and with it the decoders — `stop()` releases them) but keeps the
 * `ExoPlayer`, so a surface attached through `media3PlayerOrNull()` stays attached across
 * `unload()`/`prepare()`. [dispose] releases the `ExoPlayer` itself, once, from the player's final
 * `release()`.
 *
 * @param context application context, for the data sources and the player.
 * @param looper the application looper ExoPlayer is confined to.
 * @param playerFactory builds the `ExoPlayer` on [looper]'s thread — overridden by tests to supply a
 *   Media3 test player with fake renderers.
 * @param mediaSourceFactory turns a [VideoSource] into what ExoPlayer plays — overridden by tests to
 *   supply a fake source; production uses [defaultMediaSource].
 */
// Media3 marks setMediaSource and the media-source factories @UnstableApi: they are what a per-source
// header set and an explicit HLS/progressive choice need, and this is internal code pinned to one Media3
// version by the catalog, so an API change surfaces here at compile time rather than in a consumer.
@OptIn(markerClass = [UnstableApi::class])
internal class Media3VideoEngine(
    private val context: Context,
    private val looper: Looper = Looper.getMainLooper(),
    private val playerFactory: (Context, Looper) -> ExoPlayer = ::defaultExoPlayer,
    private val mediaSourceFactory: (Context, VideoSource) -> MediaSource = ::defaultMediaSource,
) : VideoPlaybackEngine {

    private val handler: Handler = Handler(looper)

    /**
     * How many of this engine's tasks are posted to [looper] and not yet started — transport calls and
     * [load]'s steps alike, so a call on the looper thread knows whether running it at once would
     * overtake an earlier one. An atomic rather than a coroutine tool: the SPI's transport calls are
     * plain synchronous functions, and the count must be right on whichever thread makes one.
     */
    private val queued: AtomicInteger = AtomicInteger(0)
    private val playerDispatcher: CoroutineDispatcher = HandlerDispatcher(::post)

    // Volatile: set through the non-suspending SPI from any thread, read in ExoPlayer callbacks on the
    // looper (D-21).
    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

    /**
     * How many [release] calls have returned whose reset has not yet run on the looper. While it is
     * not zero the listener hears nothing: [release] takes effect for the listener the moment it
     * returns, even though the reset itself is posted, so an event already queued on a busy looper
     * behind the reset cannot reach a player that has let go of the source. A counter, not a flag,
     * so a second [release] cannot be undone by the first one's reset. A [MutableStateFlow] only as
     * an atomic cell, set from any thread by the non-suspending SPI (D-21, D-24).
     */
    private val pendingReleases: MutableStateFlow<Int> = MutableStateFlow(0)

    /** The listener, or `null` once this engine was released or disposed: see [pendingReleases]. */
    private val sink: VideoPlaybackEngineListener?
        get() = if (disposed || pendingReleases.value != 0) null else listener

    // Written only on the looper thread; volatile so media3PlayerOrNull() off that thread sees it.
    @Volatile
    private var player: ExoPlayer? = null

    // Volatile: dispose() is non-suspending and called off the looper; obtainPlayer() reads it there.
    @Volatile
    private var disposed: Boolean = false

    // Looper-thread state.
    private var pendingLoad: CancellableContinuation<Unit>? = null

    /** A source has been handed to the player (loading or loaded): picture sizes are forwarded. */
    private var active: Boolean = false

    /** A load succeeded and nothing has freed it since: completion, failure and buffering are forwarded. */
    private var loaded: Boolean = false
    private var lastBuffering: Boolean = false

    /**
     * Timing answers for other threads. Replaced whole, never mutated, so a reader sees one moment.
     *
     * A [MutableStateFlow] only as an atomic cell (nothing collects it; D-21, D-25): the SPI getters
     * are synchronous and answer from any thread without waiting for the looper. It holds a
     * [SnapshotCell], a plain class that compares by identity, because [Snapshot] is a data class and
     * the looper's refresh needs a compare-and-set that fails when *any* other publish happened since
     * it read, even one that left a value-equal snapshot.
     */
    private val snapshotCell: MutableStateFlow<SnapshotCell> = MutableStateFlow(SnapshotCell(Snapshot.EMPTY))
    private val snapshot: Snapshot get() = snapshotCell.value.snapshot

    // At most one refresh posted at a time, set from whichever thread polls; a non-suspending CAS is
    // all it needs.
    private val refreshPosted: AtomicBoolean = AtomicBoolean(false)

    private val playerListener: Player.Listener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current: ExoPlayer = player ?: return
            when (playbackState) {
                Player.STATE_READY -> pendingLoad?.let { continuation ->
                    pendingLoad = null
                    if (continuation.isActive) {
                        loaded = true
                        refreshSnapshot()
                        continuation.resume(Unit)
                    }
                }

                Player.STATE_ENDED -> if (loaded) {
                    // ExoPlayer keeps playWhenReady at the end, so a later seek would start playback
                    // on its own while the player above says Paused. Drop it: the end is a stop.
                    current.playWhenReady = false
                    reportBuffering(false)
                    refreshSnapshot()
                    sink?.onCompleted()
                    return
                }

                // Buffering is reported below for every state; idle carries nothing to act on here.
                Player.STATE_BUFFERING, Player.STATE_IDLE -> Unit
            }
            if (loaded) reportBuffering(playbackState == Player.STATE_BUFFERING)
        }

        override fun onPlayerError(error: PlaybackException) {
            val waiting: CancellableContinuation<Unit>? = pendingLoad
            if (waiting != null) {
                pendingLoad = null
                if (waiting.isActive) waiting.resumeWithException(error)
                return
            }
            if (!loaded) return
            loaded = false
            lastBuffering = false
            refreshSnapshot()
            sink?.onFailed(error)
        }

        override fun onVideoSizeChanged(videoSize: Media3VideoSize) {
            if (active) sink?.onVideoSizeChanged(videoSize.toVideoSizeOrNull())
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refreshSnapshotUnlessSuperseded()
        }
    }

    override fun setListener(listener: VideoPlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: VideoSource) {
        withContext(playerDispatcher) {
            val current: ExoPlayer = obtainPlayer()
                ?: throw IllegalStateException("The Media3 engine was disposed")
            resetPlayer(current)
            try {
                suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                    pendingLoad = continuation
                    active = true
                    current.setMediaSource(mediaSourceFactory(context, source))
                    current.playWhenReady = false
                    current.prepare()
                }
            } catch (failure: Throwable) {
                // A load failure, a source that would not build, or cancellation: whichever it was,
                // nothing half-loaded may outlive this call. This runs on the looper thread — the
                // dispatcher resumes there even when the cancellation came from elsewhere.
                pendingLoad = null
                resetPlayer(current)
                throw failure
            }
        }
    }

    override fun start() {
        onPlayerThread { player?.play() }
    }

    override fun pause() {
        // Freeze the extrapolated playhead now, so the position read right after this call — from
        // whatever thread — is where playback stopped rather than where it would have got to.
        onPlayerThread(
            publish = {
                publishSnapshot { frozen: Snapshot ->
                    frozen.copy(positionMs = frozen.extrapolatedPosition(), atRealtimeMs = now(), advancing = false)
                }
            },
        ) { player?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        onPlayerThread(
            publish = { publishSnapshot { it.copy(positionMs = positionMs, atRealtimeMs = now()) } },
        ) { player?.seekTo(positionMs) }
    }

    override fun setSpeed(speed: Float) {
        onPlayerThread { player?.setPlaybackSpeed(speed) }
    }

    override fun setVolume(volume: Float) {
        onPlayerThread { player?.volume = volume }
    }

    override fun setLooping(looping: Boolean) {
        onPlayerThread {
            player?.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    override fun durationMs(): Long = currentSnapshot().durationMs

    override fun positionMs(): Long = currentSnapshot().extrapolatedPosition()

    override fun bufferedPositionMs(): Long = currentSnapshot().bufferedPositionMs

    override fun release() {
        // Counted before anything else and uncounted by the reset itself, so the listener is silent
        // from the moment this returns, not from the moment the posted reset gets its turn.
        pendingReleases.update { it + 1 }
        snapshotCell.value = SnapshotCell(Snapshot.EMPTY)
        onPlayerThread {
            try {
                pendingLoad?.cancel()
                pendingLoad = null
                player?.let(::resetPlayer)
            } finally {
                pendingReleases.update { it - 1 }
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        onPlayerThread {
            val current: ExoPlayer = player ?: return@onPlayerThread
            player = null
            current.removeListener(playerListener)
            current.release()
        }
    }

    /**
     * The `ExoPlayer` for a surface to attach to. Created on first use when called on the looper
     * thread (the main thread, where a surface is set up); off it, whatever exists already.
     */
    fun playerForSurface(): ExoPlayer? = if (Looper.myLooper() === looper) obtainPlayer() else player

    /** Looper thread only. */
    private fun obtainPlayer(): ExoPlayer? {
        if (disposed) return null
        return player ?: playerFactory(context, looper).also { created: ExoPlayer ->
            created.addListener(playerListener)
            player = created
        }
    }

    /** Looper thread only. Drops the source and every trace of it, keeping the player. */
    private fun resetPlayer(current: ExoPlayer) {
        active = false
        loaded = false
        lastBuffering = false
        current.playWhenReady = false
        current.stop()
        current.clearMediaItems()
        snapshotCell.value = SnapshotCell(Snapshot.EMPTY)
    }

    private fun reportBuffering(isBuffering: Boolean) {
        if (isBuffering == lastBuffering) return
        lastBuffering = isBuffering
        sink?.onBufferingChanged(isBuffering)
    }

    /** Looper thread only. */
    private fun refreshSnapshot() {
        snapshotCell.value = SnapshotCell(readPlayerSnapshot())
    }

    /** Looper thread only. The player's own timing, as a [Snapshot]. */
    private fun readPlayerSnapshot(): Snapshot {
        val current: ExoPlayer? = player
        return if (current == null || !loaded) {
            Snapshot.EMPTY
        } else {
            Snapshot(
                durationMs = current.duration.orZero(),
                positionMs = current.currentPosition.coerceAtLeast(0L),
                bufferedPositionMs = current.bufferedPosition.coerceAtLeast(0L),
                atRealtimeMs = now(),
                advancing = current.isPlaying,
                speed = current.playbackParameters.speed,
            )
        }
    }

    /**
     * Refreshes the [snapshot] from the player unless a [seekTo] or [pause] is, or becomes, newer than
     * what the player can describe: it then holds the moment before that call, and would put its
     * pre-seek playhead (or its still-playing state) over what the call already published.
     *
     * The cell is read first, then [queued], then the player; the result is published only if the cell
     * is still the one read. A call that counted itself in [queued] before the check suppresses the
     * refresh; a call that published after the read makes the compare-and-set fail. Either way the
     * call's snapshot survives, and the refresh that follows the call's own player events replaces it.
     * Writers must therefore keep the order count in [queued], then publish. Looper thread only.
     */
    private fun refreshSnapshotUnlessSuperseded() {
        val seen: SnapshotCell = snapshotCell.value
        if (queued.get() != 0) return
        snapshotCell.compareAndSet(seen, SnapshotCell(readPlayerSnapshot()))
    }

    /** Replaces the [snapshot] with [transform] of it, atomically with respect to other publishes. */
    private inline fun publishSnapshot(crossinline transform: (Snapshot) -> Snapshot) {
        snapshotCell.update { cell: SnapshotCell -> SnapshotCell(transform(cell.snapshot)) }
    }

    /**
     * The freshest snapshot this thread can get: exact on the looper thread, else cached plus a
     * refresh. Exact means nothing is still queued: until queued calls have run, the player describes
     * the moment before them, and the snapshot — which [pause] and [seekTo] update as they are called
     * — is the truer answer.
     */
    private fun currentSnapshot(): Snapshot {
        if (Looper.myLooper() === looper && queued.get() == 0) {
            // Not refreshSnapshot(): a seek or pause from another thread can be counted and published
            // between the check above and the read of the player.
            refreshSnapshotUnlessSuperseded()
        } else if (refreshPosted.compareAndSet(false, true)) {
            handler.post {
                refreshPosted.set(false)
                refreshSnapshotUnlessSuperseded()
            }
        }
        return snapshot
    }

    /**
     * Runs [block] on the looper thread after every call made before it — see the class KDoc.
     *
     * [publish] updates the [snapshot] for this call. It runs after the call is counted in [queued]
     * and before it is posted, which is the order [refreshSnapshotUnlessSuperseded] relies on to never
     * overwrite it.
     */
    private inline fun onPlayerThread(crossinline publish: () -> Unit = {}, crossinline block: () -> Unit) {
        if (Looper.myLooper() === looper && queued.get() == 0) {
            publish()
            guarded(block)
        } else {
            queued.incrementAndGet()
            publish()
            enqueueCounted { guarded(block) }
        }
    }

    /**
     * Queues [task] on the looper, counted in [queued] until it is taken off the queue. Uncounted
     * before it runs rather than after: a call made from inside it — a resumed [load] running the
     * rest of the caller's `prepare` — comes after it, and only what is still queued may hold it back.
     */
    private fun post(task: Runnable) {
        queued.incrementAndGet()
        enqueueCounted(task)
    }

    /** Posts [task] for a call already counted in [queued]. */
    private fun enqueueCounted(task: Runnable) {
        handler.post {
            queued.decrementAndGet()
            task.run()
        }
    }

    /**
     * Runs [block], swallowing the platform's "wrong state" — the SPI requires transport calls to
     * tolerate one rather than crash the caller (ExoPlayer rejects calls on a released instance).
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalStateException) {
            // The player is gone or not in a state to take the call; there is nothing to apply it to.
        }
    }

    /** Identity box for a [Snapshot]: deliberately not a data class, see [snapshotCell]. */
    private class SnapshotCell(val snapshot: Snapshot)

    /**
     * Timing as of [atRealtimeMs]. While [advancing], the playhead moves at [speed] from [positionMs].
     */
    private data class Snapshot(
        val durationMs: Long,
        val positionMs: Long,
        val bufferedPositionMs: Long,
        val atRealtimeMs: Long,
        val advancing: Boolean,
        val speed: Float,
    ) {
        fun extrapolatedPosition(): Long {
            if (!advancing) return positionMs
            val elapsed: Long = (now() - atRealtimeMs).coerceAtLeast(0L)
            val position: Long = positionMs + (elapsed * speed).toLong()
            return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
        }

        companion object {
            val EMPTY: Snapshot = Snapshot(0L, 0L, 0L, 0L, advancing = false, speed = 1f)
        }
    }

    /**
     * Resumes coroutines on the looper — a two-line stand-in for kotlinx-coroutines-android's
     * `Handler.asCoroutineDispatcher()`, posting through [post] so [load]'s steps are counted with
     * the transport calls and keep their place in the same order.
     */
    private class HandlerDispatcher(private val post: (Runnable) -> Unit) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            post(block)
        }
    }
}

private fun now(): Long = SystemClock.elapsedRealtime()

private fun Long.orZero(): Long = if (this == C.TIME_UNSET || this < 0L) 0L else this

// setLooper is @UnstableApi; the engine confines ExoPlayer to that looper (see the class KDoc).
@OptIn(markerClass = [UnstableApi::class])
private fun defaultExoPlayer(context: Context, looper: Looper): ExoPlayer =
    ExoPlayer.Builder(context).setLooper(looper).build()

/**
 * The production source for [source]: one [DefaultDataSource] routing `asset://`, `file://` and
 * `http(s)://` alike — the last through a [DefaultHttpDataSource] carrying the source's headers — and
 * the media source its [RemoteFormat] asks for (see [toMediaItem]). With [RemoteFormat.Auto] a
 * [DefaultMediaSourceFactory] decides from the URI: HLS for a `.m3u8` path, because
 * `media3-exoplayer-hls` is on the classpath, and a progressive source for anything else.
 */
@OptIn(markerClass = [UnstableApi::class])
internal fun defaultMediaSource(context: Context, source: VideoSource): MediaSource {
    val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
    val headers: Map<String, String> = source.requestHeaders()
    if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
    val dataSources = DefaultDataSource.Factory(context, http)
    val item: MediaItem = source.toMediaItem()
    return if (source.remoteFormat() == RemoteFormat.Progressive) {
        // A .m3u8 path would still make the default factory pick HLS; progressive was asked for.
        ProgressiveMediaSource.Factory(dataSources).createMediaSource(item)
    } else {
        DefaultMediaSourceFactory(dataSources).createMediaSource(item)
    }
}

/**
 * The [MediaItem] for [this] source. For [RemoteFormat.Hls] it carries the HLS MIME type, which
 * [DefaultMediaSourceFactory] honours ahead of the URI — so an HLS stream whose URL does not end in
 * `.m3u8` (a signed or rewritten URL) is still played as HLS rather than failing as a progressive file.
 */
internal fun VideoSource.toMediaItem(): MediaItem {
    val builder: MediaItem.Builder = MediaItem.Builder().setUri(toMediaUri())
    if (remoteFormat() == RemoteFormat.Hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    return builder.build()
}

private fun VideoSource.remoteFormat(): RemoteFormat? = (this as? VideoSource.Remote)?.format

/**
 * The URI Media3 opens for [this] source: `asset:///path` for a bundled asset (AssetDataSource strips
 * the leading slash), `file://` for a device file, the URL itself for a remote source. Built with
 * [Uri.Builder] so a file or asset name holding `#`, `?` or a space is encoded rather than cut short.
 */
internal fun VideoSource.toMediaUri(): Uri = when (this) {
    is VideoSource.Asset -> Uri.Builder().scheme("asset").authority("").path("/" + path.trimStart('/')).build()
    is VideoSource.File -> Uri.fromFile(java.io.File(path))
    is VideoSource.Remote -> Uri.parse(url)
}

/** Extra HTTP headers for [this] source; empty for anything that is not [VideoSource.Remote]. */
internal fun VideoSource.requestHeaders(): Map<String, String> =
    (this as? VideoSource.Remote)?.headers ?: emptyMap()

/**
 * The displayed picture size: the stored width stretched by the pixel aspect ratio (anamorphic
 * sources store non-square pixels), or `null` for Media3's "no picture" (a zero dimension).
 */
internal fun Media3VideoSize.toVideoSizeOrNull(): VideoSize? {
    if (width <= 0 || height <= 0) return null
    val ratio: Float = pixelWidthHeightRatio.takeIf { it > 0f && it.isFinite() } ?: 1f
    val displayWidth: Int = (width * ratio).roundToInt()
    return if (displayWidth > 0) VideoSize(displayWidth, height) else null
}
