package io.github.jamal_wia.kmptoolkit.video.player.javafx

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

/*
 * Every JavaFX type the engine uses lives in this file, so JavaFxVideoEngine loads on a classpath without
 * OpenJFX: nothing here is loaded until JavaFxBackend is first used, after the runtime has started the toolkit.
 * The port these classes implement is in PlaybackPort.kt; its contract (one JavaFX call per member, no decision,
 * no thread hop) is the rule for everything below.
 */

/** The production [NativeBackend]: `Media`, `MediaPlayer`, `MediaView` and the FX thread of [FxThread]. */
internal object JavaFxBackend : NativeBackend {
    override val host: FxHost = JavaFxHost

    override fun probe(uri: String): NativeSource = JavaFxNativeSource(Media(uri))

    override fun create(source: NativeSource): NativePlayer {
        check(source is JavaFxNativeSource) { "A JavaFX backend builds players only of its own sources" }
        return JavaFxNativePlayer(source.media)
    }
}

/** A probed `Media`. */
internal class JavaFxNativeSource(val media: Media) : NativeSource

/** The production [FxHost]: [FxThread], [System.nanoTime] and `PauseTransition`. */
internal object JavaFxHost : FxHost {
    override fun post(block: () -> Unit) = FxThread.post(block)

    override fun postLater(block: () -> Unit) = FxThread.postLater(block)

    override suspend fun <T> call(block: () -> T): T = FxThread.call(block)

    override fun nanoTime(): Long = System.nanoTime()

    override fun after(delayMillis: Double, block: () -> Unit) {
        PauseTransition(Duration.millis(delayMillis)).apply {
            setOnFinished { block() }
            play()
        }
    }

    override fun hasExitedNow(): Boolean = FxThread.hasExited(wait = false)
}

/**
 * One loaded source: a `MediaPlayer`, the off-screen `MediaView` it paints into, and the pulse that copies that
 * view into memory. Built and used on the FX application thread only; each member is one JavaFX call.
 */
internal class JavaFxNativePlayer(private val media: Media) : NativePlayer {

    /** The wrapped player; internal so a test can read what JavaFX itself holds. */
    internal val mediaPlayer: MediaPlayer = MediaPlayer(media)

    private val view: MediaView = MediaView(mediaPlayer)

    // Transparent, so a snapshot taken before the view holds any decoded picture comes back with
    // alpha 0 and is recognisably "nothing yet" - a decoded video picture is always opaque.
    private val snapshotParameters: SnapshotParameters =
        SnapshotParameters().apply { fill = Color.TRANSPARENT }

    // Keeps the view in a scene graph, so a snapshot renders it the way a window would.
    @Suppress("unused")
    private val scene: Scene = Scene(Group(view))

    // A cache of the snapshot target, not a decision: reused while the picture keeps its size.
    private var image: WritableImage? = null

    private var events: NativeEvents? = null

    private val pulse: AnimationTimer = object : AnimationTimer() {
        override fun handle(now: Long) {
            checkNotNull(events).onPulse(now)
        }
    }

    override fun play() = mediaPlayer.play()

    override fun pause() = mediaPlayer.pause()

    override fun seek(millis: Double) = mediaPlayer.seek(Duration.millis(millis))

    override var rate: Double
        get() = mediaPlayer.rate
        set(value) {
            mediaPlayer.rate = value
        }

    override var volume: Double
        get() = mediaPlayer.volume
        set(value) {
            mediaPlayer.volume = value
        }

    override var cycleCount: Int
        get() = mediaPlayer.cycleCount
        set(value) {
            mediaPlayer.cycleCount = value
        }

    override val status: NativeStatus get() = mediaPlayer.status.toNative()

    override val currentTimeMs: Double get() = mediaPlayer.currentTime.millisOrNaN()

    override val startTimeMs: Double get() = mediaPlayer.startTime.millisOrNaN()

    override val bufferProgressMs: Double get() = mediaPlayer.bufferProgressTime.millisOrNaN()

    override val durationMs: Double get() = media.duration.millisOrNaN()

    override val width: Int get() = media.width

    override val height: Int get() = media.height

    override val playerError: Throwable? get() = mediaPlayer.error

    override val mediaError: Throwable? get() = media.error

    override fun setEvents(events: NativeEvents) {
        this.events = events
        mediaPlayer.setOnReady { events.onReady() }
        mediaPlayer.setOnError { events.onPlayerError() }
        media.setOnError { events.onMediaError() }
        mediaPlayer.setOnEndOfMedia { events.onEndOfMedia() }
        mediaPlayer.statusProperty().addListener { _, _, status -> events.onStatus(status.toNative()) }
        mediaPlayer.currentTimeProperty().addListener { _, _, _ -> events.onCurrentTime() }
        mediaPlayer.bufferProgressTimeProperty().addListener { _, _, _ -> events.onBufferProgress() }
        media.durationProperty().addListener { _, _, _ -> events.onDuration() }
        media.widthProperty().addListener { _, _, _ -> events.onSize() }
        media.heightProperty().addListener { _, _, _ -> events.onSize() }
    }

    override fun clearEndOfMedia() {
        mediaPlayer.onEndOfMedia = null
    }

    override fun detachView() {
        view.mediaPlayer = null
    }

    override fun dispose() {
        mediaPlayer.dispose()
        image = null
    }

    override fun startPulse() = pulse.start()

    override fun stopPulse() = pulse.stop()

    override fun snapshotInto(pixels: IntArray, width: Int, height: Int) {
        val target: WritableImage = image
            ?.takeIf { it.width.toInt() == width && it.height.toInt() == height }
            ?: WritableImage(width, height).also { image = it }
        view.snapshot(snapshotParameters, target)
        target.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width)
    }
}

/**
 * A `MediaPlayer.Status` as the port's [NativeStatus] of the same name. A null (never seen from OpenJFX, which
 * starts a player at UNKNOWN) reads as UNKNOWN, which no decision in [FxPlayback] treats differently from null.
 */
internal fun MediaPlayer.Status?.toNative(): NativeStatus = when (this) {
    null -> NativeStatus.UNKNOWN
    MediaPlayer.Status.UNKNOWN -> NativeStatus.UNKNOWN
    MediaPlayer.Status.READY -> NativeStatus.READY
    MediaPlayer.Status.PAUSED -> NativeStatus.PAUSED
    MediaPlayer.Status.PLAYING -> NativeStatus.PLAYING
    MediaPlayer.Status.STOPPED -> NativeStatus.STOPPED
    MediaPlayer.Status.STALLED -> NativeStatus.STALLED
    MediaPlayer.Status.HALTED -> NativeStatus.HALTED
    MediaPlayer.Status.DISPOSED -> NativeStatus.DISPOSED
}

/** `Duration.toMillis()`: UNKNOWN is NaN and INDEFINITE is +Infinity; a null (no value yet) reads as UNKNOWN. */
private fun Duration?.millisOrNaN(): Double = this?.toMillis() ?: Double.NaN
