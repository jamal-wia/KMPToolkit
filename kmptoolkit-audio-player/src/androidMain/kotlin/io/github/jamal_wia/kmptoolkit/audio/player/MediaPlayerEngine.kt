package io.github.jamal_wia.kmptoolkit.audio.player

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
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

    // The live handle: published from Dispatchers.IO during load, read by transport calls on the
    // caller's thread, by the platform's callbacks (as the fence below) and cleared by release()
    // wherever that runs. A state holder rather than a @Volatile field (D-21/D-24).
    private val mediaPlayer: MutableStateFlow<MediaPlayer?> = MutableStateFlow(null)
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
        val player: MediaPlayer
        try {
            player = withContext(Dispatchers.IO) {
                // Published before setDataSource, so that a cancellation landing while this block is
                // still running — withContext then discards its result — cannot orphan the handle:
                // the catch below releases whatever is published.
                MediaPlayer().also { created: MediaPlayer ->
                    mediaPlayer.value = created
                    created.attachSource(source)
                }
            }

            suspendCancellableCoroutine { continuation: CancellableContinuation<Unit> ->
                // Guarded: MediaPlayer can report an error after it reported prepared, before the
                // steady-state listeners below replace these, and resuming twice would crash.
                player.setOnPreparedListener { if (continuation.isActive) continuation.resume(Unit) }
                player.setOnErrorListener { _, what: Int, extra: Int ->
                    val error: IOException = mediaPlayerError(what, extra)
                    // Awaited: the load fails. Resumed by prepared: report it as a playback failure -
                    // the player honours that until it publishes Ready - rather than keep it for a
                    // later check that could miss it. Cancelled (superseded, caller cancelled,
                    // unloaded): drop it, nobody failed and the cancel path reports Idle.
                    if (continuation.isActive) continuation.resumeWithException(error)
                    else if (!continuation.isCancelled && isCurrent(player)) listener?.onFailed(error)
                    true
                }
                player.prepareAsync()
            }
        } catch (failure: Throwable) {
            // Covers a source that would not attach, a prepare error, and cancellation of the
            // awaiting coroutine: whichever it was, the half-prepared player must not outlive this call.
            release()
            throw failure
        }

        // Swap the one-shot preparation callbacks for the steady-state ones. Doing it here rather
        // than before prepareAsync() keeps a load failure out of onFailed(): the exception above is
        // the single, already-awaited report of it.
        player.setOnPreparedListener(null)
        // Fenced by handle: the platform reads a player's listener on the main looper while load()
        // or release() clears it on another thread, so a callback of a player the engine has already
        // replaced can still arrive. Unfenced it would complete or fail the source that replaced it.
        player.setOnCompletionListener { if (isCurrent(player)) listener?.onCompleted() }
        player.setOnErrorListener { _, what: Int, extra: Int ->
            if (isCurrent(player)) listener?.onFailed(mediaPlayerError(what, extra))
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
        // MediaPlayer.seekTo takes an Int. A relative skip in a source of unknown length arrives unclamped
        // (up to Long.MAX_VALUE), and truncating it would wrap to a negative or tiny value. Saturating
        // gives the platform's own "far end": it clamps to the duration once it knows one.
        withPlayer { player -> player.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
    }

    override fun setSpeed(speed: Float) {
        // Only while playing: assigning playbackParams to a paused MediaPlayer starts playback on
        // several API levels, which would turn "choose a speed" into "start playing".
        withPlayer { player ->
            if (player.isPlaying) {
                try {
                    player.playbackParams = player.playbackParams.setSpeed(speed)
                } catch (_: IllegalArgumentException) {
                    // The device refuses this rate. Playback goes on at the previous one: a crash, or a
                    // player state out of step with audio that is already playing, would be worse.
                }
            }
        }
    }

    override fun durationMs(): Long = withPlayer { player ->
        player.duration.toLong().coerceAtLeast(0L)
    } ?: 0L

    override fun positionMs(): Long = withPlayer { player ->
        player.currentPosition.toLong().coerceAtLeast(0L)
    } ?: 0L

    override fun release() {
        val player: MediaPlayer = mediaPlayer.getAndUpdate { null } ?: return
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
        val player: MediaPlayer = mediaPlayer.value ?: return null
        return try {
            block(player)
        } catch (_: IllegalStateException) {
            null
        }
    }

    /** Whether [player] is still the engine's live handle, i.e. not replaced or released since. */
    private fun isCurrent(player: MediaPlayer): Boolean = mediaPlayer.value === player

    private fun mediaPlayerError(what: Int, extra: Int): IOException =
        IOException("MediaPlayer failed: what=$what extra=$extra")
}
