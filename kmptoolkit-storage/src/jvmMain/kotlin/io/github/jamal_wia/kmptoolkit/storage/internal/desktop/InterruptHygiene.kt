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
 * running on that thread right after the provider call sees the interrupt. A store call runs on a thread of the caller's dispatcher
 * that the library does not own, and kotlinx.coroutines does not clear a worker's status between two tasks (its scheduler does it
 * only when a worker parks), so a status left behind would reach the next, unrelated coroutine on that worker, whose file I/O the
 * JDK would then refuse. This element ends that: it is added to the context of every dispatch of a store ([withStoreContext]) and
 * works per slice, one stretch of the call on one thread between two suspensions (a call that suspends for a native fetch and
 * resumes on another thread is covered on both threads, which clearing once at the end of the call would not):
 *
 * - a slice that started on a thread whose status was already set leaves it set: an interrupted caller (for example
 *   `Dispatchers.Unconfined` on an interrupted thread, or a call that runs inline on the caller's thread) keeps its interrupt, and
 *   the JDK's refusal of the file I/O still answers `OperationFailed(ClosedByInterruptException)`;
 * - a slice that started clear and in which the boundary put the status back leaves the thread clear. The interrupt stays visible
 *   as the cause (`InterruptedException`) of the typed failure;
 * - a status that appeared in a slice for any other reason (another thread interrupted the caller's own thread, no provider
 *   involved) is never cleared.
 *
 * Nesting is handled: a store call made inside another one keeps the outer slice's pending note until the outer slice ends.
 * Stateless apart from one thread-local note that every slice saves and puts back; cancellation needs nothing, because a cancelled
 * call still ends its slices through the same path.
 */
internal object InterruptHygiene : AbstractCoroutineContextElement(Key), ThreadContextElement<Int> {
    /** The key of the element in a coroutine context. */
    object Key : CoroutineContext.Key<InterruptHygiene>

    private const val ENTRY_INTERRUPTED: Int = 1
    private const val NOTE_PENDING: Int = 2

    /** Whether the boundary put the status back on this thread during the current slice (`null` is no). */
    private val restored: ThreadLocal<Boolean> = ThreadLocal()

    /** Called by [Jca] right after it put the interrupt status back on the current thread. */
    fun noteRestored() {
        restored.set(true)
    }

    override fun updateThreadContext(context: CoroutineContext): Int {
        val pending: Boolean = restored.get() == true
        restored.remove()
        return (if (Thread.currentThread().isInterrupted) ENTRY_INTERRUPTED else 0) or (if (pending) NOTE_PENDING else 0)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
        if (oldState and ENTRY_INTERRUPTED == 0 && restored.get() == true) Thread.interrupted()
        if (oldState and NOTE_PENDING != 0) restored.set(true) else restored.remove()
    }
}

/**
 * [withContext] on [dispatcher] with [InterruptHygiene]: the one way the desktop stores dispatch their work, so no store call leaves
 * an interrupt status on a thread it found clear. A new dispatch of a store goes through this function.
 */
internal suspend fun <T> withStoreContext(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> T): T =
    withContext(dispatcher + InterruptHygiene, block)
