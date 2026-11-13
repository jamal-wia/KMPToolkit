package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxHost
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.math.floor
import kotlin.random.Random

/**
 * How the native playhead is sampled and published (B3) and where a seek lands (B5). One per [TimeModel]; take a
 * [copy] when more than one model is built from one script.
 *
 * Times are virtual milliseconds. All the numbers are read when they are used, so a test may change them while a
 * player runs.
 */
internal class TimeScript {

    /** B3: the distance between two samples of the playhead while it runs. JavaFX polls every 100 ms. */
    var sampleIntervalMs: Double = DEFAULT_SAMPLE_INTERVAL_MS
        set(value) {
            require(value > 0.0 && value.isFinite()) { "sampleIntervalMs must be finite and positive, was $value" }
            field = value
        }

    /** B3: how long a sample waits in the queue before it is published; 0 still goes through the queue. */
    var publishDelayMs: Double = 0.0
        set(value) {
            require(value >= 0.0 && value.isFinite()) { "publishDelayMs must be finite and not negative, was $value" }
            field = value
        }

    /** B5: a seek to a target at or below this lands on 0, whatever the key frames are. */
    var nearStartMs: Double = DEFAULT_NEAR_START_MS
        set(value) {
            require(value >= 0.0 && value.isFinite()) { "nearStartMs must be finite and not negative, was $value" }
            field = value
        }

    private var loadProfile: LoadProfile? = null

    private class LoadProfile(
        val seed: Long,
        val minDelayMs: Double,
        val maxDelayMs: Double,
        val dropFraction: Double,
    ) {
        val random: Random = Random(seed)
    }

    /** Whether [load] was switched on. */
    val isLoaded: Boolean get() = loadProfile != null

    /**
     * B3, the `LOAD` profile of a busy machine: every sample waits a seeded delay in [minDelayMs] until
     * [maxDelayMs] (replacing [publishDelayMs]) and a seeded [dropFraction] of the samples is never published.
     * Delays differ per sample, so a later sample can land before an earlier one.
     */
    fun load(
        seed: Long = VirtualFxHost.defaultSeed(),
        minDelayMs: Double = LOAD_MIN_DELAY_MS,
        maxDelayMs: Double = LOAD_MAX_DELAY_MS,
        dropFraction: Double = LOAD_DROP_FRACTION,
    ): TimeScript = apply {
        require(minDelayMs >= 0.0 && minDelayMs.isFinite()) { "minDelayMs must be finite and not negative, was $minDelayMs" }
        require(maxDelayMs >= minDelayMs && maxDelayMs.isFinite()) { "maxDelayMs must be finite and at least minDelayMs, was $maxDelayMs" }
        require(dropFraction in 0.0..1.0) { "dropFraction must be within 0 and 1, was $dropFraction" }
        loadProfile = LoadProfile(seed, minDelayMs, maxDelayMs, dropFraction)
    }

    /** The wait of the next sample in milliseconds, or null when the profile drops it. Draws from the seed. */
    fun nextPublishDelayMs(): Double? {
        val profile: LoadProfile = loadProfile ?: return publishDelayMs
        // Both draws are always made, so the sequence of delays does not depend on which samples were dropped.
        val dropDraw: Double = profile.random.nextDouble()
        val delayDraw: Double = profile.random.nextDouble()
        if (dropDraw < profile.dropFraction) return null
        return profile.minDelayMs + delayDraw * (profile.maxDelayMs - profile.minDelayMs)
    }

    /** An independent script with the same settings; a load profile restarts from its seed. */
    fun copy(seed: Long? = null): TimeScript {
        val source: TimeScript = this
        return TimeScript().apply {
            sampleIntervalMs = source.sampleIntervalMs
            publishDelayMs = source.publishDelayMs
            nearStartMs = source.nearStartMs
            source.loadProfile?.let { p -> load(seed ?: p.seed, p.minDelayMs, p.maxDelayMs, p.dropFraction) }
        }
    }

    companion object {
        /** The sample interval of design B3. */
        const val DEFAULT_SAMPLE_INTERVAL_MS: Double = 100.0

        /** The start window of design B5. */
        const val DEFAULT_NEAR_START_MS: Double = 100.0

        /** The shortest wait of the `LOAD` profile. */
        const val LOAD_MIN_DELAY_MS: Double = 50.0

        /** The longest wait of the `LOAD` profile. */
        const val LOAD_MAX_DELAY_MS: Double = 400.0

        /** The share of samples the `LOAD` profile drops. */
        const val LOAD_DROP_FRACTION: Double = 0.1
    }
}

