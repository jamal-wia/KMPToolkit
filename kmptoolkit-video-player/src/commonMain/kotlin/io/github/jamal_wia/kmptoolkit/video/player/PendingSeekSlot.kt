package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
@OptIn(ExperimentalAtomicApi::class)
internal class PendingSeekSlot {

    /** One seek's identity. Two tokens are never equal, whatever their targets. */
    internal class Token internal constructor(val targetMs: Long)

    private val latest: AtomicReference<Token?> = AtomicReference(null)

    /** The target of the latest seek not completed yet, or `null`. */
    val targetMs: Long?
        get() = latest.load()?.targetMs

    /** Makes [targetMs] the pending seek, replacing whatever was pending. */
    fun begin(targetMs: Long): Token = Token(targetMs).also { latest.store(it) }

    /** The seek [token] stands for is over; clears the slot unless a newer seek replaced it. */
    fun complete(token: Token) {
        latest.compareAndSet(token, null)
    }
}
