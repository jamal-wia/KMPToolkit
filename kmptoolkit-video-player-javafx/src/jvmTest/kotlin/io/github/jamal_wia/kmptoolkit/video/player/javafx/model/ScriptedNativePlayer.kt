package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * An extension point of the [ScriptedNativePlayer]: a part of the native model that is added in its own file
 * (the time and seek part, the hazard part, the loop and stall and dispose parts, the picture part) and
 * reacts to what the player receives. Every hook is empty here, so a slot holding [None] changes nothing.
 *
 * Hooks run on the model's FX thread, in the order time, hazard, loop, picture, AFTER the call was logged and
 * after the player's own state changed (a status hook sees the new status in `player.status`).
 */
internal interface ModelPart {
    /** The ready values were just set; no event was delivered yet. */
    fun onReady(player: ScriptedNativePlayer) {}

    /** `play()` was received by a live player. The status has not changed yet (B2). */
    fun onPlay(player: ScriptedNativePlayer) {}

    /** `pause()` was received by a live player. */
    fun onPause(player: ScriptedNativePlayer) {}

    /** `seek(millis)` was received by a live player. */
    fun onSeek(player: ScriptedNativePlayer, millis: Double) {}

    /** The rate property really changed from [old] to [new] (never called for a write of the held value). */
    fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) {}

    /** The status changed; the status event is delivered after the hooks. */
    fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {}

    /** `dispose()` was received, the first time. The status is already DISPOSED. */
    fun onDispose(player: ScriptedNativePlayer) {}

    /** `startPulse()` was received. */
    fun onStartPulse(player: ScriptedNativePlayer) {}

    /** `stopPulse()` was received. */
    fun onStopPulse(player: ScriptedNativePlayer) {}

    /** `snapshotInto` was received: the part writes the picture into [pixels]; with no part the array is untouched. */
    fun onSnapshot(player: ScriptedNativePlayer, pixels: IntArray, width: Int, height: Int) {}

    companion object {
        /** The part of an empty slot. */
        val None: ModelPart = object : ModelPart {}
    }
}

/**
 * A scripted stand-in for `JavaFxNativePlayer`: it implements [NativePlayer] on the model's virtual time, with the
 * behaviours of design section 4 added part by part. This skeleton carries the call log, the property, status
 * and ready model ([StatusModel]: B1, B2, B9) and the clip ([ClipModel]); the playhead, the hazards, looping and the
 * picture are the empty slots [time], [hazard], [loop] and [picture], filled by later parts.
 *
 * CONTRACT KEPT: each member is one native call and decides nothing. Every command and write is logged in [log]
 * before it acts. A disposed player ignores commands and writes, and logs them as not effective.
 *
 * NO TIME MODEL YET: [positionMs] is a plain value that only a part (or a test) moves. `seek` is logged and
 * handed to the parts, and moves nothing itself; `currentTimeMs` therefore reads 0 until a time part runs.
 * [startMs] and [bufferMs] are plain values too.
 *
 * THREADS: like the real player, every member is for the FX thread, which here is the host's driving thread.
 * Nothing is checked; the hazard monitor (J7a) is where contract violations are caught.
 *
 * @param host the model's FX thread and clock.
 * @param clip the source being played.
 * @param script when and how this player becomes ready, fails and lags; use a [StatusScript.copy] of a shared
 *   script when more than one player is built.
 */
