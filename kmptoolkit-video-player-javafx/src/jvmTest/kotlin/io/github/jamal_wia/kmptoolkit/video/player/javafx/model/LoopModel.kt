package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * What a [LoopModel] reports to a watcher at the instant a loop cycle wraps.
 *
 * @param cycle the number of the cycle that just began, 2 for the first wrap.
 * @param holdUntilNanos the host clock value until which the published time of the new cycle holds still.
 */
internal data class LoopWrap(val cycle: Int, val holdUntilNanos: Long)

/**
 * B8, the looping part of the scripted player: what the native side does at the end of every cycle of a source
 * whose `cycleCount` is INDEFINITE (-1) or more than 1.
 *
 * At each wrap, in this order, in one virtual instant:
 * 1. the playhead wraps to `startTimeMs`;
 * 2. every [onWrap] watcher runs: this is where the hazard part sets the decoder's rate back to 1.0 and opens the
 *    wrap window (the macOS player starts the next cycle at normal speed whatever the rate property says);
 * 3. `onEndOfMedia` is delivered, as it is at the end of EVERY cycle, so FxPlayback's handler finds the decoder already
 *    restarted; a cleared handler receives nothing.
 *
 * From the wrap on, the PUBLISHED time of the new cycle holds still for [publishHoldMs] and then catches up
 * (05-platform-notes, "Behaviour notes"): [isPublishHeld] and [heldValueMs] tell the time part what to publish.
 *
 * THE TRIGGER IS NOT HERE. A playhead that reaches the end of a cycle is the time part's business (B3, B5, B7), and
 * no time part may be assumed, so this part waits to be told: the time part, or a test, calls [endCycle] at the
 * instant the playhead reaches the duration. [endCycle] answers whether the source wrapped; when it did not
 * (a single cycle, or the last cycle of a finite count) the end is a plain end for B7 and nothing was changed.
 *
 * Install it in a slot of the player, alone or inside a [ModelPartGroup]. FX thread only.
 *
 * @param player the player it belongs to.
 * @param publishHoldMs how long the published time of a new cycle holds, 300 ms in the documented behaviour.
 */
internal class LoopModel(
    private val player: ScriptedNativePlayer,
    val publishHoldMs: Double = DEFAULT_PUBLISH_HOLD_MS,
) : ModelPart {

    init {
        require(publishHoldMs >= 0.0 && publishHoldMs.isFinite()) { "publishHoldMs must be finite and not negative, was $publishHoldMs" }
    }

    private val watchers: MutableList<(LoopWrap) -> Unit> = mutableListOf()
    private var holdUntilNanos: Long? = null

    /** The number of the cycle the playhead is in: 1 until the first wrap. */
    var cycle: Int = 1
        private set

    /** The published value that holds during the hold: the start time of the new cycle, as of the last wrap. */
    var heldValueMs: Double = 0.0
        private set

    /** Whether the source loops now: `cycleCount` is INDEFINITE or more than 1. */
    val isLooping: Boolean get() = player.cycleCount != SINGLE_CYCLE

    /** Whether the published time of the new cycle still holds, at the host's clock. False before the first wrap. */
    fun isPublishHeld(): Boolean {
        val until: Long = holdUntilNanos ?: return false
        // A difference, not a comparison of two readings: the clock has no zero and may wrap during the hold.
        return player.host.nanoTime() - until < 0L
    }

    /** Adds a watcher that runs at every wrap before `onEndOfMedia` (see the class KDoc, step 2). */
    fun onWrap(watcher: (LoopWrap) -> Unit) {
        watchers += watcher
    }

    /**
     * The playhead reached the end of a cycle. Wraps and returns true when the source loops and has a cycle left
     * (always, for INDEFINITE; fewer than `cycleCount` cycles begun, for a finite count); otherwise changes nothing,
     * delivers nothing and returns false.
     */
    fun endCycle(): Boolean {
        val count: Int = player.cycleCount
        val another: Boolean = count == INDEFINITE || (count > SINGLE_CYCLE && cycle < count)
        if (!another) return false
        cycle++
        player.positionMs = player.startMs
        heldValueMs = player.startMs
        val now: Long = player.host.nanoTime()
        val until: Long = now + (publishHoldMs * NANOS_PER_MILLI).toLong()
        holdUntilNanos = until
        val wrap = LoopWrap(cycle, until)
        watchers.toList().forEach { it(wrap) }
        player.deliverEndOfMedia()
        return true
    }

    override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {
        // A disposed source never wraps again; the hold ends with it.
        if (new == NativeStatus.DISPOSED) holdUntilNanos = null
    }

    companion object {
        /** The documented hold of the published time of a new cycle. */
        const val DEFAULT_PUBLISH_HOLD_MS: Double = 300.0

        /** `MediaPlayer.INDEFINITE`. */
        const val INDEFINITE: Int = -1

        private const val SINGLE_CYCLE: Int = 1
        private const val NANOS_PER_MILLI: Double = 1_000_000.0
    }
}

/**
 * Several [ModelPart]s in one slot of the scripted player, called in the order given, every hook forwarded.
 * The player has four slots; the loop, stall and dispose parts share the `loop` slot through this class.
 */
internal class ModelPartGroup(vararg parts: ModelPart) : ModelPart {

    private val members: List<ModelPart> = parts.toList()

    override fun onReady(player: ScriptedNativePlayer) = members.forEach { it.onReady(player) }

    override fun onPlay(player: ScriptedNativePlayer) = members.forEach { it.onPlay(player) }

    override fun onPause(player: ScriptedNativePlayer) = members.forEach { it.onPause(player) }

    override fun onSeek(player: ScriptedNativePlayer, millis: Double) = members.forEach { it.onSeek(player, millis) }

    override fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) =
        members.forEach { it.onRateChange(player, old, new) }

    override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) =
        members.forEach { it.onStatusChange(player, old, new) }

    override fun onDispose(player: ScriptedNativePlayer) = members.forEach { it.onDispose(player) }

    override fun onStartPulse(player: ScriptedNativePlayer) = members.forEach { it.onStartPulse(player) }

    override fun onStopPulse(player: ScriptedNativePlayer) = members.forEach { it.onStopPulse(player) }

    override fun onSnapshot(player: ScriptedNativePlayer, pixels: IntArray, width: Int, height: Int) =
        members.forEach { it.onSnapshot(player, pixels, width, height) }
}
