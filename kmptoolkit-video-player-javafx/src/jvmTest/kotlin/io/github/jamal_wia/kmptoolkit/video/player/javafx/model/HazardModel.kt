package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import java.util.EnumMap

/**
 * The fragile windows of design section 4.2 (B6): while one is open, a native rate change freezes the macOS decoder.
 * [label] is the name used in failure messages and in the design.
 */
internal enum class FragileWindow(val label: String) {
    /** Opens at a `play()` from not-running with no seek since the last `play()`. */
    PLAY("W-PLAY"),

    /** Not a window that opens: a `play()` within the idle gap of a property change made while not running freezes. */
    IDLE("W-IDLE"),

    /** Opens at the start of a loop cycle; the loop part (J7b) opens it through [HazardModel.onLoopWrap]. */
    WRAP("W-WRAP"),

    /** Opens at a `seek()` issued while the decoder runs (and, under [SeekHazardReading.STRICT], at any seek). */
    SEEK_RUN("W-SEEK-RUN"),
}

/**
 * Q-M1: which seeks open [FragileWindow.SEEK_RUN]. [DEFAULT] is the reading the H0 probe is expected to confirm: only a
 * seek issued while the decoder runs. [STRICT] is the worst case: any seek. Under [DEFAULT] a rate change within the
 * window of a seek that does NOT qualify is not a violation; it is listed in [HazardModel.strictHits], so the
 * strict reading is reported without failing.
 */
internal enum class SeekHazardReading { DEFAULT, STRICT }

/** B11: how a frozen decoder comes back. */
internal enum class FreezeRecovery {
    /** Never (the measured D2-PF1 behaviour: not even a replay from Playing unfreezes it). */
    NEVER,

    /** A `pause()` followed by a `play()` restarts it. */
    PAUSE_PLAY,
}

/**
 * The knobs of the hazard part; every default is the value of design section 4. The window widths are in virtual
 * milliseconds; a window of width W is open for a rate change that arrives strictly less than W after it opened.
 *
 * @param decoderStartsAtUnity B4: a `play()` from not-running starts the decoder at 1.0 whatever the property holds
 *   (macOS). False models a platform where it starts at the property's rate.
 */
internal data class HazardConfig(
    val playWindowMs: Double = DEFAULT_PLAY_MS,
    val idleGapMs: Double = DEFAULT_IDLE_MS,
    val wrapWindowMs: Double = DEFAULT_WRAP_MS,
    val seekRunWindowMs: Double = DEFAULT_SEEK_RUN_MS,
    val seekReading: SeekHazardReading = SeekHazardReading.DEFAULT,
    val recovery: FreezeRecovery = FreezeRecovery.NEVER,
    val decoderStartsAtUnity: Boolean = true,
) {
    init {
        for ((name: String, value: Double) in listOf(
            "playWindowMs" to playWindowMs, "idleGapMs" to idleGapMs,
            "wrapWindowMs" to wrapWindowMs, "seekRunWindowMs" to seekRunWindowMs,
        )) {
            require(value >= 0.0 && value.isFinite()) { "$name must be finite and not negative, was $value" }
        }
    }

    /** The width of [window] in virtual milliseconds. */
    fun widthMs(window: FragileWindow): Double = when (window) {
        FragileWindow.PLAY -> playWindowMs
        FragileWindow.IDLE -> idleGapMs
        FragileWindow.WRAP -> wrapWindowMs
        FragileWindow.SEEK_RUN -> seekRunWindowMs
    }

    companion object {
        /** W-PLAY: 100 ms. */
        const val DEFAULT_PLAY_MS: Double = 100.0

        /** W-IDLE: 200 ms. */
        const val DEFAULT_IDLE_MS: Double = 200.0

        /** W-WRAP: 100 ms. */
        const val DEFAULT_WRAP_MS: Double = 100.0

        /** W-SEEK-RUN: 300 ms. */
        const val DEFAULT_SEEK_RUN_MS: Double = 300.0
    }
}

