package io.github.jamal_wia.kmptoolkit.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A plain reentrant mutual-exclusion lock — the JVM's `ReentrantLock`, Foundation's `NSRecursiveLock`. */
@ToolkitInternalApi
public interface ReentrantLockHandle {
    public fun lock()
    public fun unlock()
    public fun tryLock(): Boolean
}

/** A new, unlocked [ReentrantLockHandle] backed by the platform's own reentrant lock. */
@ToolkitInternalApi
public expect fun newReentrantLock(): ReentrantLockHandle

/**
 * Serializes every transition of a state machine, whichever thread it arrives on.
 *
 * A state machine that talks to a platform engine is entered from several kinds of threads at once:
 * the caller's (normally the main thread), a polling or ticking coroutine (a background dispatcher
 * by default), and whatever thread the engine reports events on (the main looper, a native event
 * thread). Each transition reads the state, calls the engine and writes the state; two of them
 * interleaving is how a stale tick could overwrite a terminal state.
 *
 * Two entry points, because the two kinds of caller need different things:
 *
 * - [exclusive] is for the owner's own API. It **blocks** until the lock is free, so when a call
 *   returns its transition has happened — a `pause()` returns with the state already paused.
 * - [submit] is for engine callbacks and ticks. It **never blocks**: it runs the action at once
 *   when the lock is free, and otherwise queues it for whichever thread holds the lock, which runs it
 *   before letting go. An engine usually reports events while holding a lock of its own, and a
 *   transition holds this lock while calling into the engine; if a callback could wait here, those
 *   two platform locks taken in opposite orders would deadlock. Queueing instead cannot.
 *
 * An action submitted from *inside* a transition on the same thread — an engine that reports an event
 * synchronously from the very call the transition is making — is queued too, and runs right after
 * that transition finishes, before the outermost call returns. The event therefore lands on the state
 * the transition wrote, rather than being overwritten by the rest of it.
 *
 * ### Exceptions
 *
 * Queued actions run in the order they were queued. When one throws, the ones behind it still run;
 * the first failure is then rethrown by whichever call drained the queue, with later failures
 * attached as suppressed exceptions. That call is whichever thread happens to hold the lock last —
 * not the one that submitted the action — so a failing action surfaces on an unrelated thread:
 * an action handed to [submit] must not throw.
 *
 * When the block of [exclusive] itself throws, that exception is the one the caller gets, even if
 * draining the queue on the way out throws too: the queue's failure is attached to it as a
 * suppressed exception instead of replacing it.
 */
@ToolkitInternalApi
@OptIn(ExperimentalAtomicApi::class)
public class StateMachineLock {

    private val lock: ReentrantLockHandle

    /** A lock backed by the platform's own reentrant lock. */
    public constructor() {
        lock = newReentrantLock()
    }

    /** For the module's own tests, which wrap the platform lock to land a race at an exact point. */
    internal constructor(lock: ReentrantLockHandle) {
        this.lock = lock
    }

    /** How deep the holding thread is in [exclusive] sections or queued actions. Guarded by [lock]. */
    private var depth: Int = 0

    /** Actions [submit] could not run at once, oldest first. */
    private val queued: AtomicReference<List<() -> Unit>> = AtomicReference(emptyList())

    /** Runs [block] holding the lock, waiting for it if necessary, then runs whatever was queued meanwhile. */
    public fun <T> exclusive(block: () -> T): T {
        var failure: Throwable? = null
        lock.lock()
        try {
            depth++
            val result: T = try {
                block()
            } catch (@Suppress("TooGenericExceptionCaught") thrown: Throwable) {
                failure = thrown
                depth--
                if (depth == 0) drainKeeping(thrown)
                throw thrown
            }
            depth--
            if (depth == 0) runQueued()
            return result
        } finally {
            lock.unlock()
            try {
                drainIfFree()
            } catch (@Suppress("TooGenericExceptionCaught") drained: Throwable) {
                // The block's own exception wins; only when there is none does a queue failure
                // become the call's.
                val original: Throwable? = failure
                if (original == null) throw drained
                if (original !== drained) original.addSuppressed(drained)
            }
        }
    }

    /** Lock held, depth 0. Drains the queue on behalf of a block that already threw [primary]. */
    private fun drainKeeping(primary: Throwable) {
        try {
            runQueued()
        } catch (@Suppress("TooGenericExceptionCaught") queued: Throwable) {
            if (queued !== primary) primary.addSuppressed(queued)
        }
    }

    /** Runs [action] now if the lock is free, else hands it to the holder. Never blocks. */
    public fun submit(action: () -> Unit) {
        while (true) {
            val current: List<() -> Unit> = queued.load()
            if (queued.compareAndSet(current, current + action)) break
        }
        drainIfFree()
    }

    /**
     * Runs the queue if nobody holds the lock. Called after every enqueue and every unlock, so an
     * action queued just as the holder let go is still picked up — by one side or the other.
     */
    private fun drainIfFree() {
        while (queued.load().isNotEmpty() && lock.tryLock()) {
            try {
                // Reentrant: the lock was free *to this thread*, which already holds it further up
                // the stack. That outer section drains on its way out.
                if (depth > 0) return
                runQueued()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Lock held, depth 0. Runs queued actions, including ones they queue, until none is left. */
    private fun runQueued() {
        var failure: Throwable? = null
        while (true) {
            val batch: List<() -> Unit> = queued.exchange(emptyList())
            if (batch.isEmpty()) break
            for (action: () -> Unit in batch) {
                depth++
                try {
                    action()
                } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
                    // Keep going: one failing action must not strand the ones queued behind it.
                    if (failure == null) failure = error else failure.addSuppressed(error)
                } finally {
                    depth--
                }
            }
        }
        failure?.let { throw it }
    }
}
