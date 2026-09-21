package io.github.jamal_wia.kmptoolkit.video.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import androidx.media3.common.VideoSize as Media3VideoSize

/**
 * [VideoPlaybackEngine] backed by Media3 ExoPlayer.
 *
 * ### Threading
 *
 * ExoPlayer is confined to its *application looper*: it must be created and called on one thread.
 * This engine uses [looper] (the main looper in production — where a `PlayerView` or surface lives)
 * and marshals every call onto it: a call made on that thread runs at once, a call from anywhere else
 * is posted, in order. Nothing blocks waiting for the main thread.
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
internal class Media3VideoEngine(
    private val context: Context,
    private val looper: Looper = Looper.getMainLooper(),
    private val playerFactory: (Context, Looper) -> ExoPlayer = ::defaultExoPlayer,
    private val mediaSourceFactory: (Context, VideoSource) -> MediaSource = ::defaultMediaSource,
) : VideoPlaybackEngine, DisposableVideoPlaybackEngine {

    private val handler: Handler = Handler(looper)
    private val playerDispatcher: CoroutineDispatcher = HandlerDispatcher(handler)

    @Volatile
    private var listener: VideoPlaybackEngineListener? = null

    // Written only on the looper thread; volatile so media3PlayerOrNull() off that thread sees it.
    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var disposed: Boolean = false

    // Looper-thread state.
    private var pendingLoad: CancellableContinuation<Unit>? = null

    /** A source has been handed to the player (loading or loaded): picture sizes are forwarded. */
    private var active: Boolean = false

    /** A load succeeded and nothing has freed it since: completion, failure and buffering are forwarded. */
    private var loaded: Boolean = false
    private var lastBuffering: Boolean = false

    /** Timing answers for other threads. Replaced whole, never mutated, so a reader sees one moment. */
    @Volatile
    private var snapshot: Snapshot = Snapshot.EMPTY
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
                    listener?.onCompleted()
                    return
                }
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
            listener?.onFailed(error)
        }

        override fun onVideoSizeChanged(videoSize: Media3VideoSize) {
            if (active) listener?.onVideoSizeChanged(videoSize.toVideoSizeOrNull())
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refreshSnapshot()
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
        val frozen: Snapshot = snapshot
        snapshot = frozen.copy(positionMs = frozen.extrapolatedPosition(), atRealtimeMs = now(), advancing = false)
        onPlayerThread { player?.pause() }
    }

    override fun seekTo(positionMs: Long) {
        snapshot = snapshot.copy(positionMs = positionMs, atRealtimeMs = now())
        onPlayerThread { player?.seekTo(positionMs) }
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
        snapshot = Snapshot.EMPTY
        onPlayerThread {
            pendingLoad?.cancel()
            pendingLoad = null
            player?.let(::resetPlayer)
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
        snapshot = Snapshot.EMPTY
    }

    private fun reportBuffering(isBuffering: Boolean) {
        if (isBuffering == lastBuffering) return
        lastBuffering = isBuffering
        listener?.onBufferingChanged(isBuffering)
    }

    /** Looper thread only. */
    private fun refreshSnapshot() {
        val current: ExoPlayer? = player
        snapshot = if (current == null || !loaded) {
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

    /** The freshest snapshot this thread can get: exact on the looper thread, else cached plus a refresh. */
    private fun currentSnapshot(): Snapshot {
        if (Looper.myLooper() === looper) {
            refreshSnapshot()
        } else if (refreshPosted.compareAndSet(false, true)) {
            handler.post {
                refreshPosted.set(false)
                refreshSnapshot()
            }
        }
        return snapshot
    }

    private inline fun onPlayerThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() === looper) {
            guarded(block)
        } else {
            handler.post { guarded(block) }
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

    /** Resumes coroutines on the looper — a two-line stand-in for kotlinx-coroutines-android's `Handler.asCoroutineDispatcher()`. */
    private class HandlerDispatcher(private val handler: Handler) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            handler.post(block)
        }
    }
}

private fun now(): Long = SystemClock.elapsedRealtime()

private fun Long.orZero(): Long = if (this == C.TIME_UNSET || this < 0L) 0L else this

private fun defaultExoPlayer(context: Context, looper: Looper): ExoPlayer =
    ExoPlayer.Builder(context).setLooper(looper).build()

/**
 * The production source for [source]: one [DefaultDataSource] routing `asset://`, `file://` and
 * `http(s)://` alike — the last through a [DefaultHttpDataSource] carrying the source's headers — and
 * a [DefaultMediaSourceFactory], which picks HLS for a `.m3u8` URI because `media3-exoplayer-hls` is
 * on the classpath and a progressive source for anything else.
 */
internal fun defaultMediaSource(context: Context, source: VideoSource): MediaSource {
    val http: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
    val headers: Map<String, String> = source.requestHeaders()
    if (headers.isNotEmpty()) http.setDefaultRequestProperties(headers)
    val dataSources = DefaultDataSource.Factory(context, http)
    return DefaultMediaSourceFactory(dataSources).createMediaSource(MediaItem.fromUri(source.toMediaUri()))
}

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