internal class ScriptedNativePlayer(
    val host: VirtualFxHost,
    val clip: ClipModel = ClipModel(),
    val script: StatusScript = StatusScript(),
) : NativePlayer {

    /** Everything the player was asked to do, with virtual timestamps. */
    val log: NativeCallLog = NativeCallLog { host.elapsedNanos / NANOS_PER_MILLI }

    /** The slot of the time and seek part (B3, B5, B7). */
    var time: ModelPart = ModelPart.None

    /** The slot of the hazard part (B4, B6, B11). */
    var hazard: ModelPart = ModelPart.None

    /** The slot of the loop part (B8). */
    var loop: ModelPart = ModelPart.None

    /** The slot of the picture part (B13). */
    var picture: ModelPart = ModelPart.None

    /** The playhead in milliseconds: what `currentTimeMs` reads. Moved by a part or a test. */
    var positionMs: Double = 0.0

    /** What `startTimeMs` reads. */
    var startMs: Double = 0.0

    /** What `bufferProgressMs` reads. */
    var bufferMs: Double = 0.0

    /** The listener `setEvents` installed, for parts that deliver events; null before it. */
    var events: NativeEvents? = null
        private set

    /** Whether `onEndOfMedia` is still installed: false after `clearEndOfMedia`. */
    var endOfMediaHandlerInstalled: Boolean = true
        private set

    /** Whether the view is still attached: false after `detachView`. */
    var viewAttached: Boolean = true
        private set

    /** Whether the pulse is running: set by `startPulse`, cleared by `stopPulse`. */
    var pulsing: Boolean = false
        private set

    private val state: StatusModel = StatusModel(
        host = host,
        clip = clip,
        script = script,
        events = { events },
        onStatusChange = { old, new -> parts().forEach { it.onStatusChange(this, old, new) } },
        onReady = { parts().forEach { it.onReady(this) } },
    )

    private fun parts(): List<ModelPart> = listOf(time, hazard, loop, picture)

    private fun record(call: NativeCall, arg: Double?, effective: Boolean) =
        log.record(call, arg, state.status, positionMs, effective)

    /** B9 at runtime: [cause] arrives [delayMs] from now on [via]. Same as a failure in the script. */
    fun failAt(delayMs: Double, cause: Throwable, via: FailureVia = FailureVia.BOTH) = state.failAt(delayMs, cause, via)

    /**
     * Sets the status as the native side does on its own, with no command and no guard: STALLED (B10), a late PLAYING
     * or PAUSED over DISPOSED (B12). Not a native call, so it is not logged. The parts' `onStatusChange` runs, then the
     * status event is delivered; the same value is no change.
     */
    fun forceStatus(new: NativeStatus) = state.force(new)

    /**
     * Delivers `onEndOfMedia` to the listener the way JavaFX's handler would: not at all after `clearEndOfMedia`,
     * unless [ignoreClearedHandler] says a queued handler call survives (B12). For the time, loop and dispose parts.
     */
    fun deliverEndOfMedia(ignoreClearedHandler: Boolean = false) {
        if (endOfMediaHandlerInstalled || ignoreClearedHandler) events?.onEndOfMedia()
    }

    override fun play() {
        val live: Boolean = !state.isDisposed
        record(NativeCall.PLAY, null, live)
        if (!live) return
        state.issue(NativeCall.PLAY)
        parts().forEach { it.onPlay(this) }
    }

    override fun pause() {
        val live: Boolean = !state.isDisposed
        record(NativeCall.PAUSE, null, live)
        if (!live) return
        state.issue(NativeCall.PAUSE)
        parts().forEach { it.onPause(this) }
    }

    override fun seek(millis: Double) {
        val live: Boolean = !state.isDisposed
        record(NativeCall.SEEK, millis, live)
        if (live) parts().forEach { it.onSeek(this, millis) }
    }

    override var rate: Double
        get() = state.rate
        set(value) {
            val old: Double = state.rate
            val changed: Boolean = state.writeRate(value)
            record(NativeCall.SET_RATE, value, changed)
            if (changed) parts().forEach { it.onRateChange(this, old, value) }
        }

    override var volume: Double
        get() = state.volume
        set(value) {
            record(NativeCall.SET_VOLUME, value, state.writeVolume(value))
        }

    override var cycleCount: Int
        get() = state.cycleCount
        set(value) {
            record(NativeCall.SET_CYCLE_COUNT, value.toDouble(), state.writeCycleCount(value))
        }

    override val status: NativeStatus get() = state.status

    override val currentTimeMs: Double get() = positionMs

    override val startTimeMs: Double get() = startMs

    override val bufferProgressMs: Double get() = bufferMs

    override val durationMs: Double get() = state.durationMs

    override val width: Int get() = state.width

    override val height: Int get() = state.height

    override val playerError: Throwable? get() = state.playerError

    override val mediaError: Throwable? get() = state.mediaError

    override fun setEvents(events: NativeEvents) {
        record(NativeCall.SET_EVENTS, null, true)
        this.events = events
    }

    override fun clearEndOfMedia() {
        record(NativeCall.CLEAR_END_OF_MEDIA, null, true)
        endOfMediaHandlerInstalled = false
    }

    override fun detachView() {
        record(NativeCall.DETACH_VIEW, null, true)
        viewAttached = false
    }

    override fun dispose() {
        val first: Boolean = !state.isDisposed
        record(NativeCall.DISPOSE, null, first)
        if (!first) return
        state.dispose()
        parts().forEach { it.onDispose(this) }
    }

    override fun startPulse() {
        record(NativeCall.START_PULSE, null, true)
        pulsing = true
        parts().forEach { it.onStartPulse(this) }
    }

    override fun stopPulse() {
        record(NativeCall.STOP_PULSE, null, true)
        pulsing = false
        parts().forEach { it.onStopPulse(this) }
    }

    override fun snapshotInto(pixels: IntArray, width: Int, height: Int) {
        record(NativeCall.SNAPSHOT, null, true)
        parts().forEach { it.onSnapshot(this, pixels, width, height) }
    }

    override fun toString(): String =
        "ScriptedNativePlayer(status=${state.status}, positionMs=$positionMs, rate=${state.rate}, log=${log.size} entries)"

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}
