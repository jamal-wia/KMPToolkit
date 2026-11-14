package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import java.util.EnumMap

/** Which of the two error channels of a failed source is populated: `player.error`, `media.error` or both. */
internal enum class FailureVia { PLAYER, MEDIA, BOTH }

/**
 * What a scripted player does on its own: when it becomes ready or never does, when it fails, and how long
 * its status lags a command. One [ScriptedBackend] holds one; every player it creates gets a [copy], so a
 * test scripts the backend before the engine builds the player, or scripts the player's own copy afterwards.
 *
 * Times are virtual milliseconds. The ready latency, [neverReady] and the failures are read once, when the
 * player is created; the status latencies are read at every `play()` and `pause()`.
 */
internal class StatusScript {

    /** B9: how long after `create` the duration, the size and READY arrive. */
    var readyLatencyMs: Double = DEFAULT_READY_LATENCY_MS

    /** B2: how long the status lags a `play()` or `pause()` that has no scripted latency of its own. */
    var statusLatencyMs: Double = DEFAULT_STATUS_LATENCY_MS

    /** B9: the player stays UNKNOWN for good, as a file macOS cannot parse does: no READY and no error. */
    var neverReady: Boolean = false
        private set

    private val latencyOverrides: MutableMap<Pair<NativeCall, Int>, Double> = mutableMapOf()
    private val scriptedFailures: MutableList<Failure> = mutableListOf()

    /** One scripted failure: [delayMs] after the player was created, [cause] arrives on the [via] channel(s). */
    class Failure(val delayMs: Double, val cause: Throwable, val via: FailureVia)

    /** The failures [failAt] scripted, in the order scripted. */
    val failures: List<Failure> get() = scriptedFailures.toList()

    /** B9: the player never becomes ready and reports no error. */
    fun neverReady(): StatusScript = apply { neverReady = true }

    /** B9: [delayMs] after the player is created, [cause] arrives on [via]; the status becomes HALTED. */
    fun failAt(delayMs: Double, cause: Throwable, via: FailureVia = FailureVia.BOTH): StatusScript = apply {
        require(delayMs >= 0.0 && delayMs.isFinite()) { "delayMs must be finite and not negative, was $delayMs" }
        scriptedFailures += Failure(delayMs, cause, via)
    }

    /**
     * B2, per call: the [nth] (1-based) `play()` or `pause()` the player receives turns the status after [latencyMs]
     * instead of [statusLatencyMs]. Calls on a disposed player are not counted.
     */
    fun statusLatency(call: NativeCall, nth: Int, latencyMs: Double): StatusScript = apply {
        require(call == NativeCall.PLAY || call == NativeCall.PAUSE) { "only PLAY and PAUSE have a status latency, was $call" }
        require(nth >= 1) { "nth is 1-based, was $nth" }
        require(latencyMs >= 0.0 && latencyMs.isFinite()) { "latencyMs must be finite and not negative, was $latencyMs" }
        latencyOverrides[call to nth] = latencyMs
    }

    internal fun statusLatencyFor(call: NativeCall, nth: Int): Double = latencyOverrides[call to nth] ?: statusLatencyMs

    /** An independent script with the same settings. */
    fun copy(): StatusScript {
        val source: StatusScript = this
        return StatusScript().apply {
            readyLatencyMs = source.readyLatencyMs
            statusLatencyMs = source.statusLatencyMs
            neverReady = source.neverReady
            latencyOverrides.putAll(source.latencyOverrides)
            scriptedFailures.addAll(source.scriptedFailures)
        }
    }

    companion object {
        /** The default ready latency of design B9. */
        const val DEFAULT_READY_LATENCY_MS: Double = 50.0

        /** The default status latency of design B2. */
        const val DEFAULT_STATUS_LATENCY_MS: Double = 30.0
    }
}

/**
 * The state a `MediaPlayer` keeps besides the playhead: the properties (B1), the status that lags its
 * commands (B2), and the ready and failure sequence (B9). One instance per [ScriptedNativePlayer]; FX thread
 * (the model's driving thread) only.
 *
 * B1: [writeRate], [writeVolume] and [writeCycleCount] return whether the write changed the property. A write of
 * the value the property holds is no change: nothing native happens and the caller must not treat it as one.
 * Comparison is the primitive `!=` that JavaFX's own properties use, so a NaN is a change every time.
 *
 * B2: [issue] schedules the status change of a `play()` or `pause()` after its latency, through the host's
 * queue; until then [status] reads the old value. Transitions land in the order issued, so a play followed by a
 * quick pause shows PLAYING for a moment, as the lagging status does. A command given before the player is ready
 * takes effect right after READY; one given to a HALTED or disposed player does nothing. The status event fires
 * only for a changed status.
 *
 * B9: [status] starts UNKNOWN and [durationMs], [width], [height] unknown (NaN, 0, 0). Unless the script says
 * [StatusScript.neverReady], the ready latency after creation the model sets all three, fires the duration and
 * size events (width, then height), the status event READY and then `onReady`. A failure sets the error
 * properties, makes the status HALTED, then fires the media handler and then the player handler (OpenJFX's player
 * learns of a media error through the media, so the media is first); it also means no READY ever follows. A later
 * failure fires its handlers again, without a second status event. These orders are modelling choices; no real
 * media measured them.
 *
 * DISPOSE: [dispose] sets DISPOSED at once and fires its status event; pending transitions and a pending ready are
 * dropped. Late events after dispose are the dispose part's job (B12, J7b).
 *
 * TIMING: every delay goes through `host.after`, so a host with a jitter adds it to these latencies too.
 *
 * @param events the listener installed by `setEvents`, or null before; read at every delivery.
 * @param onStatusChange called with the old and the new status BEFORE the status event is delivered, so a part
 *   can bring its own state up to date before the listener reads it.
 * @param onReady called after the ready values are set and before the ready events are delivered.
 */
