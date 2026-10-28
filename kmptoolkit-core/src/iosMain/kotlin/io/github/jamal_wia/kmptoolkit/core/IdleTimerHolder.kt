package io.github.jamal_wia.kmptoolkit.core

import kotlin.concurrent.AtomicInt
import platform.Foundation.NSThread
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Reference-counted holds on `UIApplication.idleTimerDisabled`.
 *
 * The flag is one switch for the whole process. Every module in the suite that wants the screen kept
 * on (the system-bars wake lock and the video surface) goes through one instance, [SharedIdleTimer].
 * With two owners, each writing the flag on its own schedule, one owner's release turned the timer
 * back on under the other, or restored a value saved while the other held it. That left the timer
 * disabled for good.
 */
@ToolkitInternalApi
public interface IdleTimerHolder {

    /**
     * Takes a hold. The timer stays disabled while at least one hold is live. Safe from any thread;
     * the flag itself is only ever written on the main thread.
     */
    public fun acquire(): IdleTimerHold
}

/** One hold taken by [IdleTimerHolder.acquire]. */
@ToolkitInternalApi
public interface IdleTimerHold {

    /** Gives the hold back. Idempotent: a second call does nothing, so the count cannot underflow. */
    public fun release()
}

/** The process-wide holder over the real `UIApplication.idleTimerDisabled`. */
@ToolkitInternalApi
public val SharedIdleTimer: IdleTimerHolder = CountingIdleTimerHolder(
    readFlag = { UIApplication.sharedApplication.idleTimerDisabled },
    writeFlag = { disabled -> UIApplication.sharedApplication.idleTimerDisabled = disabled },
    isMainThread = { NSThread.isMainThread() },
    postToMain = { block -> dispatch_async(dispatch_get_main_queue(), block) },
)

/**
 * A holder over a flag other than the real one. Exists for the tests of the modules that share
 * [SharedIdleTimer]. A unit-test binary has no `UIApplication`, and those tests need to drive the
 * holder and the module's own code over one fake flag and one fake main queue.
 */
@ToolkitInternalApi
public fun newIdleTimerHolder(
    readFlag: () -> Boolean,
    writeFlag: (Boolean) -> Unit,
    isMainThread: () -> Boolean,
    postToMain: (() -> Unit) -> Unit,
): IdleTimerHolder = CountingIdleTimerHolder(readFlag, writeFlag, isMainThread, postToMain)

/**
 * The count is atomic and changes on the caller's thread, at the call. The flag is written on the
 * main thread from the count as it is *then*, not from the value one particular call computed. A
 * change queued from a background thread therefore cannot land over a later one made on the main
 * thread.
 *
 * The first hold saves the flag's current value, and the last release restores it. An app that
 * disabled the timer itself keeps it disabled.
 */
@OptIn(ToolkitInternalApi::class)
private class CountingIdleTimerHolder(
    private val readFlag: () -> Boolean,
    private val writeFlag: (Boolean) -> Unit,
    private val isMainThread: () -> Boolean,
    private val postToMain: (() -> Unit) -> Unit,
) : IdleTimerHolder {

    private val holds: AtomicInt = AtomicInt(0)

    // Main thread only: whether this holder currently has the flag disabled, and the value it found
    // there when it did.
    private var applied: Boolean = false
    private var savedValue: Boolean = false

    override fun acquire(): IdleTimerHold {
        holds.incrementAndGet()
        applySoon()
        return Hold()
    }

    private fun applySoon() {
        if (isMainThread()) applyLatest() else postToMain { applyLatest() }
    }

    /** Main thread only. */
    private fun applyLatest() {
        val wanted: Boolean = holds.value > 0
        if (wanted == applied) return
        if (wanted) {
            savedValue = readFlag()
            writeFlag(true)
        } else {
            writeFlag(savedValue)
        }
        applied = wanted
    }

    private inner class Hold : IdleTimerHold {
        private val released: AtomicInt = AtomicInt(0)

        override fun release() {
            if (!released.compareAndSet(0, 1)) return
            holds.decrementAndGet()
            applySoon()
        }
    }
}
