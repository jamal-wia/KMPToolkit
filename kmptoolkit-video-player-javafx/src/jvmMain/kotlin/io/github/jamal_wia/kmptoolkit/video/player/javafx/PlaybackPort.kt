package io.github.jamal_wia.kmptoolkit.video.player.javafx

// The port FxPlayback talks to instead of JavaFX: the FX application thread with its clock and timers
// (FxHost), and one loaded source as JavaFX holds it (NativePlayer). Nothing in this file may import
// javafx.*, so FxPlayback and any test double built on these types load without OpenJFX; the one
// implementation over JavaFX is JavaFxNativePlayer.kt.

/**
 * The FX application thread, its clock and its timers, as [FxPlayback] sees them.
 *
 * THREADS: [post], [postLater], [call] and [nanoTime] may be called from any thread; [after] and
 * [hasExitedNow] are called from the FX thread and from a coroutine's thread respectively, see each.
 * NO THREAD HOP: a member never moves its caller's work to another thread except where its own
 * contract says "on the FX thread".
 *
 * CONTRACT: every member is exactly one call of what the production implementation names in its
 * comment, and no decision lives here.
 */
internal interface FxHost {
    /** `FxThread.post`: [block] runs on the FX thread, inline when the caller is there and nothing is queued. */
    fun post(block: () -> Unit)

    /** `FxThread.postLater`: [block] runs on the FX thread, always queued behind what is already waiting. */
    fun postLater(block: () -> Unit)

    /** `FxThread.call`: runs [block] on the FX thread and suspends for its result or exception. */
    suspend fun <T> call(block: () -> T): T

    /** `System.nanoTime()`: the clock every deadline in [FxPlayback] is measured on. */
    fun nanoTime(): Long

    /**
     * `PauseTransition(Duration.millis(delayMillis))` finished: runs [block] on the FX thread once, no earlier
     * than [delayMillis] from now. Called on the FX thread. The delay is computed by the caller, not here.
     */
    fun after(delayMillis: Double, block: () -> Unit)

    /**
     * `FxThread.hasExited(wait = false)`: whether the toolkit has exited, never waiting. May be called from any
     * thread, the exit watch in `open` calls it from a coroutine's thread.
     */
    fun hasExitedNow(): Boolean
}

/**
 * The state of a [NativePlayer]. The names are identical to `MediaPlayer.Status`, so a status read through the
 * port prints exactly as `MediaPlayer.Status.toString()` did.
 */
internal enum class NativeStatus { UNKNOWN, READY, PAUSED, PLAYING, STOPPED, STALLED, HALTED, DISPOSED }

/**
 * One loaded source as JavaFX holds it: `Media` + `MediaPlayer` + `MediaView` + the pulse. FX thread only.
 *
 * CONTRACT: every member is exactly one JavaFX call (or one property read), in the order [FxPlayback] makes it.
 * No decision lives here; every `if` about playback stays in [FxPlayback]. NO THREAD HOP: a member runs on the
 * caller's thread, which is the FX thread, and never posts, waits or switches threads itself.
 *
 * Durations are milliseconds as `Duration.toMillis()` gives them: UNKNOWN = NaN, INDEFINITE = +Infinity.
 */
internal interface NativePlayer {
    fun play()
    fun pause()

    /** `MediaPlayer.seek(Duration.millis(millis))`. */
    fun seek(millis: Double)

    /** Property semantics kept: a write of the value the property already holds is no change. */
    var rate: Double
    var volume: Double

    /** `MediaPlayer.cycleCount`; INDEFINITE is -1, the value of `MediaPlayer.INDEFINITE`. */
    var cycleCount: Int

    val status: NativeStatus
    val currentTimeMs: Double
    val startTimeMs: Double
    val bufferProgressMs: Double

    /** `media.duration`. */
    val durationMs: Double

    /** `media.width`. */
    val width: Int

    /** `media.height`. */
    val height: Int

    /** `player.error`. */
    val playerError: Throwable?

    /** `media.error`. */
    val mediaError: Throwable?

    /**
     * Installs every handler and listener, in the order [FxPlayback] always installed them. Called once, right after
     * the player was created. [events] is called on the FX thread, synchronously from the JavaFX handler or listener.
     */
    fun setEvents(events: NativeEvents)

    /** `player.onEndOfMedia = null`. */
    fun clearEndOfMedia()

    /** `view.mediaPlayer = null`. */
    fun detachView()

    /** `player.dispose()`, and drops the adapter's own cached picture. */
    fun dispose()

    /** `AnimationTimer.start()`. */
    fun startPulse()

    /** `AnimationTimer.stop()`. */
    fun stopPulse()

    /**
     * `view.snapshot` into the adapter's cached image (recreated when its size is not [width] by [height]), then
     * `getPixels` of it into [pixels] (IntArgb, stride [width]).
     */
    fun snapshotInto(pixels: IntArray, width: Int, height: Int)
}

/**
 * What a [NativePlayer] reports. Every call is on the FX thread, synchronously from the JavaFX handler or listener,
 * as the handlers and listeners of `MediaPlayer` and `Media` ran before the port existed. A callback carries no
 * value that the handler can read back from the [NativePlayer] itself, except the new [NativeStatus] and the pulse
 * time.
 */
internal interface NativeEvents {
    /** `player.onReady`. */
    fun onReady()

    /** `player.onError`: the cause is `playerError ?: mediaError`. */
    fun onPlayerError()

    /** `media.onError`: the cause is `mediaError ?: playerError`. */
    fun onMediaError()

    /** `player.onEndOfMedia`. */
    fun onEndOfMedia()

    /** `player.statusProperty()` changed to [status]. */
    fun onStatus(status: NativeStatus)

    /** `player.currentTimeProperty()` changed. */
    fun onCurrentTime()

    /** `player.bufferProgressTimeProperty()` changed. */
    fun onBufferProgress()

    /** `media.durationProperty()` changed. */
    fun onDuration()

    /** `media.widthProperty()` or `media.heightProperty()` changed. */
    fun onSize()

    /** One `AnimationTimer.handle(now)`, [nowNanos] being its argument. */
    fun onPulse(nowNanos: Long)
}

/** An opened but not yet played source: what [NativeBackend.probe] returns, opaque to [FxPlayback]. */
internal interface NativeSource

/** Builds players. [probe] blocks (network) and is called on IO; [create] is called on the FX thread. */
internal interface NativeBackend {
    val host: FxHost

    /** `Media(uri)`: probes the source, may block on the network. */
    fun probe(uri: String): NativeSource

    /** Builds the player of a source [probe] returned: `MediaPlayer(media)` with its `MediaView` and pulse. */
    fun create(source: NativeSource): NativePlayer
}
