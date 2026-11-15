package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** How one native call ended, as seen by the coroutine that made it. */
internal sealed interface NativeOutcome<out T> {

    /** The call returned [value]. */
    class Completed<out T>(val value: T) : NativeOutcome<T> {
        override fun toString(): String = "Completed"
    }

    /** The time limit passed first. The call may still be running; its late result is discarded. */
    data object TimedOut : NativeOutcome<Nothing>

    /** The binding cannot run here: a [LinkageError], an [IllegalCallerException] or a [SecurityException] ([cause]). */
    class NativeUnavailable(val cause: Throwable) : NativeOutcome<Nothing> {
        override fun toString(): String = "NativeUnavailable(${cause::class.java.simpleName})"
    }

    /** The binding threw something else ([cause]), or the executor was closed before the call ran. */
    class Threw(val cause: Throwable) : NativeOutcome<Nothing> {
        override fun toString(): String = "Threw(${cause::class.java.simpleName})"
    }
}

/**
 * Runs the blocking native calls of one process on one dedicated daemon thread, with an optional time limit.
 *
 * - **One thread.** The thread starts on the first call, never earlier (a plain-store app never starts it). A hung
 *   operating-system call keeps it busy, and later calls queue behind it and time out ([NativeOutcome.TimedOut]) rather
 *   than start more threads.
 * - **A limit is a limit for the caller, not for the call.** A call that has not started when its limit passes (or
 *   its caller is cancelled) is withdrawn and never runs, so a timed-out write is never applied later. A call that
 *   is running cannot be stopped: its result is delivered to nobody and handed to `discard` (to wipe a key).
 * - **Interactive calls** pass `limit = null`: a prompt may be on screen, so there is no limit, but the caller stays
 *   cancellable.
 * - **No lock is held across a call.** The executor owns no lock a binding call can wait on; callers never hold a
 *   store lock while they await a call that can prompt the user.
 * - **Release is terminal.** [close] withdraws every queued call (each answers [NativeOutcome.Threw]), interrupts the
 *   thread and refuses new calls. A new executor is the way to start again.
 *
 * The limit is enforced with `withTimeoutOrNull`, so a test controls it with virtual time. The shared process
 * instance is [NativeExecutor.shared].
 */
internal class NativeExecutor(
    private val threadFactory: ThreadFactory = daemonThreadFactory("kmptoolkit-storage-native"),
) : AutoCloseable {

    private class Task<T>(val block: () -> T, val discard: (T) -> Unit) {
        val state: AtomicInteger = AtomicInteger(QUEUED)
        val done: CompletableDeferred<NativeOutcome<T>> = CompletableDeferred()

        fun execute() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return
            val outcome: NativeOutcome<T> = try {
                NativeOutcome.Completed(block())
            } catch (error: Throwable) {
                classify(error)
            }
            if (state.compareAndSet(RUNNING, FINISHED)) {
                done.complete(outcome)
            } else if (outcome is NativeOutcome.Completed<T>) {
                // Abandoned while running: nobody will read the value, so let its owner wipe it.
                discard(outcome.value)
            }
        }

        /** Withdraws a queued task. Returns `true` when its result was already delivered, so the caller can use it. */
        fun abandon(): Boolean {
            if (state.compareAndSet(QUEUED, ABANDONED)) return false
            if (state.compareAndSet(RUNNING, ABANDONED_RUNNING)) return false
            return state.get() == FINISHED
        }

        fun refuse(reason: Throwable) {
            if (state.compareAndSet(QUEUED, ABANDONED)) done.complete(NativeOutcome.Threw(reason))
        }
    }

    private val queue: LinkedBlockingQueue<Task<*>> = LinkedBlockingQueue()
    private val started: AtomicBoolean = AtomicBoolean(false)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    @Volatile private var thread: Thread? = null

    /**
     * Runs [block] on the native thread and waits for it. [limit] `null` waits without a limit (cancellable);
     * a limit must be positive. [discard] receives a result nobody will read.
     */
    suspend fun <T> call(limit: Duration?, discard: (T) -> Unit = {}, block: () -> T): NativeOutcome<T> {
        require(limit == null || limit > Duration.ZERO) { "limit must be positive" }
        if (closed.get()) return NativeOutcome.Threw(IllegalStateException("The native executor is closed"))
        val task: Task<T> = Task(block, discard)
        queue.put(task)
        ensureStarted()
        if (closed.get()) refuseQueued()
        try {
            val outcome: NativeOutcome<T>? =
                if (limit == null) task.done.await() else withTimeoutOrNull(limit) { task.done.await() }
            if (outcome != null) return outcome
            // The limit passed. A result that landed in the same instant is still a result.
            return if (task.abandon()) task.done.await() else NativeOutcome.TimedOut
        } catch (cancelled: CancellationException) {
            if (task.abandon()) {
                val late: NativeOutcome<T> = task.done.await()
                if (late is NativeOutcome.Completed<T>) discard(late.value)
            }
            throw cancelled
        }
    }

    /** How many calls are queued or running; `0` when idle. Used by tests as the "nothing left behind" check. */
    val pendingCalls: Int get() = queue.size + if (running.get()) 1 else 0

    private val running: AtomicBoolean = AtomicBoolean(false)

    /** Whether [close] has run. */
    val isClosed: Boolean get() = closed.get()

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        val created: Thread = threadFactory.newThread(::runLoop)
        thread = created
        created.start()
        // close() may have run between the check in call() and the start: make the thread stop.
        if (closed.get()) created.interrupt()
    }

    private fun runLoop() {
        try {
            while (!closed.get()) {
                val task: Task<*> = queue.take()
                running.set(true)
                try {
                    task.execute()
                } finally {
                    running.set(false)
                }
            }
        } catch (_: InterruptedException) {
            // close() interrupts an idle thread.
        } finally {
            refuseQueued()
        }
    }

    private fun refuseQueued() {
        val reason = IllegalStateException("The native executor is closed")
        while (true) {
            val task: Task<*> = queue.poll() ?: return
            task.refuse(reason)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        refuseQueued()
        thread?.interrupt()
    }

    internal companion object {
        private const val QUEUED: Int = 0
        private const val RUNNING: Int = 1
        private const val ABANDONED: Int = 2
        private const val ABANDONED_RUNNING: Int = 3
        private const val FINISHED: Int = 4

        /** The limit of a call that cannot show a dialog (plan 4.11). */
        val NON_INTERACTIVE_LIMIT: Duration = 10.seconds

        /** The executor of the process: one daemon thread, started by the first call. */
        val shared: NativeExecutor by lazy { NativeExecutor() }

        fun daemonThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable: Runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

        /** Whether [error] means the binding cannot run in this environment rather than that a call failed. */
        fun isNativeUnavailable(error: Throwable): Boolean =
            error is LinkageError || error is IllegalCallerException || error is SecurityException

        fun classify(error: Throwable): NativeOutcome<Nothing> =
            if (isNativeUnavailable(error)) NativeOutcome.NativeUnavailable(error) else NativeOutcome.Threw(error)
    }
}
