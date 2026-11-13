package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * B12, the late events of a disposed player: the two OpenJFX races that `FxPlayback.dispose` guards against
 * (commit 587be890 and the comment in `dispose()`). Off by default: a plain player says nothing after DISPOSED.
 *
 * - [lateStatus]: a status update that the native side had already queued before `dispose()` runs afterwards and
 *   overwrites DISPOSED with PLAYING or PAUSED, [lateStatusDelayMs] after the dispose, and the status event follows.
 *   Without it nothing is queued.
 * - [lateEndOfMedia]: an end of media that the native side had already queued runs [lateEndDelayMs] after the dispose.
 *   Like JavaFX it calls the handler that is installed THEN, so a handler cleared by `clearEndOfMedia` receives
 *   nothing (unless [lateEndIgnoresClearedHandler] says the queued call survives the clearing). When
 *   `cycleCount` is not 1 at that instant, the native side would loop, that is seek on a native player that is gone, and
 *   throw a NullPointerException on the FX thread: the model writes it to [violations] instead of throwing, so a test
 *   decides what a violation is worth. With one cycle it takes the plain non-looping branch, which never seeks.
 *
 * Both are scheduled by the first `dispose()` only; a second `dispose()` schedules nothing more. A host with an `after`
 * jitter adds it to the delays.
 *
 * Install it in a slot of the player, alone or inside a [ModelPartGroup]. FX thread only.
 *
 * @param player the player it belongs to.
 * @param lateStatus the status that lands after the dispose: PLAYING, PAUSED or null for none.
 * @param lateStatusDelayMs how long after the dispose the late status lands.
 * @param lateEndOfMedia whether an end of media runs after the dispose.
 * @param lateEndDelayMs how long after the dispose the late end of media runs.
 * @param lateEndIgnoresClearedHandler whether the late end of media is delivered even after `clearEndOfMedia`.
 */
internal class DisposeModel(
    private val player: ScriptedNativePlayer,
    val lateStatus: NativeStatus? = null,
    val lateStatusDelayMs: Double = DEFAULT_LATE_DELAY_MS,
    val lateEndOfMedia: Boolean = false,
    val lateEndDelayMs: Double = DEFAULT_LATE_DELAY_MS,
    val lateEndIgnoresClearedHandler: Boolean = false,
) : ModelPart {

    init {
        require(lateStatus == null || lateStatus == NativeStatus.PLAYING || lateStatus == NativeStatus.PAUSED) {
            "a late status is PLAYING or PAUSED, was $lateStatus"
        }
        require(lateStatusDelayMs >= 0.0 && lateStatusDelayMs.isFinite()) { "lateStatusDelayMs must be finite and not negative, was $lateStatusDelayMs" }
        require(lateEndDelayMs >= 0.0 && lateEndDelayMs.isFinite()) { "lateEndDelayMs must be finite and not negative, was $lateEndDelayMs" }
    }

    private val problems: MutableList<String> = mutableListOf()

    /** What the late events would have broken on a real player, in the order it happened. */
    val violations: List<String> get() = problems.toList()

    /** Whether the late status landed. */
    var lateStatusLanded: Boolean = false
        private set

    /** Whether the late end of media ran: true even when the handler was cleared and received nothing. */
    var lateEndRan: Boolean = false
        private set

    /** Whether the late end of media reached the listener. */
    var lateEndDelivered: Boolean = false
        private set

    override fun onDispose(player: ScriptedNativePlayer) {
        lateStatus?.let { status ->
            this.player.host.after(lateStatusDelayMs) {
                lateStatusLanded = true
                this.player.forceStatus(status)
            }
        }
        if (lateEndOfMedia) {
            this.player.host.after(lateEndDelayMs) { runLateEnd() }
        }
    }

    private fun runLateEnd() {
        lateEndRan = true
        if (player.cycleCount != SINGLE_CYCLE) {
            problems += "a queued end of media ran on a disposed player with cycleCount=${player.cycleCount}: " +
                "the native side would loop, seek on a player that is gone and throw on the FX thread"
        }
        lateEndDelivered = player.events != null && (player.endOfMediaHandlerInstalled || lateEndIgnoresClearedHandler)
        player.deliverEndOfMedia(ignoreClearedHandler = lateEndIgnoresClearedHandler)
    }

    companion object {
        /** The default delay of a late event after the dispose. */
        const val DEFAULT_LATE_DELAY_MS: Double = 20.0

        private const val SINGLE_CYCLE: Int = 1
    }
}