/**
 * One native rate change (or, for [FragileWindow.IDLE], one `play()`) that landed in a fragile window.
 *
 * @param tMs virtual time of the offending call, milliseconds since the host started.
 * @param openedAtMs when the window opened (for W-IDLE: the property change).
 * @param widthMs the window's width.
 * @param trigger the offending call: `SET_RATE`, or `PLAY` for W-IDLE.
 * @param arg the rate written, or null for the `play()`.
 * @param strictOnly true for a hit that only the STRICT reading of Q-M1 counts (see [HazardModel.strictHits]).
 */
internal data class HazardViolation(
    val window: FragileWindow,
    val tMs: Double,
    val openedAtMs: Double,
    val widthMs: Double,
    val trigger: NativeCall,
    val arg: Double?,
    val strictOnly: Boolean = false,
) {
    /** One line for a failure message. */
    fun describe(): String =
        "${window.label}: $trigger${if (arg != null) "($arg)" else ""} at t=${fmt(tMs)} ms came ${fmt(tMs - openedAtMs)} ms " +
            "after the window opened at t=${fmt(openedAtMs)} ms (width ${fmt(widthMs)} ms)" +
            if (strictOnly) " [strict reading only]" else ""

    private fun fmt(v: Double): String = if (v == Math.rint(v) && v.isFinite()) v.toLong().toString() else v.toString()
}

/**
 * The hazard part of the scripted player (design 4.1 B4, B6, B11 and 4.2): it knows whether the native decoder
 * runs, at which rate, whether a fragile window is open, and whether the decoder is frozen. Install it as
 * `player.hazard = HazardModel(host)`, or through [withHazards] to get a [HazardMonitor] too.
 *
 * DECODER STATE. The decoder runs from a `play()` the player can act on (READY, PAUSED, STOPPED, STALLED or PLAYING
 * status; before READY the play waits and starts the decoder when the status reaches PLAYING; a HALTED or disposed
 * player never starts it) until a `pause()`, a failure or `dispose()`. It is driven by the commands, not by the
 * status, because the native status lags a command (B2) while the decoder does not. A pause also closes every window.
 *
 * WINDOWS (B6). Only a native rate change while the decoder runs is checked against [FragileWindow.PLAY],
 * [FragileWindow.WRAP] and [FragileWindow.SEEK_RUN], and only a REAL one: a write of the rate the decoder already plays
 * at ([effectiveRate]) is a property change with nothing native behind it (B1), so it judges no window and records no
 * strict hit (the loop wrap's unity write at a speed is such a write, B8). A rate change while the decoder does not
 * run is a property change only; it starts the W-IDLE gap, and a `play()` less than the gap later freezes, unless a
 * native seek came in between: under [SeekHazardReading.DEFAULT] a seek of a player that does not run clears the gap
 * (the measured W-IDLE is the plain resume, with no seek), and a `play()` inside the cleared gap is only a strict-only
 * IDLE hit in [strictHits]; STRICT keeps the gap. A window of width W opened at t is open for a change at t' with
 * t' - t < W, so a change exactly W later is safe. A new seek while running restarts W-SEEK-RUN (S9: the
 * seek-during-wrap hazard is the same predicate).
 *
 * B4. A `play()` from not-running sets [effectiveRate] to 1.0 (see [HazardConfig.decoderStartsAtUnity]); only a
 * later effective property change sets it again. A loop wrap resets it to 1.0 too ([onLoopWrap], B8).
 *
 * B11. A violation sets [frozen]: the status stays PLAYING and the playhead must stop, which is the time part's job
 * (J6b reads [frozen]; samples then carry the same value). Recovery is [HazardConfig.recovery].
 *
 * Clock: the host's `nanoTime`, compared as differences, so any epoch works.
 *
 * Single-threaded, like the rest of the player: every hook runs on the model's FX thread.
 *
 * @param host the model's FX thread and clock.
 * @param config widths, readings and recovery.
 */
