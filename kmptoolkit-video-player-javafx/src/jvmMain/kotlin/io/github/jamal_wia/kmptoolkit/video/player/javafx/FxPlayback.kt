package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import javafx.animation.AnimationTimer
import javafx.scene.Group
import javafx.scene.Scene
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.util.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One loaded source: a `MediaPlayer`, the off-screen `MediaView` it paints into, and the pulse that
 * copies that view into memory. Every JavaFX type the engine uses lives in this file, so
 * [JavaFxVideoEngine] loads on a classpath without OpenJFX.
 *
 * All state is confined to the FX application thread; the public methods may be called from any
 * thread and hop there themselves. Nothing here decides whether an event still matters — [Sink]
 * does that, so a disposed playback's late events are dropped in one place.
 */
@OptIn(ToolkitInternalApi::class)
internal class FxPlayback private constructor(
    private val media: Media,
    private val sink: Sink,
    maxFrameRate: Int,
) {

    /** Where events go. Called on the FX thread. */
    interface Sink {
        fun onTimes(durationMs: Long, positionMs: Long, bufferedMs: Long)
        fun onVideoSize(size: VideoSize?)
        fun onBuffering(isBuffering: Boolean)
        fun onCompleted()
        fun onFailed(cause: Throwable)
        fun onFrame(frame: VideoFrame)
    }

    private val player: MediaPlayer = MediaPlayer(media)
    private val view: MediaView = MediaView(player)
    private val minFrameIntervalNanos: Long = 1_000_000_000L / maxFrameRate
    // Transparent, so a snapshot taken before the view holds any decoded picture comes back with
    // alpha 0 and is recognisably "nothing yet" — a decoded video picture is always opaque.
    private val snapshotParameters: SnapshotParameters =
        SnapshotParameters().apply { fill = Color.TRANSPARENT }

    // Keeps the view in a scene graph, so a snapshot renders it the way a window would.
    @Suppress("unused")
    private val scene: Scene = Scene(Group(view))

    /** Completes once, with READY or the first error; later errors go to [Sink.onFailed]. */
    private val ready: CompletableDeferred<Unit> = CompletableDeferred()
    private var image: WritableImage? = null

    /**
     * Where the next snapshot is copied. Handed over with the frame it becomes and never touched
     * again — the consumer holds the latest frame through a conflated flow and may draw it at any
     * time, so a recycled array could be overwritten mid-draw. Kept only while a snapshot is not
     * published (no picture yet).
     */
    private var scratch: IntArray? = null
    private var lastSnapshotNanos: Long = 0L
    private var awaitingPicture: Boolean = true
    private var refreshUntilNanos: Long = 0L
    private var looping: Boolean = false
    private var atEnd: Boolean = false
    private var buffering: Boolean = false
    private var reportedSize: VideoSize? = null
    private var failed: Boolean = false
    private var disposed: Boolean = false

    private val pulse: AnimationTimer = object : AnimationTimer() {
        override fun handle(now: Long) = onPulse(now)
    }

    init {
        player.setOnReady {
            reportTimes()
            reportSize()
            // The first picture is decoded around READY, not necessarily before it: the pulse keeps
            // trying until a snapshot is opaque (see awaitingPicture).
            pulse.start()
            ready.complete(Unit)
        }
        player.setOnError { fail(player.error ?: media.error) }
        media.setOnError { fail(media.error ?: player.error) }
        player.setOnEndOfMedia {
            // Fired at the end of every cycle, including while cycleCount is INDEFINITE and JavaFX
            // is already starting the next one — that is not an end.
            if (looping) return@setOnEndOfMedia
            atEnd = true
            // JavaFX leaves its status at PLAYING after the last cycle ends. The player reports
            // Completed, and a seek from there reports Paused — so JavaFX must be paused too, or a
            // seek would resume playback on a backend that honours the status, and the pulse
            // would keep copying frames of a picture that does not move.
            player.pause()
            reportTimes()
            sink.onCompleted()
        }
        player.statusProperty().addListener { _, _, status ->
            setBuffering(status == MediaPlayer.Status.STALLED)
        }
        player.currentTimeProperty().addListener { _, _, _ ->
            reportTimes()
            if (player.status != MediaPlayer.Status.PLAYING) refreshPicture()
        }
        player.bufferProgressTimeProperty().addListener { _, _, _ -> reportTimes() }
        media.durationProperty().addListener { _, _, _ -> reportTimes() }
        media.widthProperty().addListener { _, _, _ -> reportSize() }
        media.heightProperty().addListener { _, _, _ -> reportSize() }
        // Anything already failed before the handlers were installed.
        (player.error ?: media.error)?.let { fail(it) }
    }

    fun start(): Unit = FxThread.post {
        if (disposed) return@post
        if (atEnd) {
            atEnd = false
            player.seek(player.startTime)
        }
        player.play()
        reapplyRate()
    }

    fun pause(): Unit = FxThread.post {
        if (!disposed) player.pause()
    }

    fun seekTo(positionMs: Long): Unit = FxThread.post {
        if (disposed) return@post
        atEnd = false
        player.seek(Duration.millis(positionMs.toDouble()))
        refreshPicture()
    }

    fun setSpeed(speed: Float): Unit = FxThread.post {
        if (!disposed) player.rate = speed.toDouble()
    }

    fun setVolume(volume: Float): Unit = FxThread.post {
        if (!disposed) player.volume = volume.toDouble()
    }

    fun setLooping(looping: Boolean): Unit = FxThread.post {
        if (disposed) return@post
        this.looping = looping
        player.cycleCount = if (looping) MediaPlayer.INDEFINITE else 1
    }

    /** What JavaFX currently holds, read on the FX thread — for tests; settings have no getter. */
    class Inspection(val volume: Double, val rate: Double, val cycleCount: Int, val status: String)

    suspend fun inspect(): Inspection = FxThread.call {
        Inspection(player.volume, player.rate, player.cycleCount, player.status.toString())
    }

    /** Stops the pulse and frees the native player. Idempotent; any thread. */
    fun dispose(): Unit = FxThread.post {
        if (disposed) return@post
        disposed = true
        pulse.stop()
        ready.cancel()
        view.mediaPlayer = null
        player.dispose()
        image = null
        scratch = null
    }

    /**
     * Pushes the rate JavaFX already holds down to the native player again. On macOS, play() starts
     * the native player at normal speed whatever the rate property says — a rate set while paused
     * (every rate set before the first play) is silently lost. The property only forwards a change,
     * so it is nudged away and back.
     */
    private fun reapplyRate() {
        val rate: Double = player.rate
        if (rate == 1.0) return
        player.rate = 1.0
        player.rate = rate
    }

    /** The first error only: JavaFX reports one failure through both the player and the media. */
    private fun fail(cause: Throwable?) {
        if (disposed || failed) return
        failed = true
        val error: Throwable = cause
            ?: IllegalStateException("JavaFX Media reported an error without a cause")
        if (!ready.completeExceptionally(error)) sink.onFailed(error)
    }

    private fun setBuffering(isBuffering: Boolean) {
        if (buffering == isBuffering) return
        buffering = isBuffering
        sink.onBuffering(isBuffering)
    }

    private fun reportTimes() {
        if (disposed) return
        val duration: Long = media.duration.toMillisOrZero()
        sink.onTimes(
            durationMs = duration,
            positionMs = player.currentTime.toMillisOrZero(),
            bufferedMs = player.bufferProgressTime.toMillisOrZero(),
        )
    }

    private fun reportSize() {
        if (disposed) return
        val size: VideoSize? = if (media.width > 0 && media.height > 0) {
            VideoSize(media.width, media.height)
        } else {
            null
        }
        if (size == reportedSize) return
        reportedSize = size
        sink.onVideoSize(size)
    }

    /** Keeps copying for a while although not playing: the picture changes after a seek. */
    private fun refreshPicture() {
        refreshUntilNanos = System.nanoTime() + REFRESH_WINDOW_NANOS
    }

    private fun onPulse(now: Long) {
        if (disposed) return
        val playing: Boolean = player.status == MediaPlayer.Status.PLAYING && !atEnd
        if (!playing && !awaitingPicture && now > refreshUntilNanos) return
        // Pulses arrive at the display rate with jitter; without the slack a 30 fps cap on a 60 Hz
        // pulse regularly waits a third pulse and delivers ~24 fps.
        if (now - lastSnapshotNanos < minFrameIntervalNanos - PULSE_SLACK_NANOS) return
        lastSnapshotNanos = now
        snapshot()
    }

    private fun snapshot() {
        val width: Int = media.width
        val height: Int = media.height
        if (width <= 0 || height <= 0) return
        val target: WritableImage = image
            ?.takeIf { it.width.toInt() == width && it.height.toInt() == height }
            ?: WritableImage(width, height).also { image = it }
        view.snapshot(snapshotParameters, target)
        val pixels: IntArray = scratch?.takeIf { it.size == width * height } ?: IntArray(width * height)
        target.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width)
        if (pixels[(height / 2) * width + width / 2] ushr 24 == 0) {
            // The view has no decoded picture yet: nothing to show, and nothing was published, so
            // the array can be written again next time.
            scratch = pixels
            return
        }
        awaitingPicture = false
        scratch = null
        sink.onFrame(VideoFrame(width, height, pixels))
    }

    companion object {

        /** How long the pulse keeps copying after the picture changed while not playing (a seek). */
        private const val REFRESH_WINDOW_NANOS: Long = 500_000_000L

        /** A quarter of a 60 Hz pulse. */
        private const val PULSE_SLACK_NANOS: Long = 4_000_000L

        /**
         * Opens [uri] and suspends until JavaFX reports it ready. `Media(uri)` probes the source and
         * may block on the network, so it runs on the IO dispatcher; everything else runs on the FX
         * thread. On failure or cancellation the half-built playback is disposed before this throws.
         *
         * [onCreated] runs on the FX thread as soon as the player exists, before it is ready, so the
         * caller can route settings to it from then on.
         */
        suspend fun open(
            uri: String,
            sink: Sink,
            maxFrameRate: Int,
            readyTimeoutMs: Long,
            onCreated: (FxPlayback) -> Unit,
        ): FxPlayback {
            val media: Media = withContext(Dispatchers.IO) { runInterruptible { Media(uri) } }
            val playback: FxPlayback = FxThread.call {
                FxPlayback(media, sink, maxFrameRate).also(onCreated)
            }
            try {
                // JavaFX can stay UNKNOWN forever — on macOS a file it cannot parse reports neither
                // READY nor an error — so the wait is bounded.
                withTimeoutOrNull(readyTimeoutMs) { playback.ready.await() }
                    ?: throw JavaFxVideoPlayerException.LoadTimedOut(readyTimeoutMs)
                return playback
            } catch (e: Throwable) {
                // Cancellation included: nothing playable is left behind either way.
                playback.dispose()
                throw e
            }
        }

        private fun Duration?.toMillisOrZero(): Long {
            if (this == null || isUnknown || isIndefinite) return 0L
            val millis: Double = toMillis()
            return if (millis.isNaN() || millis < 0.0) 0L else millis.toLong()
        }
    }
}