internal class StatusModel(
    private val host: VirtualFxHost,
    private val clip: ClipModel,
    private val script: StatusScript,
    private val events: () -> NativeEvents?,
    private val onStatusChange: (old: NativeStatus, new: NativeStatus) -> Unit,
    private val onReady: () -> Unit,
) {

    /** `player.status`. */
    var status: NativeStatus = NativeStatus.UNKNOWN
        private set

    /** `media.duration` in milliseconds: NaN until ready. */
    var durationMs: Double = Double.NaN
        private set

    /** `media.width`: 0 until ready. */
    var width: Int = 0
        private set

    /** `media.height`: 0 until ready. */
    var height: Int = 0
        private set

    /** `player.error`. */
    var playerError: Throwable? = null
        private set

    /** `media.error`. */
    var mediaError: Throwable? = null
        private set

    /** The `MediaPlayer.rate` property: 1.0 until written. */
    var rate: Double = 1.0
        private set

    /** The `MediaPlayer.volume` property: 1.0 until written. */
    var volume: Double = 1.0
        private set

    /** The `MediaPlayer.cycleCount` property: 1 until written; INDEFINITE is -1. */
    var cycleCount: Int = 1
        private set

    /** Whether the ready values were given. */
    var isReady: Boolean = false
        private set

    /** Whether a failure has arrived. */
    var hasFailed: Boolean = false
        private set

    /** Whether [dispose] was called. */
    var isDisposed: Boolean = false
        private set

    private val issued: MutableMap<NativeCall, Int> = EnumMap(NativeCall::class.java)
    private var targetAfterReady: NativeStatus? = null

    init {
        if (!script.neverReady) host.after(script.readyLatencyMs) { becomeReady() }
        for (failure in script.failures) failAt(failure.delayMs, failure.cause, failure.via)
    }

    /** Writes the rate property; returns whether it changed (B1). A disposed player ignores the write. */
    fun writeRate(value: Double): Boolean {
        if (isDisposed || value == rate) return false
        rate = value
        return true
    }

    /** Writes the volume property; returns whether it changed (B1). A disposed player ignores the write. */
    fun writeVolume(value: Double): Boolean {
        if (isDisposed || value == volume) return false
        volume = value
        return true
    }

    /** Writes the cycle count property; returns whether it changed (B1). A disposed player ignores the write. */
    fun writeCycleCount(value: Int): Boolean {
        if (isDisposed || value == cycleCount) return false
        cycleCount = value
        return true
    }

    /** B2: a `play()` or `pause()` arrived; the status follows after its latency. */
    fun issue(call: NativeCall) {
        require(call == NativeCall.PLAY || call == NativeCall.PAUSE) { "only PLAY and PAUSE move the status, was $call" }
        if (isDisposed) return
        val nth: Int = (issued[call] ?: 0) + 1
        issued[call] = nth
        val target: NativeStatus = if (call == NativeCall.PLAY) NativeStatus.PLAYING else NativeStatus.PAUSED
        host.after(script.statusLatencyFor(call, nth)) { land(target) }
    }

    /** B9: [delayMs] from now [cause] arrives on [via]; same effect as a scripted failure. */
    fun failAt(delayMs: Double, cause: Throwable, via: FailureVia = FailureVia.BOTH) {
        require(delayMs >= 0.0 && delayMs.isFinite()) { "delayMs must be finite and not negative, was $delayMs" }
        host.after(delayMs) { fail(cause, via) }
    }

    /** `player.dispose()`: DISPOSED at once; what was pending is dropped. Idempotent. */
    fun dispose() {
        if (isDisposed) return
        isDisposed = true
        targetAfterReady = null
        setStatus(NativeStatus.DISPOSED)
    }

    /**
     * Sets the status without any of the guards of [issue], [dispose] and the failure path, and delivers the status
     * event of a changed value. The seam of the stall and dispose parts (B10, B12): a part decides when the native side
     * moves the status on its own (STALLED, a late PLAYING over DISPOSED); this model only keeps the value and tells
     * the parts and the listener.
     */
    fun force(new: NativeStatus) = setStatus(new)

    private fun land(target: NativeStatus) {
        if (isDisposed || status == NativeStatus.HALTED) return
        if (!isReady) {
            targetAfterReady = target
            return
        }
        setStatus(target)
    }

    private fun becomeReady() {
        if (isDisposed || hasFailed || isReady) return
        isReady = true
        durationMs = clip.durationMs
        width = clip.width
        height = clip.height
        onReady()
        events()?.onDuration()
        events()?.onSize()
        events()?.onSize()
        setStatus(NativeStatus.READY)
        events()?.onReady()
        targetAfterReady?.let { setStatus(it) }
        targetAfterReady = null
    }

    private fun fail(cause: Throwable, via: FailureVia) {
        if (isDisposed) return
        hasFailed = true
        targetAfterReady = null
        if (via != FailureVia.MEDIA) playerError = cause
        if (via != FailureVia.PLAYER) mediaError = cause
        setStatus(NativeStatus.HALTED)
        if (via != FailureVia.PLAYER) events()?.onMediaError()
        if (via != FailureVia.MEDIA) events()?.onPlayerError()
    }

    private fun setStatus(new: NativeStatus) {
        val old: NativeStatus = status
        if (old == new) return
        status = new
        onStatusChange(old, new)
        events()?.onStatus(new)
    }
}
