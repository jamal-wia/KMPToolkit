package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
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

        /**
         * A [seekTo] has reached the FX thread — called whether or not it could still seek, so
         * every [seekTo] is answered once (unless the toolkit has exited and the call was dropped).
         */
        fun onSeekApplied()

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

    /**
     * The speed to give the native player once it plays, while the rate property holds 1.0 until then
     * (see [deferRate]). Null when the property holds the speed.
     */
    private var deferredRate: Double? = null

    /** Where the playhead stood on the first tick after [deferredRate] was set; NaN until then. */
    private var deferredFrom: Double = Double.NaN

    /** Whether [deferredRate] waits for the playhead to wrap to the start of a looping source first. */
    private var deferredAfterWrap: Boolean = false

    // The ticket of the newest seekTo: taken on the caller's thread before posting, read on the FX thread
    // (D-24: a value shared between threads, in a module with a coroutines dependency).
    private val latestSeek: MutableStateFlow<Long> = MutableStateFlow(0L)
    private var buffering: Boolean = false
    private var reportedSize: VideoSize? = null
    private var failed: Boolean = false
    private var disposed: Boolean = false

    /** Whether [pulse] runs. FX-thread confined, like everything here. */
    private var pulsing: Boolean = false

    private val pulse: AnimationTimer = object : AnimationTimer() {
        override fun handle(now: Long) = onPulse(now)
    }

    init {
        player.setOnReady {
            reportTimes()
            reportSize()
            // The first picture is decoded around READY, not necessarily before it: the pulse keeps
            // trying until a snapshot is opaque (see awaitingPicture).
            startPulse()
            ready.complete(Unit)
        }
        player.setOnError { fail(player.error ?: media.error) }
        media.setOnError { fail(media.error ?: player.error) }
        player.setOnEndOfMedia {
            // Fired at the end of every cycle, including while cycleCount is INDEFINITE and JavaFX
            // is already starting the next one — that is not an end. A handler JavaFX queued before
            // dispose() can still run after it, and must then do nothing.
            if (disposed) return@setOnEndOfMedia
            if (looping) {
                // The native player starts the next cycle at normal speed (macOS), so a speed other than 1 is lost
                // here unless it is given again.
                deferRate(afterWrap = true)
                return@setOnEndOfMedia
            }
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
            // JavaFX reports PLAYING some time after play(): a pulse in between may already have
            // stopped, and a stall longer than the refresh window stops it too.
            if (status == MediaPlayer.Status.PLAYING) {
                startPulse()
            }
        }
        player.currentTimeProperty().addListener { _, _, _ ->
            applyDeferredRate()
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
        // Keeps the pulse alive until the status turns PLAYING (the listener restarts it then).
        refreshPicture()
        if (player.status != MediaPlayer.Status.PLAYING) deferRate(afterWrap = false)
        player.play()
    }

    fun pause(): Unit = FxThread.post {
        if (!disposed) player.pause()
    }

    fun seekTo(positionMs: Long) {
        val ticket: Long = latestSeek.updateAndGet { it + 1 }
        FxThread.post {
            // A newer seek is queued behind this one and overrides its position: skip the native work.
            // The newer ticket is taken before its block is posted, and FxThread.post runs a block inline
            // only when nothing is queued, so a block that sees a newer ticket always has that newer
            // block still behind it. onSeekApplied still answers every seek, a skipped one included.
            if (!disposed && ticket == latestSeek.value) {
                atEnd = false
                player.seek(Duration.millis(positionMs.toDouble()))
                refreshPicture()
            }
            sink.onSeekApplied()
        }
    }

    fun setSpeed(speed: Float): Unit = FxThread.post {
        if (disposed) return@post
        if (deferredRate != null) deferredRate = speed.toDouble() else player.rate = speed.toDouble()
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
    class Inspection(
        val volume: Double,
        val rate: Double,
        val cycleCount: Int,
        val status: String,
        val pulsing: Boolean,
    )

    suspend fun inspect(): Inspection = FxThread.call {
        Inspection(player.volume, player.rate, player.cycleCount, player.status.toString(), pulsing)
    }

    /** Stops the pulse and frees the native player. Idempotent; any thread. */
    fun dispose(): Unit = FxThread.post {
        if (disposed) return@post
        disposed = true
        stopPulse()
        ready.cancel()
        view.mediaPlayer = null
        // OpenJFX race: MediaPlayer.dispose() sets DISPOSED, but a status update its native side had
        // already queued (onPlaying / onPause) can run afterwards and overwrite it, after which the
        // guards in seek() and pause() pass on a player whose native half is gone. A queued
        // end-of-media then loops (seek -> NullPointerException on the FX thread) or reaches our
        // handler. With one cycle and no handler an already queued end takes the plain non-looping
        // branch, which never seeks. Plain property writes on a player that is not yet disposed. Do
        // not "simplify" this away.
        player.onEndOfMedia = null
        player.cycleCount = 1
        player.dispose()
        image = null
        scratch = null
    }

    /**
     * Makes the native player play at the speed in force, which it does not by itself: on macOS
     * play() and the start of a loop's next cycle begin at normal speed whatever the rate property
     * says (a rate set while paused, every rate set before the first play, is silently lost). The
     * property only forwards a change, so the property is put to 1.0 here and the speed is given back
     * as one change by [applyDeferredRate], once the native player has played for a moment — a rate
     * change made together with play(), or within the first ~100 ms after it, makes the playhead stand
     * still for good (measured: 7 of 30 resumes at 2x and 4x, none when the change waits 100 ms of
     * playback). [afterWrap] is for the end of a cycle of a looping source: the change then also waits
     * for the playhead to wrap.
     */
    private fun deferRate(afterWrap: Boolean) {
        val rate: Double = deferredRate ?: player.rate
        if (rate == 1.0) return
        deferredRate = rate
        deferredFrom = Double.NaN
        deferredAfterWrap = afterWrap
        player.rate = 1.0
    }

    /** Called on every tick of the playhead: gives the native player the deferred speed once it is safe. */
    private fun applyDeferredRate() {
        val rate: Double = deferredRate ?: return
        if (player.status != MediaPlayer.Status.PLAYING) return
        val position: Double = player.currentTime.toMillis()
        if (position.isNaN()) return
        if (deferredAfterWrap) {
            if (position > media.duration.toMillis() / 2.0) return
            deferredAfterWrap = false
        }
        if (deferredFrom.isNaN()) {
            deferredFrom = position
            return
        }
        // Either direction: a seek during the wait moves the playhead without the native player having settled.
        if (abs(position - deferredFrom) < RATE_SETTLE_MS) return
        deferredRate = null
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
        // A picture that appears later (the size was 0×0 until now) needs the pulse again.
        if (size != null) startPulse()
    }

    /** Keeps copying for a while although not playing: the picture changes after a seek. */
    private fun refreshPicture() {
        refreshUntilNanos = System.nanoTime() + REFRESH_WINDOW_NANOS
        startPulse()
    }

    private fun startPulse() {
        if (disposed || pulsing) return
        pulsing = true
        pulse.start()
    }

    private fun stopPulse() {
        if (!pulsing) return
        pulsing = false
        pulse.stop()
    }

    private fun onPulse(now: Long) {
        if (disposed) return
        if (media.width <= 0 || media.height <= 0) {
            // Audio only: there is no picture to copy, now or until reportSize sees one.
            stopPulse()
            return
        }
        val playing: Boolean = player.status == MediaPlayer.Status.PLAYING && !atEnd
        if (!playing && !awaitingPicture && now > refreshUntilNanos) {
            // Nothing left to copy; start(), seekTo() and a changed picture start it again.
            stopPulse()
            return
        }
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

        /** How far the playhead must move, at normal speed, before a deferred rate is given (see [deferRate]). */
        private const val RATE_SETTLE_MS: Double = 200.0

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
            // An atomic: the FX block may still run after the wait timed out or was cancelled,
            // concurrently with the read below (D-21).
            val created: AtomicReference<FxPlayback?> = AtomicReference(null)
            try {
                // JavaFX can stay UNKNOWN forever — on macOS a file it cannot parse reports neither
                // READY nor an error — so the wait is bounded. So is building the player: once the
                // toolkit has exited, runLater drops the block silently and the call would never
                // resume. A block that does run late finds its session closed and disposes itself
                // (see JavaFxVideoEngine.Session.attach). An exit seen before this point fails the load
                // at once (ToolkitJavaFxRuntime re-checks it on every call); only an exit that races
                // the load itself, between that probe and this call, waits out the timeout.
                withTimeoutOrNull(readyTimeoutMs) {
                    coroutineScope {
                        // The toolkit can end at any moment, by implicit exit as well as
                        // Platform.exit(); the wait then ends at once instead of at the timeout.
                        // Silence is not taken for an exit while the FX thread runs this load's own
                        // construction (see FxThread.hasExited).
                        val exitWatch: Job = launch {
                            while (true) {
                                delay(EXIT_POLL_MS)
                                if (FxThread.hasExited(wait = false)) {
                                    throw JavaFxVideoPlayerException.RuntimeUnavailable(
                                        IllegalStateException("The JavaFX toolkit has exited"),
                                    )
                                }
                            }
                        }
                        val playback: FxPlayback = FxThread.call {
                            FxPlayback(media, sink, maxFrameRate).also {
                                created.set(it)
                                onCreated(it)
                            }
                        }
                        playback.ready.await()
                        exitWatch.cancel()
                        playback
                    }
                } ?: throw if (created.get() == null) {
                    JavaFxVideoPlayerException.RuntimeUnavailable(
                        IllegalStateException(
                            "The JavaFX application thread ran no work within $readyTimeoutMs ms; " +
                                "the toolkit has probably exited",
                        ),
                    )
                } else {
                    JavaFxVideoPlayerException.LoadTimedOut(readyTimeoutMs)
                }
                return checkNotNull(created.get())
            } catch (e: Throwable) {
                // Cancellation included: nothing playable is left behind either way.
                created.get()?.dispose()
                throw e
            }
        }

        private const val EXIT_POLL_MS: Long = 100L

        private fun Duration?.toMillisOrZero(): Long {
            if (this == null || isUnknown || isIndefinite) return 0L
            val millis: Double = toMillis()
            return if (millis.isNaN() || millis < 0.0) 0L else millis.toLong()
        }
    }
}
