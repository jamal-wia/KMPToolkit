package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A plain reentrant mutual-exclusion lock — the JVM's `ReentrantLock`, Foundation's `NSRecursiveLock`. */
internal interface ReentrantLockHandle {
    fun lock()
    fun unlock()
    fun tryLock(): Boolean
}

internal expect fun newReentrantLock(): ReentrantLockHandle

/**
 * Serializes every transition of [EngineVideoPlayer]'s state machine, whichever thread it arrives on.
 *
 * Transitions come from three kinds of threads at once: the caller's (transport and settings, normally
 * the main thread), the polling coroutine's (a background dispatcher by default), and whatever thread
 * the engine reports events on (the main looper for Media3, a native event thread for a desktop
 * engine). Each transition reads the state, calls the engine and writes the state; two of them
 * interleaving is how a poll could overwrite `Completed` or `Paused` with a stale `Playing`.
 *
 * Two entry points, because the two kinds of caller need different things:
 *
 * - [exclusive] is for the player's own API. It **blocks** until the lock is free, so when a call
 *   returns its transition has happened — `pause()` returns with the state already `Paused`.
 * - [submit] is for engine callbacks and poll ticks. It **never blocks**: it runs the action at once
 *   when the lock is free, and otherwise queues it for whichever thread holds the lock, which runs it
 *   before letting go. An engine usually reports events while holding a lock of its own, and a
 *   transition holds this lock while calling into the engine; if a callback could wait here, those
 *   two locks taken in opposite orders would deadlock. Queueing instead cannot.
 *
 * An action submitted from *inside* a transition on the same thread — an engine that reports an event
 * synchronously from the very call the transition is making — is queued too, and runs right after
 * that transition finishes, before the outermost call returns. The event therefore lands on the state
 * the transition wrote, rather than being overwritten by the rest of it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class StateMachineLock {

    private val lock: ReentrantLockHandle = newReentrantLock()

    /** How deep the holding thread is in [exclusive] sections or queued actions. Guarded by [lock]. */
    private var depth: Int = 0

    /** Actions [submit] could not run at once, oldest first. */
    private val queued: AtomicReference<List<() -> Unit>> = AtomicReference(emptyList())

    /** Runs [block] holding the lock, waiting for it if necessary, then runs whatever was queued meanwhile. */
    fun <T> exclusive(block: () -> T): T {
        lock.lock()
        try {
            depth++
            try {
                return block()
            } finally {
                depth--
                if (depth == 0) runQueued()
            }
        } finally {
            lock.unlock()
            drainIfFree()
        }
    }

    /** Runs [action] now if the lock is free, else hands it to the holder. Never blocks. */
    fun submit(action: () -> Unit) {
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
