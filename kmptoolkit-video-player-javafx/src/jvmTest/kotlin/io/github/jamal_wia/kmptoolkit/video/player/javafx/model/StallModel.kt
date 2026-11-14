package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus

/**
 * B10, the stall part of the scripted player: the native side runs out of buffered media (status STALLED) and later
 * has enough again (back to PLAYING). Off by default: nothing stalls until [stallAt] is called.
 *
 * A stall happens only to a PLAYING player: when [stallAt]'s instant comes and the status is anything else, nothing
 * happens (a paused or ready player does not buffer-starve). It sets STALLED and the buffer progress to the playhead,
 * clamped to the duration (nothing is buffered ahead), and delivers the status event, and a buffer-progress event
 * when the value changed. A resume happens only to a STALLED player: it sets PLAYING, lets the buffer run
 * [bufferAheadMs] ahead of the playhead, clamped to the duration, and delivers the same events. So the buffer
 * progress is always within 0 and the duration, and it is never behind the playhead while stalled.
 *
 * While [isStalled] the time part must hold the playhead still; this part does not move it.
 *
 * A command that changes the status meanwhile wins: a `pause()` that lands while STALLED makes the player PAUSED, and
 * the resume that follows finds a player that is not STALLED and does nothing. A disposed player never stalls or
 * resumes.
 *
 * Install it in a slot of the player, alone or inside a [ModelPartGroup]. FX thread only.
 *
 * @param player the player it belongs to.
 * @param bufferAheadMs how far ahead of the playhead the buffer runs after a resume.
 */
internal class StallModel(
    private val player: ScriptedNativePlayer,
    val bufferAheadMs: Double = DEFAULT_BUFFER_AHEAD_MS,
) : ModelPart {

    init {
        require(bufferAheadMs >= 0.0 && bufferAheadMs.isFinite()) { "bufferAheadMs must be finite and not negative, was $bufferAheadMs" }
    }

    private var disposed: Boolean = false

    /** Whether the player is STALLED because of this part right now. */
    var isStalled: Boolean = false
        private set

    /** How many stalls began. */
    var stalls: Int = 0
        private set

    /** B10: [delayMs] from now the player stalls, if it is PLAYING then. */
    fun stallAt(delayMs: Double) {
        require(delayMs >= 0.0 && delayMs.isFinite()) { "delayMs must be finite and not negative, was $delayMs" }
        player.host.after(delayMs) { stall() }
    }

    /** B10: [delayMs] from now the player resumes, if it is STALLED then. */
    fun resumeAt(delayMs: Double) {
        require(delayMs >= 0.0 && delayMs.isFinite()) { "delayMs must be finite and not negative, was $delayMs" }
        player.host.after(delayMs) { resume() }
    }

    private fun stall() {
        if (disposed || player.status != NativeStatus.PLAYING) return
        isStalled = true
        stalls++
        setBuffer(player.positionMs)
        player.forceStatus(NativeStatus.STALLED)
    }

    private fun resume() {
        if (disposed || player.status != NativeStatus.STALLED) return
        isStalled = false
        setBuffer(player.positionMs + bufferAheadMs)
        player.forceStatus(NativeStatus.PLAYING)
    }

    private fun setBuffer(wantedMs: Double) {
        val bounded: Double = wantedMs.coerceIn(0.0, player.clip.durationMs)
        if (bounded == player.bufferMs) return
        player.bufferMs = bounded
        player.events?.onBufferProgress()
    }

    override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) {
        // Whatever moved the status off STALLED (a pause, a failure, a dispose) ends this part's stall.
        if (old == NativeStatus.STALLED && new != NativeStatus.STALLED) isStalled = false
    }

    override fun onDispose(player: ScriptedNativePlayer) {
        disposed = true
        isStalled = false
    }

    companion object {
        /** The default distance the buffer runs ahead of the playhead after a resume. */
        const val DEFAULT_BUFFER_AHEAD_MS: Double = 1_000.0
    }
}
