package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.FxHost
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeBackend
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativePlayer
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.math.floor
import kotlin.random.Random

/**
 * When the `LOAD` profile of [TimeScript] reads the native playhead for a sample (B3).
 *
 * JavaFX reads it when the publication RUNS: `MediaPlayer.MediaTimerTask.run` posts `updateTime()` to the FX thread every
 * 100 ms, and `updateTime` calls `jfxPlayer.getPresentationTime()` on the FX thread when the runnable runs
 * (OpenJFX 27.0.1, `MediaPlayer.java` lines 2847-2862 and 1771-1783; `seek` does not touch `currentTime`). A publication
 * that waits on a busy FX thread therefore carries the playhead of the moment it is published, and a publication queued
 * ahead of a seek block runs ahead of it and carries the old position.
 */
internal enum class SampleRead {
    /**
     * The playhead is read when the sample is TAKEN and published after its delay, so a value read before a seek can land
     * after the seek has run, up to the longest delay later. JavaFX cannot do that; the option stands in for a native seek
     * latency of up to the longest delay, which no backend has shown. The default, and what every test before
     * [AT_PUBLISH] was written against.
     */
    AT_TICK,

    /**
     * The playhead is read when the publication RUNS, as `MediaPlayer.updateTime` does. A tick schedules a publication
     * after the seeded delay; publications run in the order they were posted (the run time is the later of the previous
     * publication's run time and the tick plus the delay, as an FX queue is FIFO). Drops are unchanged.
     */
    AT_PUBLISH,
}

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

    /**
     * The stale first publication after a resume (opt-in, off by default; every test written before it is unchanged).
     *
     * Measured on real media (D2 round 11, the NudgeTimeline probe, 14 of 14 resumes): after a `play()` of a paused
     * player the first reported time carries a stale value, and a fresh one follows 2 to 5 ms later. The stale value is
     * about a clip's worth behind the decoder (424 ms in the probe). With this set, the first sample TAKEN BY A TICK
     * after a `play()` that started a decoder which was not running publishes `max(0, native - lagMs)` instead of the
     * native position, and a second, ordinary sample (the load profile applies to it) is taken [StaleFirstPublication.freshAfterMs]
     * later. Ticks that fall inside that wait publish nothing (the stale value stands), later ticks are ordinary.
     */
    var staleFirstPublicationAfterPlay: StaleFirstPublication? = null

    /** [staleFirstPublicationAfterPlay]: how far behind the stale value is, and how soon the fresh one follows. */
    class StaleFirstPublication(val lagMs: Double, val freshAfterMs: Double) {
        init {
            require(lagMs >= 0.0 && lagMs.isFinite()) { "lagMs must be finite and not negative, was $lagMs" }
            require(freshAfterMs > 0.0 && freshAfterMs.isFinite()) { "freshAfterMs must be finite and positive, was $freshAfterMs" }
        }
    }

    private var loadProfile: LoadProfile? = null

    private class LoadProfile(
        val seed: Long,
        val minDelayMs: Double,
        val maxDelayMs: Double,
        val dropFraction: Double,
        val read: SampleRead,
    ) {
        val random: Random = Random(seed)
    }

    /** Whether [load] was switched on. */
    val isLoaded: Boolean get() = loadProfile != null

    /** When a sample reads the playhead: the [load] profile's choice, [SampleRead.AT_TICK] without a profile. */
    val sampleRead: SampleRead get() = loadProfile?.read ?: SampleRead.AT_TICK

    /**
     * B3, the `LOAD` profile of a busy machine: every sample waits a seeded delay in [minDelayMs] until
     * [maxDelayMs] (replacing [publishDelayMs]) and a seeded [dropFraction] of the samples is never published.
     * Delays differ per sample; with [SampleRead.AT_TICK] (the default) a later sample can land before an earlier one.
     * [read] says when the playhead is read: at the tick (the default, unchanged) or when the publication runs, as JavaFX
     * does ([SampleRead.AT_PUBLISH]; see there for the source lines). A twin of a real-media assertion that reads the
     * engine uses AT_PUBLISH; AT_TICK stays as the stress profile.
     */
    fun load(
        seed: Long = VirtualFxHost.defaultSeed(),
        minDelayMs: Double = LOAD_MIN_DELAY_MS,
        maxDelayMs: Double = LOAD_MAX_DELAY_MS,
        dropFraction: Double = LOAD_DROP_FRACTION,
        read: SampleRead = SampleRead.AT_TICK,
    ): TimeScript = apply {
        require(minDelayMs >= 0.0 && minDelayMs.isFinite()) { "minDelayMs must be finite and not negative, was $minDelayMs" }
        require(maxDelayMs >= minDelayMs && maxDelayMs.isFinite()) { "maxDelayMs must be finite and at least minDelayMs, was $maxDelayMs" }
        require(dropFraction in 0.0..1.0) { "dropFraction must be within 0 and 1, was $dropFraction" }
        loadProfile = LoadProfile(seed, minDelayMs, maxDelayMs, dropFraction, read)
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
            staleFirstPublicationAfterPlay = source.staleFirstPublicationAfterPlay
            source.loadProfile?.let { p -> load(seed ?: p.seed, p.minDelayMs, p.maxDelayMs, p.dropFraction, p.read) }
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

    // A play() started a decoder that was not running and the first tick after it has not taken its stale sample yet.
    private var staleFirstArmed: Boolean = false

    // The fresh sample of a stale first publication is due at this instant; ticks before it find the stale value standing.
    private var staleHoldUntilNanos: Long = 0L

    /** How many samples were taken so far, dropped ones included. */
    var samplesTaken: Int = 0
        private set

    /** How many samples the `LOAD` profile dropped. */
    var samplesDropped: Int = 0
        private set

    /** One publication of a [SampleRead.AT_PUBLISH] sample that ran: when it was posted, its seeded delay, when it ran. */
    class Publication(
        val index: Int,
        val tickMs: Double,
        val delayMs: Double,
        val runMs: Double,
        val value: Double,
    )

    private val publicationLog: MutableList<Publication> = mutableListOf()

    // The nominal run time of the newest publication posted, in host nanoseconds; the next one never runs before it.
    private var lastPublicationDueNanos: Long = 0L
    private var publicationsPosted: Int = 0
    private val postedPublications: ArrayDeque<PostedPublication> = ArrayDeque()

    /**
     * When the oldest [SampleRead.AT_PUBLISH] publication still waiting is due, in host nanoseconds elapsed (the nominal
     * time; a jittered host fires it later), or null when none waits. A test that calls the engine from a
     * [VirtualFxHost.beforeEachTask] hook while this equals `host.elapsedNanos` queues its call BEHIND that publication,
     * as a call made while the FX thread is busy publishing is.
     */
    val nextPublicationDueNanos: Long? get() = postedPublications.firstOrNull()?.dueNanos

    /** The [SampleRead.AT_PUBLISH] publications that ran so far, in the order they ran; empty under AT_TICK. */
    val publications: List<Publication> get() = publicationLog.toList()

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

    override fun onPlay(player: ScriptedNativePlayer) {
        // This part's hooks run before the hazard part's, so isRunning still says what it was before this play().
        val wasRunning: Boolean = isRunning
        change {
            playRequested = true
            ended = false
            ownRate = player.rate
            if (!wasRunning) staleFirstArmed = script.staleFirstPublicationAfterPlay != null
        }
    }

    override fun onPause(player: ScriptedNativePlayer) = change {
        playRequested = false
        staleFirstArmed = false
    }

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
            takeSample(fromTick = true)
            scheduleTick(generation)
        }
    }

    private fun takeSample(fromTick: Boolean = false) {
        val value: Double = nativePositionMs
        samplesTaken++
        if (fromTick && host.elapsedNanos < staleHoldUntilNanos) return
        val stale: TimeScript.StaleFirstPublication? = script.staleFirstPublicationAfterPlay
        if (fromTick && staleFirstArmed && stale != null) {
            // A stale first publication (see TimeScript.staleFirstPublicationAfterPlay): the old value now, a fresh sample soon after.
            staleFirstArmed = false
            val staleValue: Double = (value - stale.lagMs).coerceAtLeast(0.0)
            host.after(script.publishDelayMs) { publish(staleValue) }
            staleHoldUntilNanos = host.elapsedNanos + (stale.freshAfterMs * NANOS_PER_MILLI).toLong()
            host.after(stale.freshAfterMs) { takeSample() }
            return
        }
        val delayMs: Double? = script.nextPublishDelayMs()
        if (delayMs == null) {
            samplesDropped++
            return
        }
        if (script.sampleRead == SampleRead.AT_PUBLISH) postPublication(delayMs) else host.after(delayMs) { publish(value) }
    }

    /**
     * [SampleRead.AT_PUBLISH]: the publication waits the seeded [delayMs], but never runs before the publication posted
     * ahead of it (an FX queue is FIFO), and reads the native playhead when it runs, as `MediaPlayer.updateTime` does
     * (OpenJFX `MediaPlayer.java` 1771-1783). A publication queued ahead of a seek block still runs first and carries the
     * old position (the hazard `Session.seeksInFlight` guards); one that runs after the seek block reads the new one.
     */
    private fun postPublication(delayMs: Double) {
        val tickNanos: Long = host.elapsedNanos
        val wait: Long = lastPublicationDueNanos - tickNanos
        var runDelayMs: Double = delayMs
        if ((runDelayMs * NANOS_PER_MILLI).toLong() < wait) {
            runDelayMs = wait / NANOS_PER_MILLI
            // A double does not always give back the whole nanoseconds; the run must not fall a nanosecond short of the previous one.
            while ((runDelayMs * NANOS_PER_MILLI).toLong() < wait) runDelayMs = Math.nextUp(runDelayMs)
        }
        lastPublicationDueNanos = tickNanos + (runDelayMs * NANOS_PER_MILLI).toLong()
        postedPublications += PostedPublication(publicationsPosted++, tickNanos / NANOS_PER_MILLI, delayMs, lastPublicationDueNanos)
        // Each timer runs the OLDEST publication still waiting, so even a host whose jitter makes a later timer fire first
        // keeps the posting order; no timer fires before its own nominal time, and the nominal times never decrease.
        host.after(runDelayMs) {
            val posted: PostedPublication = postedPublications.removeFirst()
            val value: Double = nativePositionMs
            publicationLog += Publication(posted.index, posted.tickMs, posted.delayMs, host.elapsedNanos / NANOS_PER_MILLI, value)
            publish(value)
        }
    }

    private class PostedPublication(val index: Int, val tickMs: Double, val delayMs: Double, val dueNanos: Long)

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
        // JavaFX changes currentCount in the task that decides, just before it reads cycleCount (no task can run in between, and the
        // engine's listener only takes a snapshot, so running the step right after the read is equivalent), and posts the end of media as
        // a later task: a task a listener of the decision queues runs between the two.
        val wrapped: Boolean = loop?.endCycle { player.deliverCycleDecision() } ?: run {
            player.deliverCycleDecision()
            false
        }
        if (wrapped) {
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
