package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
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
     * Whether the last [start] has not been followed by [pause] or the end of the source. JavaFX's own status
     * lags a pause() (it changes when the native side answers), so a start right after a pause would still
     * read PLAYING there.
     */
    private var playIssued: Boolean = false

    /** Whether a native seek has been posted since the last [start]'s play() (see [start]). */
    private var seekedSincePlay: Boolean = false

    /**
     * When the last [start] issued a plain play(), with no native seek before it and no rate given after it
     * ([System.nanoTime]), or 0 when there is no such play. A speed given within [FRESH_PLAY_NANOS] of it is given the
     * way [start] gives a resume at a speed (see [setSpeed]).
     */
    private var playIssuedAtNanos: Long = 0L

    /**
     * When the last native `seek()` was posted ([System.nanoTime]), or 0 when none has been. A speed given while playing
     * within [SEEK_RATE_SETTLE_NANOS] of it is not written at once (see [setSpeed]): on macOS a rate change that close
     * to a native seek can leave the native player PLAYING with its playhead standing at the seek target for good, and
     * a player in that state is never kicked again.
     */
    private var lastNativeSeekNanos: Long = 0L

    /**
     * The speed [start] has still to give after its play(): given two FX tasks later, so a speed the caller gives right
     * after [start] (the shared player gives it that way, from any thread, the FX thread included) replaces it and
     * reaches the native player once, not as a second change on top of the first. Null when nothing is pending.
     */
    private var pendingNudge: Double? = null

    /**
     * The speed a resume without a native seek has still to give (see [start]): given as one away-and-back once the
     * playhead has really moved on for [RESUME_RATE_SETTLE_MS], on a tick of the playhead (see [applyResumeRate]).
     * Null when nothing is pending. A speed that arrives meanwhile replaces it.
     */
    private var settleNudge: Double? = null

    /** [settleNudge] is not given before this ([System.nanoTime]): a seek restarts the wait (see [seekTo]). */
    private var settleNotBeforeNanos: Long = 0L

    /** Where the playhead stood on the first tick after [settleNudge] was set; NaN until then. */
    private var settleFrom: Double = Double.NaN

    /** Counts the plain resumes and pauses: a play() [start] delayed (see [idleRateWriteNanos]) runs only if it is still the newest. */
    private var playGeneration: Long = 0L

    /** When the rate property was last written while not playing ([System.nanoTime]), or 0: see [IDLE_RATE_GAP_NANOS]. */
    private var idleRateWriteNanos: Long = 0L

    /** A deferred rate is not given before this ([System.nanoTime]): a seek restarts the wait (see [seekTo]). */
    private var deferredNotBeforeNanos: Long = 0L

    /**
     * The speed to give the native player after a loop wrap, while the rate property holds 1.0 until then
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
            playIssued = false
            settleNudge?.let { player.rate = it }
            settleNudge = null
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
            applyResumeRate()
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
            lastNativeSeekNanos = System.nanoTime()
            seekedSincePlay = true
        }
        // Keeps the pulse alive until the status turns PLAYING (the listener restarts it then).
        refreshPicture()
        val rate: Double = deferredRate ?: player.rate
        val resuming: Boolean = !playIssued
        playIssued = true
        if (!resuming || rate == 1.0) {
            val hadSeek: Boolean = seekedSincePlay
            player.play()
            seekedSincePlay = false
            playIssuedAtNanos = if (resuming && !hadSeek) System.nanoTime() else 0L
            return@post
        }
        deferredRate = null
        if (!seekedSincePlay && !nearStart()) {
            // A plain resume at a speed other than 1 (macOS: play() starts the native player at normal speed whatever
            // the rate property says, and a rate change right after a plain play() can freeze the playhead). No native
            // seek here: a seek lands on the key frame at or before the playhead, so the clip would resume from there,
            // up to a whole key-frame interval earlier. Play at the speed the player stands at, and give the speed
            // as one away-and-back once the native player has been playing for a moment (see [applyResumeRate]).
            settleNudge = rate
            settleNotBeforeNanos = 0L
            settleFrom = Double.NaN
            val generation: Long = ++playGeneration
            val wait: Long = IDLE_RATE_GAP_NANOS - (System.nanoTime() - idleRateWriteNanos)
            if (wait > 0L) {
                PauseTransition(Duration.millis(wait / 1_000_000.0 + 1.0)).apply {
                    setOnFinished {
                        if (disposed || !playIssued || generation != playGeneration) return@setOnFinished
                        player.play()
                        playIssuedAtNanos = System.nanoTime()
                    }
                    play()
                }
            } else {
                player.play()
                playIssuedAtNanos = System.nanoTime()
            }
            return@post
        }
        // A native seek is already posted (the caller's seek, or the restart after the end), or the playhead stands at
        // the start, where a seek to it moves nothing (the first play, a replay): a rate change right after a native
        // seek is safe and effective, so the speed is given right after play() as the old nudge, away and back.
        if (!seekedSincePlay) {
            player.seek(player.currentTime)
            lastNativeSeekNanos = System.nanoTime()
        }
        player.play()
        seekedSincePlay = false
        playIssuedAtNanos = 0L
        pendingNudge = rate
        // Two tasks later, not one: called on the FX thread, the shared player's setSpeed right after start() queues
        // behind a nudge posted one task later (post() runs inline only when nothing is queued), found the nudge already
        // given and wrote the speed as a third rate change right after play(), which can freeze the playhead on macOS.
        // One more hop puts that setSpeed in front of the nudge, where it replaces it.
        FxThread.postLater { FxThread.postLater { giveNudge() } }
    }

    /**
     * Whether a native seek was posted within [SEEK_RATE_SETTLE_NANOS] ([lastNativeSeekNanos]). Checked before the
     * fresh-play branch of [setSpeed], so a seek after a plain play() does not let a speed skip the seek settle.
     */
    private fun inSeekSettle(): Boolean =
        lastNativeSeekNanos != 0L && System.nanoTime() - lastNativeSeekNanos < SEEK_RATE_SETTLE_NANOS

    /** Gives the native player the speed [start] left pending, as the old nudge: away and back. */
    private fun giveNudge() {
        val rate: Double = pendingNudge ?: return
        pendingNudge = null
        if (disposed) return
        if (!playIssued) {
            // Paused again meanwhile: the property only keeps the speed for the next start.
            player.rate = rate
            return
        }
        player.rate = 1.0
        player.rate = rate
    }

    fun pause(): Unit = FxThread.post {
        if (disposed) return@post
        playIssued = false
        playGeneration++
        settleNudge?.let {
            if (player.rate != it) idleRateWriteNanos = System.nanoTime()
            player.rate = it
        }
        settleNudge = null
        player.pause()
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
                lastNativeSeekNanos = System.nanoTime()
                seekedSincePlay = true
                if (settleNudge != null) {
                    // Same as below: the playhead jumped, wait again from the new place.
                    settleFrom = Double.NaN
                    settleNotBeforeNanos = System.nanoTime() + SEEK_RATE_SETTLE_NANOS
                }
                if (deferredRate != null) {
                    // The playhead jumped without the native player having settled, and a rate change within about
                    // 100 ms of a native seek can make the playhead stand still: wait again, from the new place.
                    deferredFrom = Double.NaN
                    deferredAfterWrap = false
                    deferredNotBeforeNanos = System.nanoTime() + SEEK_RATE_SETTLE_NANOS
                }
                refreshPicture()
            }
            sink.onSeekApplied()
        }
    }

    fun setSpeed(speed: Float): Unit = FxThread.post {
        if (disposed) return@post
        val rate: Double = speed.toDouble()
        when {
            pendingNudge != null -> pendingNudge = rate
            settleNudge != null -> settleNudge = rate
            deferredRate != null -> deferredRate = rate
            rate != player.rate && rate != 1.0 && playIssued && playIssuedAtNanos != 0L && !inSeekSettle() &&
                System.nanoTime() - playIssuedAtNanos < FRESH_PLAY_NANOS -> {
                // A play() has just been issued (the shared player gives the speed right after start(), so a
                // speed changed while paused arrives here): a rate change right after a plain play() can freeze
                // the playhead on macOS. Give it the way start() gives a resume at a speed, once the native
                // player has been playing for a moment.
                playIssuedAtNanos = 0L
                if (nearStart()) {
                    if (!seekedSincePlay) {
                        player.seek(player.currentTime)
                        lastNativeSeekNanos = System.nanoTime()
                    }
                    player.play()
                    seekedSincePlay = false
                    player.rate = 1.0
                    player.rate = rate
                } else {
                    settleNudge = rate
                    settleNotBeforeNanos = 0L
                    settleFrom = Double.NaN
                }
            }
            rate != player.rate && playIssued && inSeekSettle() -> {
                // A native seek was posted a moment ago on a player that is playing (replay() or seekTo() while
                // Playing, or a stop() followed by a play()): a rate written now can leave the native player PLAYING
                // with the playhead standing at the seek target for good (macOS, AVFoundation). Keep the property as
                // it is and hand the speed to the deferred-rate machinery: it is given once the player is PLAYING,
                // the settle time since the seek has passed and the playhead has moved on (see [applyDeferredRate]).
                // For those few hundred milliseconds the native player keeps its old speed.
                deferredRate = rate
                deferredFrom = Double.NaN
                deferredAfterWrap = false
                deferredNotBeforeNanos = lastNativeSeekNanos + SEEK_RATE_SETTLE_NANOS
            }
            else -> {
                if (!playIssued && player.rate != rate) idleRateWriteNanos = System.nanoTime()
                player.rate = rate
            }
        }
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
     * Gives the speed in force to the native player again at the start of a looping source's next cycle, which
     * begins at normal speed on macOS whatever the rate property says. The property only forwards a change, so
     * it is put to 1.0 here and the speed is given back as one change by [applyDeferredRate], once the playhead
     * has wrapped and moved a moment (a change made within the first ~100 ms of playback can make the playhead
     * stand still). A resume from a pause is not handled here: see [start].
     */
    private fun deferRate(afterWrap: Boolean) {
        val rate: Double = deferredRate ?: settleNudge ?: player.rate
        settleNudge = null
        if (rate == 1.0) return
        deferredRate = rate
        deferredFrom = Double.NaN
        deferredAfterWrap = afterWrap
        deferredNotBeforeNanos = 0L
        player.rate = 1.0
    }

    /** Called on every tick of the playhead: gives the native player the deferred speed once it is safe. */
    private fun applyDeferredRate() {
        val rate: Double = deferredRate ?: return
        if (player.status != MediaPlayer.Status.PLAYING) return
        if (System.nanoTime() < deferredNotBeforeNanos) return
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
        if (abs(position - deferredFrom) < LOOP_RATE_SETTLE_MS) return
        deferredRate = null
        player.rate = rate
    }

    /**
     * Called on every tick of the playhead: gives the native player the speed of a resume without a native seek, as one
     * away-and-back, once the playhead has moved [RESUME_RATE_SETTLE_MS] at normal speed (a rate change within about
     * 100 ms of a plain play() can freeze the playhead on macOS; a fixed delay is a race with the native start-up).
     */
    private fun applyResumeRate() {
        val rate: Double = settleNudge ?: return
        if (player.status != MediaPlayer.Status.PLAYING || !playIssued) return
        if (System.nanoTime() < settleNotBeforeNanos) return
        val position: Double = player.currentTime.toMillis()
        if (position.isNaN()) return
        if (settleFrom.isNaN()) {
            settleFrom = position
            return
        }
        if (abs(position - settleFrom) < RESUME_RATE_SETTLE_MS) return
        settleNudge = null
        player.rate = 1.0
        player.rate = rate
    }

    /**
     * Whether the playhead stands within [NEAR_START_MS] of the start, where a native seek to it moves nothing visible
     * (the first key frame is at 0): there a resume may seek to where it stands, and does, for the freeze it prevents.
     */
    private fun nearStart(): Boolean {
        val position: Double = player.currentTime.toMillis()
        return position.isNaN() || position <= NEAR_START_MS
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

        /** How far the playhead must move, at normal speed, before a rate deferred at a loop wrap is given (see [deferRate]). */
        private const val LOOP_RATE_SETTLE_MS: Double = 200.0

        /**
         * How long a plain play() keeps away from a rate written while the player was not playing (see [start]): a play()
         * issued within about this time of such a write can freeze the playhead on macOS (found at a speed of 8).
         */
        private const val IDLE_RATE_GAP_NANOS: Long = 200_000_000L

        /** How long after a play() a speed is still given the way a resume gives it (see [setSpeed]). */
        private const val FRESH_PLAY_NANOS: Long = 600_000_000L

        /** Within this of the start a native seek to the playhead moves it by no more than the documented quantisation (see [nearStart]). */
        private const val NEAR_START_MS: Double = 100.0

        /** How far the playhead must move, at normal speed, before the speed of a resume without a seek is given (see [applyResumeRate]). */
        private const val RESUME_RATE_SETTLE_MS: Double = 200.0

        /** How long after a native seek a deferred rate keeps waiting, and a speed given while playing is held back (see [seekTo], [setSpeed]). */
        private const val SEEK_RATE_SETTLE_NANOS: Long = 300_000_000L

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
