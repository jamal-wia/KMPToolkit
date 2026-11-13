package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

/**
 * B13, the picture part of the scripted player: the pulse and the snapshot.
 *
 * PULSE. From `startPulse()` to `stopPulse()` the player's listener gets `onPulse(now)` every [pulsePeriodNanos]
 * (16.67 ms, 60 Hz), the first one one period after the start, each on its exact virtual instant (the n-th at the start
 * plus n periods, rounded to the nanosecond, so the count over a window is exact and nothing drifts). `now` is the
 * host's clock. A second `startPulse()` while it runs changes nothing, and a `stopPulse()` drops the tick that was
 * queued; a restart begins a new count. The pulse is NOT tied to the player: like an `AnimationTimer` it keeps running
 * after `dispose()` until it is stopped, so a test can see a pulse that was left running.
 *
 * SNAPSHOT. `snapshotInto` writes the picture of the view into the caller's array only, `width * height` pixels
 * from index 0, ARGB: fully transparent (0) while the view holds no decoded picture, and opaque red afterwards
 * ([OPAQUE_RED]). The first picture exists [ClipModel.firstPictureAfterReadyMs] after the player became ready; before
 * ready there is none. After `detachView()` or after the player is disposed the view shows nothing, so the snapshot is
 * transparent again. The part keeps no reference to the array and touches nothing beyond `width * height`.
 *
 * Install it in a slot of the player, alone or inside a [ModelPartGroup]. FX thread only.
 *
 * @param player the player it belongs to.
 * @param pulsePeriodNanos the pulse period, 1/60 s by default.
 */
internal class PictureModel(
    private val player: ScriptedNativePlayer,
    val pulsePeriodNanos: Double = DEFAULT_PULSE_PERIOD_NANOS,
) : ModelPart {

    init {
        require(pulsePeriodNanos > 0.0 && pulsePeriodNanos.isFinite()) { "pulsePeriodNanos must be finite and positive, was $pulsePeriodNanos" }
    }

    private var firstPictureNanos: Long? = null
    private var pulseStartNanos: Long = 0L
    private var tick: Long = 0L
    private var generation: Int = 0
    private var running: Boolean = false
    private var disposed: Boolean = false

    /** How many pulses were delivered in total. */
    var pulsesDelivered: Int = 0
        private set

    /** The host clock value from which a snapshot is opaque, or null while the first picture does not exist. */
    val firstPictureAtNanos: Long? get() = firstPictureNanos

    override fun onReady(player: ScriptedNativePlayer) {
        firstPictureNanos = player.host.nanoTime() + (player.clip.firstPictureAfterReadyMs * NANOS_PER_MILLI).toLong()
    }

    override fun onStartPulse(player: ScriptedNativePlayer) {
        if (running) return
        running = true
        generation++
        pulseStartNanos = player.host.nanoTime()
        tick = 0L
        scheduleNext(generation)
    }

    override fun onStopPulse(player: ScriptedNativePlayer) {
        running = false
        generation++
    }

    override fun onDispose(player: ScriptedNativePlayer) {
        disposed = true
    }

    override fun onSnapshot(player: ScriptedNativePlayer, pixels: IntArray, width: Int, height: Int) {
        require(width > 0 && height > 0) { "the snapshot size must be positive, was ${width}x$height" }
        require(pixels.size >= width * height) { "the array holds ${pixels.size} pixels, a ${width}x$height snapshot needs ${width * height}" }
        val first: Long? = firstPictureNanos
        val hasPicture: Boolean = first != null && player.host.nanoTime() >= first && player.viewAttached && !disposed
        pixels.fill(if (hasPicture) OPAQUE_RED else TRANSPARENT, 0, width * height)
    }

    private fun scheduleNext(forGeneration: Int) {
        tick++
        val dueNanos: Long = pulseStartNanos + Math.round(tick * pulsePeriodNanos)
        // The half nanosecond keeps the host's truncation of the millisecond delay from losing one nanosecond.
        val delayMs: Double = (dueNanos - player.host.nanoTime() + 0.5) / NANOS_PER_MILLI
        player.host.after(delayMs) {
            if (forGeneration == generation) {
                pulsesDelivered++
                player.events?.onPulse(player.host.nanoTime())
                // The listener may have stopped or restarted the pulse; only the live generation goes on.
                if (forGeneration == generation) scheduleNext(forGeneration)
            }
        }
    }

    companion object {
        /** One 60 Hz pulse, 16.67 ms. */
        const val DEFAULT_PULSE_PERIOD_NANOS: Double = 1_000_000_000.0 / 60.0

        /** A fully transparent pixel: the view holds no decoded picture. */
        const val TRANSPARENT: Int = 0

        /** An opaque red pixel, ARGB: the decoded picture of the model. */
        const val OPAQUE_RED: Int = 0xFFFF0000.toInt()

        private const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}