/**
 * The time and seek part of the scripted native player (design B3, B5, B7): the native playhead, the samples of
 * it that JavaFX publishes, where a seek lands, and the end of the source. It fills the
 * [ScriptedNativePlayer.time] slot of [player] and composes with the other parts (see COMPOSITION).
 *
 * TWO POSITIONS. [nativePositionMs] is where the native decoder is. `player.currentTimeMs` is the PUBLISHED
 * position, the `currentTime` property, which only a published sample moves. A sample of the native position is
 * taken every [TimeScript.sampleIntervalMs] while the decoder runs, and again when a seek lands; it is published
 * through the host's queue after [TimeScript.publishDelayMs]; `onCurrentTime` fires only when the published
 * value really changed. A sample taken before a seek can therefore be published after it and put the old
 * position back over the new one, which is the hazard `Session.seeksInFlight` guards.
 *
 * WHEN THE DECODER RUNS, AND HOW FAST. With no hazard part: from a `play()` of a ready player until a `pause()`, a
 * failure or `dispose()`, at the rate property (a `play()` given before ready starts it at ready). With a
 * [HazardModel] in the hazard slot the decoder is the hazard part's: it runs while `decoderRunning`, at its
 * `effectiveRate` (B4: 1.0 after a plain play), and it stands still while `frozen` (B11). Either way the decoder
 * starts and stops at the COMMAND, not at the status (B2 lags the status, not the decoder). No real media measured
 * the moment the decoder starts; it is a modelling choice. A status of STALLED holds the playhead too (B10).
 *
 * COMPOSITION. This part's hooks run BEFORE the hazard part's, so a hook first PINS the native position (anchors it
 * at the present instant under the state as it was) and the state that follows is read lazily, never copied. The
 * samples and the end are re-planned by a block queued for the same instant, after the other parts' hooks ran.
 * Give a [LoopModel] to [loop] to model B8 (the end of a cycle is then the loop's `endCycle`, and samples that land
 * while its publish hold lasts are ignored, so the published time holds the start of the cycle and then catches
 * up); without one a looping player ends like any other (B7).
 *
 * SEEK (B5). A seek lands, at once, on the key frame at or before its target (a multiple of
 * `clip.keyFrameIntervalMs`), or on 0 when the target is at most [TimeScript.nearStartMs], and never beyond the
 * duration. It moves the native position, takes a sample and, while the decoder runs, goes on from there. A seek
 * before the player is ready, or to NaN, does nothing.
 *
 * END WITHOUT LOOPING (B7). When the decoder reaches the duration: the published position is set to the
 * duration at once (not through a sample), `onEndOfMedia` is queued behind what already waits, the status
 * is NOT touched (it stays PLAYING), the position holds at the duration, and sampling stops. A seek to before the
 * duration, or a new `play()`, goes on from there; a `play()` while the position is at the duration ends again at
 * once (unmeasured). [ScriptedNativePlayer.deliverEndOfMedia] decides whether a cleared handler hears the end.
 *
 * All the timers go through `host.after`, so a host with a jitter delays them too; the native position never
 * passes the duration because of it.
 *
 * @param player the player whose [ScriptedNativePlayer.time] slot this part fills; the part installs itself.
 * @param script when samples are taken and published and where seeks land.
 * @param loop the loop part of the same player, or null when the source does not loop.
 */
