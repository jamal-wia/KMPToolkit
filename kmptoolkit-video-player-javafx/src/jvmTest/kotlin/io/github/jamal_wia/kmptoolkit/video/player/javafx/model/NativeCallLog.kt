package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/** One command or property write the scripted player received, as the native call log names it. */
internal enum class NativeCall {
    PLAY, PAUSE, SEEK, SET_RATE, SET_VOLUME, SET_CYCLE_COUNT, SET_EVENTS, CLEAR_END_OF_MEDIA, DETACH_VIEW, DISPOSE,
    START_PULSE, STOP_PULSE, SNAPSHOT,
}

/**
 * One line of the [NativeCallLog]: the call, its argument and the native state at the moment it arrived.
 *
 * @param tMs virtual milliseconds since the model's host started.
 * @param arg the seek target, the written rate, volume or cycle count; null for a call without one.
 * @param status the player's status when the call arrived, before the call had any effect.
 * @param positionMs the playhead when the call arrived.
 * @param effective whether the call changed anything native: false for a write of the value the property
 *   already holds (B1) and for a call on a disposed player.
 */
internal data class NativeCallEntry(
    val tMs: Double,
    val call: NativeCall,
    val arg: Double?,
    val status: NativeStatus,
    val positionMs: Double,
    val effective: Boolean,
) {
    override fun toString(): String =
        "t=${fmt(tMs)} $call${if (arg != null) "(${fmt(arg)})" else ""} status=$status pos=${fmt(positionMs)}" +
            if (effective) "" else " [no effect]"

    private fun fmt(v: Double): String = if (v == Math.rint(v) && v.isFinite()) v.toLong().toString() else v.toString()
}

/**
 * Everything the native player was asked to do, in order, with virtual timestamps: the model's replacement for
 * the reflective rate recorder of the real-media tests. A test asserts on it exactly, for example
 * `assertEquals(listOf(1.0, 3.0), log.rates())`.
 *
 * Reads of properties are not logged; only commands and writes are, because only those reach the native side.
 * Single-threaded: the scripted player writes it on the model's FX thread, and a test reads it on the driving
 * thread, which is the same thread.
 *
 * @param nowMs the virtual time in milliseconds, read when an entry is recorded.
 */
internal class NativeCallLog(private val nowMs: () -> Double) {

    private val recorded: MutableList<NativeCallEntry> = mutableListOf()

    /** A snapshot of the entries so far, oldest first. */
    val entries: List<NativeCallEntry> get() = recorded.toList()

    /** How many entries there are. */
    val size: Int get() = recorded.size

    /** Appends an entry stamped with the current virtual time. */
    fun record(call: NativeCall, arg: Double?, status: NativeStatus, positionMs: Double, effective: Boolean) {
        recorded += NativeCallEntry(nowMs(), call, arg, status, positionMs, effective)
    }

    /** The entries of one kind, oldest first, effective or not. */
    fun calls(call: NativeCall): List<NativeCallEntry> = recorded.filter { it.call == call }

    /**
     * The rates that reached the native player, oldest first: the effective `SET_RATE` writes, so a write of the
     * value the property already held (no change, B1) is not in it.
     */
    fun rates(): List<Double> = recorded.filter { it.call == NativeCall.SET_RATE && it.effective }.map { checkNotNull(it.arg) }

    /** The last [count] entries as text, oldest first, with the number of earlier ones: for a failure message. */
    fun tail(count: Int = DEFAULT_TAIL): String {
        require(count > 0) { "count must be positive, was $count" }
        val shown: List<NativeCallEntry> = recorded.takeLast(count)
        val omitted: Int = recorded.size - shown.size
        return buildString {
            append("native call log (").append(recorded.size).append(" entries")
            if (omitted > 0) append(", ").append(omitted).append(" earlier omitted")
            append("):")
            for (entry in shown) append("\n  ").append(entry)
        }
    }

    override fun toString(): String = tail()

    companion object {
        /** The number of entries a failure message of the monitor shows (design 4.3). */
        const val DEFAULT_TAIL: Int = 20
    }
}
