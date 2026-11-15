package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext

/**
 * The interrupt rule of the desktop stores (decisions D-P12, rule 1 of [Jca]): **a store call never leaves an interrupt status on a
 * thread that it found clear.**
 *
 * The JCA boundary puts the interrupt status back after a provider was interrupted ([Jca] calls [noteRestored]), so that code
 * running on that thread right after the provider call sees the interrupt. A store call runs on a thread of the store's dispatcher
 * that the library does not own, and kotlinx.coroutines does not clear a worker's status between two tasks (its scheduler does it
 * only when a worker parks), so a status left behind would reach the next, unrelated coroutine on that worker, whose file I/O the
 * JDK would then refuse. This element ends that: it is added to the context of every dispatch of a store ([withStoreContext]) and
 * works per slice, one stretch of the call on one thread between two suspensions (a call that suspends for a native fetch and
 * resumes on another thread is covered on both threads, which clearing once at the end of the call would not):
 *
 * - a slice that started on a thread whose status was already set leaves it set: an interrupted caller (for example
 *   `Dispatchers.Unconfined` on an interrupted thread, or a call that runs inline on the caller's thread) keeps its interrupt, and
 *   the JDK's refusal of the file I/O still answers `OperationFailed(ClosedByInterruptException)`;
 * - a slice that started clear and in which the boundary put the status back leaves the thread clear: at the end of the dispatched
 *   block ([endOfBlock], still inside the slice) and at the end of every slice ([restoreThreadContext]). The end of the block is
 *   the one that counts when the caller's dispatcher needs no dispatch (`Dispatchers.Unconfined`, an immediate dispatcher): when the
 *   block completes, kotlinx.coroutines resumes that caller, and every coroutine it wakes, inline on this very thread inside the
 *   same task, before it ends the slice, so only a clear made by the block itself keeps them from meeting the status. The interrupt
 *   stays visible as the cause (`InterruptedException`) of the typed failure;
 * - a status that appeared in a slice for any other reason (another thread interrupted the caller's own thread, no provider
 *   involved) is never cleared.
 *
 * Nesting is handled: a store call made inside another one starts its own slice and the enclosing slice's state (entry flag and
 * pending note) is saved and put back, so the outer slice still clears at its own end. Stateless apart from one thread-local that
 * every slice saves and puts back; cancellation needs nothing, because a cancelled call still ends its slices through the same
 * path (the block's `finally` runs [endOfBlock]). A store block must not start a child coroutine of its own scope: a child that ran
 * the boundary and finished last would complete the call in a slice that has no [endOfBlock] (no store code does).
 */
internal object InterruptHygiene : AbstractCoroutineContextElement(Key), ThreadContextElement<Int> {
    /** The key of the element in a coroutine context. */
    object Key : CoroutineContext.Key<InterruptHygiene>

    /** The slice found the thread's status set when it started: nothing in it is ever cleared. */
    private const val ENTRY_INTERRUPTED: Int = 1

    /** The boundary put the status back on this thread during the slice and it has not been cleared yet. */
    private const val NOTE: Int = 2

    /**
     * The state of the slice running on this thread now (absent is 0; a note made outside any slice, by a direct [Jca] call, lives
     * here too and is saved and put back by the next slice, which never clears because of it). Each slice saves the enclosing
     * state and puts it back, so the entry flag and the note of an outer slice survive a nested store call together.
     */
    private val slice: ThreadLocal<Int> = ThreadLocal()

    private fun current(): Int = slice.get() ?: 0

    private fun store(state: Int) {
        if (state == 0) slice.remove() else slice.set(state)
    }

    private fun ownsRestoredStatus(state: Int): Boolean = state and ENTRY_INTERRUPTED == 0 && state and NOTE != 0

    /** Called by [Jca] right after it put the interrupt status back on the current thread. */
    fun noteRestored() {
        store(current() or NOTE)
    }

    /**
     * The end of a store block, called from the block's `finally` inside its slice, before kotlinx.coroutines can resume a caller
     * inline: clears the status when this slice found it clear and the boundary put it back, and consumes the note, so a status
     * that appears afterwards (set by code that was resumed inline) is never cleared by [restoreThreadContext].
     */
    fun endOfBlock() {
        val state: Int = current()
        if (ownsRestoredStatus(state)) Thread.interrupted()
        store(state and NOTE.inv())
    }

    override fun updateThreadContext(context: CoroutineContext): Int {
        val enclosing: Int = current()
        store(if (Thread.currentThread().isInterrupted) ENTRY_INTERRUPTED else 0)
        return enclosing
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
        if (ownsRestoredStatus(current())) Thread.interrupted()
        store(oldState)
    }
}

/**
 * [withContext] on [dispatcher] with [InterruptHygiene]: the one way the desktop stores dispatch their work, so no store call leaves
 * an interrupt status on a thread it found clear, on the worker or on a caller resumed inline. A new dispatch of a store goes
 * through this function.
 */
internal suspend fun <T> withStoreContext(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> T): T =
    withContext(dispatcher + InterruptHygiene) {
        try {
            block()
        } finally {
            InterruptHygiene.endOfBlock()
        }
    }