internal class TimeModel(
    private val player: ScriptedNativePlayer,
    val script: TimeScript = TimeScript(),
    private val loop: LoopModel? = null,
) : ModelPart {

    private val host: VirtualFxHost = player.host
    private val durationMs: Double = player.clip.durationMs

    // The decoder when no hazard part is installed.
    private var playRequested: Boolean = false
    private var ready: Boolean = false
    private var ownRate: Double = 1.0

    private var stopped: Boolean = false
    private var holding: Boolean = false
    private var ended: Boolean = false
    private var anchorMs: Double = 0.0
    private var anchorNanos: Long = host.elapsedNanos
    private var sampling: Boolean = false
    private var samplingGeneration: Int = 0
    private var endGeneration: Int = 0
    private var replanQueued: Boolean = false

    /** How many samples were taken so far, dropped ones included. */
    var samplesTaken: Int = 0
        private set

    /** How many samples the `LOAD` profile dropped. */
    var samplesDropped: Int = 0
        private set

    /** How many times the end of the source was reached (a loop wrap is not an end). */
    var endsReached: Int = 0
        private set

    private val hazard: HazardModel? get() = player.hazardModel

    /** Whether the decoder runs now, whatever it is doing: a frozen or stalled decoder still runs. */
    val isRunning: Boolean get() = hazard?.decoderRunning ?: (playRequested && ready && !stopped)

    /** How fast the decoder moves, in clip milliseconds per virtual millisecond. */
    val effectiveRate: Double get() = hazard?.effectiveRate ?: ownRate

    /** Whether the playhead stands still although the decoder runs: frozen (B11) or stalled (B10). */
    val isHeld: Boolean get() = holding || hazard?.frozen == true

    private val isAdvancing: Boolean get() = isRunning && !isHeld

    /** Where the native decoder is now: the native position, not the published one. */
    val nativePositionMs: Double
        get() {
            if (!isAdvancing) return anchorMs
            val elapsedMs: Double = (host.elapsedNanos - anchorNanos) / NANOS_PER_MILLI
            return (anchorMs + elapsedMs * effectiveRate).coerceIn(0.0, durationMs)
        }

    init {
        player.time = this
    }

    override fun onReady(player: ScriptedNativePlayer) = change { ready = true }

    override fun onPlay(player: ScriptedNativePlayer) = change {
        playRequested = true
        ended = false
        ownRate = player.rate
    }

    override fun onPause(player: ScriptedNativePlayer) = change { playRequested = false }

    override fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) = change { ownRate = new }

    override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) = change {
        holding = new == NativeStatus.STALLED
        if (new == NativeStatus.HALTED || new == NativeStatus.DISPOSED) stopped = true
    }

    override fun onDispose(player: ScriptedNativePlayer) = change { stopped = true }

    override fun onSeek(player: ScriptedNativePlayer, millis: Double) {
        if (!ready || stopped || millis.isNaN()) return
        anchorMs = landingMs(millis)
        anchorNanos = host.elapsedNanos
        ended = false
        requestReplan()
        takeSample()
    }

    /** B5: where a seek to [targetMs] lands. */
    fun landingMs(targetMs: Double): Double {
        val target: Double = targetMs.coerceIn(0.0, durationMs)
        if (target <= script.nearStartMs) return 0.0
        val interval: Double = player.clip.keyFrameIntervalMs
        return (floor(target / interval) * interval).coerceAtMost(durationMs)
    }

    /** Pins the position under the state as it is, applies [mutation] to this part's own state, and re-plans later. */
    private inline fun change(mutation: () -> Unit) {
        pin()
        mutation()
        requestReplan()
    }

    private fun pin() {
        anchorMs = nativePositionMs
        anchorNanos = host.elapsedNanos
    }

    // The other parts' hooks of this very event run after ours; one block queued for the same instant reads the
    // state they left, so the plan never uses a half-updated one.
    private fun requestReplan() {
        if (replanQueued) return
        replanQueued = true
        host.after(0.0) {
            replanQueued = false
            plan()
        }
    }

    private fun plan() {
        endGeneration++
        if (!isRunning) {
            samplingGeneration++
            sampling = false
            return
        }
        if (!ended && !sampling) startSampling()
        if (ended || !isAdvancing || effectiveRate <= 0.0) return
        val generation: Int = endGeneration
        host.after((durationMs - nativePositionMs) / effectiveRate) { if (generation == endGeneration) reachEnd() }
    }

    private fun startSampling() {
        sampling = true
        val generation: Int = ++samplingGeneration
        scheduleTick(generation)
    }

    private fun scheduleTick(generation: Int) {
        host.after(script.sampleIntervalMs) {
            if (generation != samplingGeneration || !isRunning || ended) {
                if (generation == samplingGeneration) sampling = false
                return@after
            }
            takeSample()
            scheduleTick(generation)
        }
    }

    private fun takeSample() {
        val value: Double = nativePositionMs
        samplesTaken++
        val delayMs: Double? = script.nextPublishDelayMs()
        if (delayMs == null) {
            samplesDropped++
            return
        }
        host.after(delayMs) { publish(value) }
    }

    private fun publish(value: Double) {
        if (stopped || value == player.positionMs) return
        if (loop?.isPublishHeld() == true) return
        player.positionMs = value
        player.events?.onCurrentTime()
    }

    private fun reachEnd() {
        if (stopped || !isAdvancing || ended) return
        if (nativePositionMs < durationMs - END_TOLERANCE_MS) {
            plan()
            return
        }
        pin()
        anchorMs = durationMs
        if (loop?.endCycle() == true) {
            // The loop part wrapped the playhead and published the start of the new cycle; the decoder goes on from it.
            anchorMs = player.startMs
            anchorNanos = host.elapsedNanos
            plan()
            return
        }
        endsReached++
        ended = true
        samplingGeneration++
        sampling = false
        publish(durationMs)
        host.postLater { player.deliverEndOfMedia() }
    }

    private companion object {
        const val NANOS_PER_MILLI: Double = 1_000_000.0

        /** A microsecond: a timer's nanosecond rounding must not make the end a hair short of the duration. */
        const val END_TOLERANCE_MS: Double = 1e-3
    }
}

/**
 * A [NativeBackend] whose players each get a [TimeModel], for a test that builds the player through the backend seam
 * (the engine tests): every player [create] makes is built by [inner], which must build [ScriptedNativePlayer]s (a
 * [ScriptedBackend], or a wrapper of one such as the hazard backend), and is then given a model of its own with a
 * [TimeScript.copy] of [script]. A hazard part the inner backend installs is found by the time model on its own.
 */
internal class TimedBackend(
    private val inner: NativeBackend,
    val script: TimeScript = TimeScript(),
) : NativeBackend {

    private val built: MutableList<TimeModel> = java.util.concurrent.CopyOnWriteArrayList()

    override val host: FxHost get() = inner.host

    /** The time models built so far, in the order of the players. */
    val models: List<TimeModel> get() = built.toList()

    /** The newest time model; fails when none was created yet. */
    val lastModel: TimeModel get() = checkNotNull(built.lastOrNull()) { "no player was created yet (${inner.host})" }

    override fun probe(uri: String): NativeSource = inner.probe(uri)

    override fun create(source: NativeSource): NativePlayer {
        val player: ScriptedNativePlayer = inner.create(source) as ScriptedNativePlayer
        built += TimeModel(player, script.copy())
        return player
    }
}
