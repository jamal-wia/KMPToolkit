package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The target of the AVPlayer seek that has not finished yet, cleared by the completion of that very
 * seek and no other. AVFoundation runs the completion handler of a seek that a newer one interrupted
 * (with `finished = NO`) while the newer one is still in flight, so a completion that cleared "any
 * pending seek to the same position" — two quick `seekBackward` taps that both clamp to 0, `stop()`
 * then `replay()`, 10 → 20 → 10 — would drop the target of the seek still running, and the position
 * reported would fall back to `currentTime()`, the old position, until it completed.
 *
 * Every [begin] returns a fresh [Token]; [complete] clears the slot only while that token is still
 * the latest. Any thread.
 */
internal class PendingSeekSlot {

    /** One seek's identity. Two tokens are never equal, whatever their targets. */
    internal class Token internal constructor(val targetMs: Long)

    // A MutableStateFlow used as an atomic cell, nothing collects it: begin/complete run on the caller's
    // thread and in AVFoundation's seek completion block, neither of which can suspend (D-21, D-24).
    // Token has no equals, so compareAndSet compares identity.
    private val latest: MutableStateFlow<Token?> = MutableStateFlow(null)

    /** The target of the latest seek not completed yet, or `null`. */
    val targetMs: Long?
        get() = latest.value?.targetMs

    /** Makes [targetMs] the pending seek, replacing whatever was pending. */
    fun begin(targetMs: Long): Token = Token(targetMs).also { token -> latest.value = token }

    /** The seek [token] stands for is over; clears the slot unless a newer seek replaced it. */
    fun complete(token: Token) {
        latest.compareAndSet(token, null)
    }
}