internal class HazardModel(
    private val host: VirtualFxHost,
    val config: HazardConfig = HazardConfig(),
) : ModelPart {

    private val openedAt: MutableMap<FragileWindow, Long> = EnumMap(FragileWindow::class.java)
    private val recorded: MutableList<HazardViolation> = mutableListOf()
    private val recordedStrict: MutableList<HazardViolation> = mutableListOf()
    private val observers: MutableList<(HazardViolation) -> Unit> = mutableListOf()
    private var idleChangeNanos: Long? = null
    private var shadowSeekNanos: Long? = null
    private var shadowIdleNanos: Long? = null
    private var seekSinceLastPlay: Boolean = false
    private var playPending: Boolean = false
    private var recoverOnPlay: Boolean = false

    /** Whether the native decoder runs (see the class KDoc for what starts and stops it). */
    var decoderRunning: Boolean = false
        private set

    /** The rate the decoder plays at; moved by B4 at a play, by an effective rate change while running, by a wrap. */
    var effectiveRate: Double = 1.0
        private set

    /** B11: the decoder is stuck. The status stays PLAYING and the playhead stops; the time part reads this. */
    var frozen: Boolean = false
        private set

    /** Virtual milliseconds at which the decoder froze, or null. */
    var frozenAtMs: Double? = null
        private set

    /** Every violation of a window under the active reading, oldest first. */
    val violations: List<HazardViolation> get() = recorded.toList()

    /** The rate changes that only the STRICT reading of Q-M1 counts, oldest first; empty under STRICT itself. */
    val strictHits: List<HazardViolation> get() = recordedStrict.toList()

    /** The windows that are open right now (W-IDLE is never in it: it has no open phase). */
    val openWindows: Set<FragileWindow>
        get() {
            val now: Long = host.nanoTime()
            return FragileWindow.entries.filter { isOpen(it, now) }.toSet()
        }

    /** Calls [observer] for every violation (not for strict hits), after the decoder froze. */
    fun addObserver(observer: (HazardViolation) -> Unit) {
        observers += observer
    }

    /**
     * B8: a loop cycle started. Opens [FragileWindow.WRAP] and resets the decoder to 1.0. Called by the loop part.
     */
    fun onLoopWrap(player: ScriptedNativePlayer) {
        openedAt[FragileWindow.WRAP] = host.nanoTime()
        effectiveRate = 1.0
    }

    override fun onPlay(player: ScriptedNativePlayer) {
        when (player.status) {
            NativeStatus.UNKNOWN -> playPending = true
            NativeStatus.HALTED, NativeStatus.DISPOSED -> Unit
            else -> startDecoder(player)
        }
    }

    override fun onPause(player: ScriptedNativePlayer) {
        playPending = false
        if (!decoderRunning) return
        stopDecoder()
        if (frozen && config.recovery == FreezeRecovery.PAUSE_PLAY) recoverOnPlay = true
    }

    override fun onSeek(player: ScriptedNativePlayer, millis: Double) {
        val now: Long = host.nanoTime()
        seekSinceLastPlay = true
        if (!decoderRunning && config.seekReading == SeekHazardReading.DEFAULT) {
            // MUST-2: a native seek of a player that does not run clears the idle gap; the gap stays as a shadow.
            val idle: Long? = idleChangeNanos
            if (idle != null) {
                shadowIdleNanos = idle
                idleChangeNanos = null
            }
        }
        when {
            decoderRunning -> {
                openedAt[FragileWindow.SEEK_RUN] = now
                shadowSeekNanos = null
            }
            config.seekReading == SeekHazardReading.STRICT -> openedAt[FragileWindow.SEEK_RUN] = now
            else -> shadowSeekNanos = now
        }
    }

    override fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) {
        val now: Long = host.nanoTime()
        if (!decoderRunning) {
            idleChangeNanos = now
            shadowIdleNanos = null
            return
        }
        // MUST-1 (B1, B6: a native rate change is a real change only): the decoder already plays at this rate, so the
        // property write changes nothing native. No window is judged and no strict hit is recorded.
        if (new == effectiveRate) return
        val hits: MutableList<HazardViolation> = mutableListOf()
        for (window: FragileWindow in listOf(FragileWindow.PLAY, FragileWindow.WRAP, FragileWindow.SEEK_RUN)) {
            if (isOpen(window, now)) hits += violation(window, openedAt.getValue(window), now, NativeCall.SET_RATE, new, false)
        }
        val shadow: Long? = shadowSeekNanos
        if (shadow != null && withinWidth(shadow, now, config.seekRunWindowMs)) {
            recordedStrict += violation(FragileWindow.SEEK_RUN, shadow, now, NativeCall.SET_RATE, new, true)
        }
        effectiveRate = new
        report(hits)
    }

    override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {
        when {
            new == NativeStatus.PLAYING && playPending -> startDecoder(player)
            new == NativeStatus.HALTED -> stopDecoder()
        }
    }

    override fun onDispose(player: ScriptedNativePlayer) = stopDecoder()

    private fun startDecoder(player: ScriptedNativePlayer) {
        playPending = false
        val seekedSinceLastPlay: Boolean = seekSinceLastPlay
        seekSinceLastPlay = false
        if (decoderRunning) return
        val now: Long = host.nanoTime()
        decoderRunning = true
        effectiveRate = if (config.decoderStartsAtUnity) 1.0 else player.rate
        if (recoverOnPlay) {
            recoverOnPlay = false
            frozen = false
            frozenAtMs = null
        }
        val hits: MutableList<HazardViolation> = mutableListOf()
        val idle: Long? = idleChangeNanos
        idleChangeNanos = null
        if (idle != null && withinWidth(idle, now, config.idleGapMs)) {
            hits += violation(FragileWindow.IDLE, idle, now, NativeCall.PLAY, null, false)
        }
        val idleShadow: Long? = shadowIdleNanos
        shadowIdleNanos = null
        if (idleShadow != null && withinWidth(idleShadow, now, config.idleGapMs)) {
            recordedStrict += violation(FragileWindow.IDLE, idleShadow, now, NativeCall.PLAY, null, true)
        }
        if (!seekedSinceLastPlay) openedAt[FragileWindow.PLAY] = now
        report(hits)
    }

    private fun stopDecoder() {
        playPending = false
        decoderRunning = false
        openedAt.clear()
    }

    private fun report(hits: List<HazardViolation>) {
        if (hits.isEmpty()) return
        recorded += hits
        if (!frozen) {
            frozen = true
            frozenAtMs = hits.first().tMs
        }
        for (hit: HazardViolation in hits) for (observer in observers.toList()) observer(hit)
    }

    private fun violation(
        window: FragileWindow, openedNanos: Long, now: Long, trigger: NativeCall, arg: Double?, strictOnly: Boolean,
    ): HazardViolation {
        val epoch: Long = host.epochNanos
        return HazardViolation(
            window = window,
            tMs = (now - epoch) / NANOS_PER_MILLI,
            openedAtMs = (openedNanos - epoch) / NANOS_PER_MILLI,
            widthMs = config.widthMs(window),
            trigger = trigger,
            arg = arg,
            strictOnly = strictOnly,
        )
    }

    private fun isOpen(window: FragileWindow, now: Long): Boolean {
        val opened: Long = openedAt[window] ?: return false
        return withinWidth(opened, now, config.widthMs(window))
    }

    private fun withinWidth(openedNanos: Long, now: Long, widthMs: Double): Boolean =
        now - openedNanos < Math.round(widthMs * NANOS_PER_MILLI)

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}

/** The installed [HazardModel], or null when another part (or none) holds the hazard slot. */
internal val ScriptedNativePlayer.hazardModel: HazardModel? get() = hazard as? HazardModel
