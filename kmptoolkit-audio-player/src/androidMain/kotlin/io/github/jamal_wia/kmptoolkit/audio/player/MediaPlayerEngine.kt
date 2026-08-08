package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [PlaybackEngine] backed by [android.media.MediaPlayer].
 *
 * `MediaPlayer` rather than Media3/ExoPlayer on purpose: this module is a small playback seam, and
 * ExoPlayer would add megabytes of transitively-published dependency to every consumer that only
 * wanted to play a notification chime. A consumer who needs ExoPlayer's format support can supply
 * their own [PlaybackEngine] — that is what the interface is public for.
 *
 * @param context application context, used only to open [AudioSource.Asset] file descriptors.
 */
internal class MediaPlayerEngine(
    private val context: Context,
) : PlaybackEngine {

    private var mediaPlayer: MediaPlayer? = null
    private var listener: PlaybackEngineListener? = null

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override suspend fun load(source: AudioSource) {
        release()

        // Created off the main thread deliberately: setDataSource does blocking I/O for a file or a
        // URL. MediaPlayer delivers its callbacks on the main looper when the creating thread has
        // no Looper of its own, which is exactly what happens here — so this needs no main-thread
        // dispatcher and the module needs no kotlinx-coroutines-android dependency.
        val player: MediaPlayer = withContext(Dispatchers.IO) {
            MediaPlayer().also { player: MediaPlayer ->
                try {
                    player.attachSource(source)
                } catch (failure: Throwable) {
                    player.release()
                    throw failure
                }
            }
        }
        mediaPlayer = player

        try {
            suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                player.setOnPreparedListener { continuation.resume(Unit) }
                player.setOnErrorListener { _, what: Int, extra: Int ->
                    continuation.resumeWithException(mediaPlayerError(what, extra))
                    true
                }
                player.prepareAsync()
            }
        } catch (failure: Throwable) {
            // Covers both a prepare error and cancellation of the awaiting coroutine: either way the
            // half-prepared player must not outlive this call.
            release()
            throw failure
        }

        // Swap the one-shot preparation callbacks for the steady-state ones. Doing it here rather
        // than before prepareAsync() keeps a load failure out of onFailed(): the exception above is
        // the single, already-awaited report of it.
        player.setOnPreparedListener(null)
        player.setOnCompletionListener { listener?.onCompleted() }
        player.setOnErrorListener { _, what: Int, extra: Int ->
            listener?.onFailed(mediaPlayerError(what, extra))
            true
        }
    }

    override fun start() {
        withPlayer { player -> if (!player.isPlaying) player.start() }
    }

    override fun pause() {
        withPlayer { player -> if (player.isPlaying) player.pause() }
    }

    override fun seekTo(positionMs: Long) {
        withPlayer { player -> player.seekTo(positionMs.toInt()) }
    }

    override fun setSpeed(speed: Float) {
        // Only while playing: assigning playbackParams to a paused MediaPlayer starts playback on
        // several API levels, which would turn "choose a speed" into "start playing".
        withPlayer { player ->
            if (player.isPlaying) player.playbackParams = player.playbackParams.setSpeed(speed)
        }
    }

    override fun durationMs(): Long = withPlayer { player ->
        player.duration.toLong().coerceAtLeast(0L)
    } ?: 0L

    override fun positionMs(): Long = withPlayer { player ->
        player.currentPosition.toLong().coerceAtLeast(0L)
    } ?: 0L

    override fun release() {
        val player: MediaPlayer = mediaPlayer ?: return
        mediaPlayer = null
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        try {
            player.release()
        } catch (_: IllegalStateException) {
            // Already torn down by the platform; the handle is gone either way.
        }
    }

    private fun MediaPlayer.attachSource(source: AudioSource) {
        when (source) {
            is AudioSource.Asset ->
                context.assets.openFd(source.path).use { descriptor: AssetFileDescriptor ->
                    setDataSource(
                        descriptor.fileDescriptor,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                }

            is AudioSource.File -> setDataSource(source.path)
            is AudioSource.Remote -> setDataSource(source.url)
        }
    }

    /**
     * Runs [block] against the live player, or returns `null` if there is none.
     *
     * `MediaPlayer` throws `IllegalStateException` for a call made in a state it did not expect —
     * after an error, or between a release and the state update above it. Swallowing that here is
     * what lets the player above stay a pure state machine, as [PlaybackEngine] requires.
     */
    private inline fun <T> withPlayer(block: (MediaPlayer) -> T): T? {
        val player: MediaPlayer = mediaPlayer ?: return null
        return try {
            block(player)
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun mediaPlayerError(what: Int, extra: Int): IOException =
        IOException("MediaPlayer failed: what=$what extra=$extra")
}
