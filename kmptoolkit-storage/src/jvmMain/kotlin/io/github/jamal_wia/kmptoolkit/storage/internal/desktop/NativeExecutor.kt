package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 *   its caller is cancelled) is withdrawn from the queue and never runs, so a timed-out write is never applied later. The
 *   withdrawal happens at the instant of the cancellation or of the limit, in the thread that cancels, not when the caller's
 *   dispatcher gets round to resuming the caller (a busy dispatcher would otherwise leave the call queued, and the native
 *   thread, which has just become free, would run it). The cancellation handler is registered before the call is queued, so
 *   there is no moment at which a call is queued and its caller's cancellation is not watched. A call that
 *   is running cannot be stopped: its result is delivered to nobody and handed to `discard` (to wipe a key). A caller that is
 *   already cancelled when it calls gets its `CancellationException` at once and queues nothing, so no call ever starts for a
 *   caller that was cancelled before it asked.
 * - **A value is never dropped.** A value a call returned that does not reach its caller as [NativeOutcome.Completed] reaches
 *   `discard` exactly once, whatever the reason: the caller left or timed out first, the caller is cancelled in the instant
 *   the value lands, or the delivery itself fails (an `Error` while the executor allocates the outcome, the thread dying).
 *   It is handed over before that failure leaves the delivery, so even a thread that dies leaves no unwiped key behind.
 * - **Interactive calls** pass `limit = null`: a prompt may be on screen, so there is no limit, but the caller stays
 *   cancellable.
 * - **No lock is held across a call.** The executor owns no lock a binding call can wait on; callers never hold a
 *   store lock while they await a call that can prompt the user.
 * - **The thread outlives what a call does to it.** The interrupt status a call leaves behind is cleared after every
 *   call and an interrupt that is not [close] never ends the loop. The thread is made before the call is queued: a
 *   thread that cannot be made (a thread limit) answers that one call [NativeOutcome.Threw], the call is never queued
 *   (so no later caller's thread can run it), and the next call tries again. A native thread that has died is replaced
 *   by the next call too, and a thread that dies while calls are queued (only an `Error` such as an `OutOfMemoryError` can
 *   end the loop) hands them to its replacement before it ends, so they are neither refused as "closed" nor left without a
 *   thread; if no replacement can be made they answer [NativeOutcome.Threw] with that cause.
 * - **A call never throws for the caller's own interrupt.** The queue is unbounded and the call is added with `offer`,
 *   which cannot be interrupted, so a caller whose thread is already interrupted still gets an outcome and keeps its
 *   interrupt status.
 * - **Release is terminal.** [close] withdraws every queued call (each answers [NativeOutcome.Threw]), interrupts the
 *   thread and refuses new calls. A call that the thread had already taken from the queue when [close] ran may still run
 *   and answer its real result. After [close] no thread is made or started any more, not even for a caller that passed the
 *   `closed` check before [close] ran (at most the one thread whose start [close] overtook, which ends at once). A new
 *   executor is the way to start again.
 *
 * The limit is enforced with `withTimeoutOrNull`, so a test controls it with virtual time. The shared process
 * instance is [NativeExecutor.shared].
 */
internal class NativeExecutor(
    private val threadFactory: ThreadFactory = daemonThreadFactory("kmptoolkit-storage-native"),
    /**
     * Test seam: runs where the executor allocates after a call returned, i.e. where an `OutOfMemoryError` could come from
     * (the only way an `Error` can leave the loop). Production does nothing. It is the one way a test can make the native
     * thread die with calls queued.
     */
    private val beforeDelivery: () -> Unit = {},
) : AutoCloseable {

    /** What [Task.abandon] found when a caller stopped waiting. */
    private enum class Abandoned {
        /** The task had not started: it will never run and must leave the queue. */
        WITHDRAWN,

        /** The task is running: its late result goes to `discard` on the native thread. */
        RUNNING,

        /** The task finished first: [Task.result] is readable at once. */
        DELIVERED,

        /** [close] refused the task. */
        REFUSED,
    }

    private class Task<T>(val block: () -> T, val discard: (T) -> Unit, val beforeDelivery: () -> Unit) {
        val state: AtomicInteger = AtomicInteger(QUEUED)
        val done: CompletableDeferred<NativeOutcome<T>> = CompletableDeferred()

        /** Written before the state becomes FINISHED, so a reader that sees FINISHED can read it without waiting. */
        @Volatile var result: NativeOutcome<T>? = null

        fun execute() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return
            val value: T = try {
                block()
            } catch (error: Throwable) {
                settle(classify(error))
                return
            }
            publish(value)
        }

        /**
         * Delivers the [value] the call returned: to the caller as `Completed`, or to `discard` when the caller already left.
         * Whatever happens on the way (an `Error` while allocating the outcome or at the delivery seam, a caller that
         * left first, the thread dying) a value that is not handed over as `Completed` reaches `discard` exactly once and
         * before the failure leaves this function, so a key the call returned is never dropped unwiped. The caller of a failed
         * delivery is answered `Threw` by [settleAfterEscape]; an abandoned caller needs no answer.
         */
        private fun publish(value: T) {
            var handedOver = false
            try {
                beforeDelivery()
                val outcome: NativeOutcome.Completed<T> = NativeOutcome.Completed(value)
                result = outcome
                if (state.compareAndSet(RUNNING, FINISHED)) {
                    // From here the caller owns the value: it reads `result` on a limit or a cancellation (see `call`).
                    handedOver = true
                    done.complete(outcome)
                }
            } finally {
                if (!handedOver) NativeExecutor.discardQuietly(discard, value)
            }
        }

        /** Delivers a [outcome] that carries no value to wipe to the caller; an abandoned caller needs no answer. */
        private fun settle(outcome: NativeOutcome<Nothing>) {
            beforeDelivery()
            result = outcome
            if (state.compareAndSet(RUNNING, FINISHED)) done.complete(outcome)
        }

        /** The native thread met an error outside [execute]'s own handling: never leave a caller waiting for it. */
        fun settleAfterEscape(error: Throwable) {
            if (state.get() == RUNNING) settle(NativeOutcome.Threw(error))
        }

        /**
         * The caller stopped waiting. A task that the cancellation handler already took out of play ([withdraw]) reads as
         * withdrawn: answering [Abandoned.REFUSED] for it would send a timed-out caller to wait for an answer that nothing will give.
         */
        fun abandon(): Abandoned {
            if (state.compareAndSet(QUEUED, ABANDONED)) return Abandoned.WITHDRAWN
            if (state.compareAndSet(RUNNING, ABANDONED_RUNNING)) return Abandoned.RUNNING
            return when (state.get()) {
                ABANDONED -> Abandoned.WITHDRAWN
                ABANDONED_RUNNING -> Abandoned.RUNNING
                FINISHED -> Abandoned.DELIVERED
                else -> Abandoned.REFUSED
            }
        }

        /** Takes the task out of play if it has not started; `false` when it runs, finished or was refused (it then answers by itself). */
        fun withdraw(): Boolean = state.compareAndSet(QUEUED, ABANDONED)

        fun refuse(reason: Throwable) {
            if (state.compareAndSet(QUEUED, REFUSED)) done.complete(NativeOutcome.Threw(reason))
        }
    }

    private val queue: LinkedBlockingQueue<Task<*>> = LinkedBlockingQueue()
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /** Guards the creation of [thread] only (short, never held across a suspension or a native call). */
    private val startLock: Any = Any()

    @Volatile private var thread: Thread? = null

    /**
     * Runs [block] on the native thread and waits for it. [limit] `null` waits without a limit (cancellable);
     * a limit must be positive. [discard] receives a result nobody will read (see "A value is never dropped" above).
     */
    suspend fun <T> call(limit: Duration?, discard: (T) -> Unit = {}, block: () -> T): NativeOutcome<T> {
        require(limit == null || limit > Duration.ZERO) { "limit must be positive" }
        // A caller that is already cancelled never queues anything: without this the native thread, woken by the offer below, can
        // win the QUEUED-to-RUNNING race against the caller's own `abandon()` and run a call for a caller that is then told it was
        // cancelled (about one attempt in six on an idle executor). A cancellation that lands later is watched from before the
        // task is queued (see `withdrawOnCancelling`): the call has not started and is withdrawn at that instant, or it has started.
        currentCoroutineContext().ensureActive()
        if (closed.get()) return NativeOutcome.Threw(IllegalStateException("The native executor is closed"))
        // The thread first, the queue second: a call that could not get a thread is never queued, so no other caller that
        // makes the thread a moment later can run it after its own caller was told it failed.
        try {
            ensureStarted()
        } catch (error: Throwable) {
            // The thread could not be made (a container's thread limit, an OutOfMemoryError): this call never runs, and
            // the next call tries again.
            return NativeOutcome.Threw(error)
        }
        val task: Task<T> = Task(block, discard, beforeDelivery)
        // Watched BEFORE the task is queued, so there is no moment at which the task is visible to the native thread and the
        // cancellation of its caller is not watched.
        val callerWatch: DisposableHandle? = withdrawOnCancelling(currentCoroutineContext()[Job], task)
        try {
            enqueue(task)?.let { answered: NativeOutcome<T> -> return answered }
            val outcome: NativeOutcome<T>? =
                if (limit == null) {
                    task.done.await()
                } else {
                    withTimeoutOrNull(limit) {
                        // The limit cancels this scope, not the caller's job: it is watched too (a scope that is already cancelled runs
                        // the handler at once). A limit that passes between the offer and this line is thus seen as soon as it can be.
                        val limitWatch: DisposableHandle? = withdrawOnCancelling(currentCoroutineContext()[Job], task)
                        try {
                            task.done.await()
                        } finally {
                            limitWatch?.dispose()
                        }
                    }
                }
            if (outcome != null) return outcome
            // The limit passed. A result that landed in the same instant is still a result.
            return when (task.abandon()) {
                Abandoned.DELIVERED -> checkNotNull(task.result)
                Abandoned.REFUSED -> task.done.await()
                Abandoned.WITHDRAWN -> {
                    queue.remove(task)
                    NativeOutcome.TimedOut
                }
                Abandoned.RUNNING -> NativeOutcome.TimedOut
            }
        } catch (escaped: Throwable) {
            // A cancellation, or anything else that leaves this call while its task is still in play: the task must not be left
            // behind to deliver a value to nobody.
            when (task.abandon()) {
                // Never awaited here: this coroutine is cancelled, so an await would throw at once and skip the wipe.
                // FINISHED is set only after `result` is written, so reading it needs no waiting.
                Abandoned.DELIVERED -> (task.result as? NativeOutcome.Completed<T>)?.let { NativeExecutor.discardQuietly(discard, it.value) }
                Abandoned.WITHDRAWN -> queue.remove(task)
                Abandoned.RUNNING, Abandoned.REFUSED -> Unit
            }
            throw escaped
        } finally {
            callerWatch?.dispose()
        }
    }

    /**
     * Takes [task] out of the queue the moment the job that waits for it is cancelling: the caller's own cancellation, or the
     * limit of the enclosing `withTimeoutOrNull` (its scope is cancelled when the limit passes). `invokeOnCompletion` with
     * `onCancelling` runs synchronously in the thread that cancels, before the resumption of the caller is dispatched, so a busy
     * caller dispatcher cannot keep a cancelled caller's task queued for the native thread, which has just become free, to run.
     * `invokeImmediately` makes a job that is already cancelling run the handler at once, so there is no moment between the
     * registration and the cancellation in which the cancellation is missed. The handler only does a compare-and-set and a
     * `remove` on the queue: short, never throws, takes no store lock and not `startLock`. The caller disposes the handle on every way
     * out, so nothing stays registered on a long-lived job.
     */
    @OptIn(InternalCoroutinesApi::class)
    private fun <T> withdrawOnCancelling(job: Job?, task: Task<T>): DisposableHandle? =
        job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { _: Throwable? ->
            if (task.withdraw()) queue.remove(task)
        }

    /**
     * Offers [task] and makes sure a live native thread will see it. A caller cancelled before the offer has had the task withdrawn
     * by its handler already (a withdrawn task is never run, whoever takes it from the queue); its own wait then throws at once
     * and `call` removes the task again, so nothing stays queued behind a hung call. Answers the call at once (`Threw`) when no
     * thread could be made for it and it was still queued; `null` otherwise.
     */
    private fun <T> enqueue(task: Task<T>): NativeOutcome<T>? {
        // `offer`, not `put`: on an unbounded queue it never blocks, and unlike `put` it does not take the queue lock
        // interruptibly, so a caller whose thread is already interrupted neither gets an InterruptedException out of an
        // API that answers outcomes nor loses its interrupt status.
        queue.offer(task)
        if (closed.get()) {
            refuseQueued()
        } else if (thread?.isAlive != true) {
            // The thread that ensureStarted saw ended after that (its loop died on an Error) and its last look at the queue was
            // before the offer above: it cannot be counted on to run this task. The thread clears `thread` before that look, so
            // either it saw the task and made a replacement, or this read sees no live thread and makes one here.
            try {
                ensureStarted()
            } catch (error: Throwable) {
                if (task.withdraw()) {
                    queue.remove(task)
                    return NativeOutcome.Threw(error)
                }
            }
        }
        return null
    }

    /** How many calls are queued or running; `0` when idle. Used by tests as the "nothing left behind" check. */
    val pendingCalls: Int get() = queue.size + if (running.get()) 1 else 0

    private val running: AtomicBoolean = AtomicBoolean(false)

    /** Whether [close] has run. */
    val isClosed: Boolean get() = closed.get()

    /** Makes the native thread if there is none. A failed attempt leaves nothing behind, so the next call tries again. */
    private fun ensureStarted() {
        // A closed executor makes no thread: its callers are refused by the `closed` check that follows their offer. Without
        // this, a thread that ended because of close() reads as "died" and every caller already in flight would replace it.
        if (closed.get() || thread?.isAlive == true) return
        synchronized(startLock) {
            if (closed.get() || thread?.isAlive == true) return
            val created: Thread = threadFactory.newThread(::runLoop)
            created.start()
            thread = created
            // close() may have run between the check in call() and the start: make the thread stop.
            if (closed.get()) created.interrupt()
        }
    }

    private fun runLoop() {
        var current: Task<*>? = null
        try {
            while (!closed.get()) {
                val task: Task<*> = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    // close() sets `closed` before it interrupts; any other interrupt (a binding call that restored its
                    // thread's status) must not end the loop for good: nothing would ever make the thread again.
                    continue
                }
                current = task
                running.set(true)
                try {
                    task.execute()
                } catch (error: Throwable) {
                    // The loop must outlive anything a task throws (an Error from a wipe handler, an allocation failure
                    // while building the outcome): with the thread gone no thread is ever made again.
                    task.settleAfterEscape(error)
                } finally {
                    running.set(false)
                    // The next binding call must not start with its predecessor's interrupt status (an interruptible JDK
                    // call would fail at once); close() is seen through `closed`, not through the status.
                    Thread.interrupted()
                }
                current = null
            }
        } catch (death: Throwable) {
            // Even the handling above failed (an OutOfMemoryError): the call this thread was running must not wait forever.
            // Once more, now that the stack has unwound; if that fails too the caller's own limit or cancellation ends it.
            current?.let { task: Task<*> ->
                try {
                    task.settleAfterEscape(death)
                } catch (_: Throwable) {
                    // Nothing more can be done for it.
                }
            }
            throw death
        } finally {
            retire()
        }
    }

    /**
     * The end of the loop, for any reason. Closed: every queued call is refused. Otherwise the thread died with the executor
     * open: it forgets itself first, then looks at the queue, and hands what is queued to a replacement (see `call`, which
     * looks at `thread` after its offer: between the two, either side sees the other). If no replacement can be made the queued
     * calls answer [NativeOutcome.Threw] with that cause instead of waiting for a thread that nothing will make.
     */
    private fun retire() {
        if (closed.get()) {
            refuseQueued()
            return
        }
        synchronized(startLock) {
            if (thread === Thread.currentThread()) thread = null
        }
        if (queue.isEmpty()) return
        try {
            ensureStarted()
        } catch (error: Throwable) {
            refuseQueued(error)
        }
    }

    private fun refuseQueued(reason: Throwable = IllegalStateException("The native executor is closed")) {
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
        private const val REFUSED: Int = 5

        /** The limit of a call that cannot show a dialog (plan 4.11). */
        val NON_INTERACTIVE_LIMIT: Duration = 10.seconds

        /** The executor of the process: one daemon thread, started by the first call. */
        val shared: NativeExecutor by lazy { NativeExecutor() }

        fun daemonThreadFactory(name: String): ThreadFactory = ThreadFactory { runnable: Runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

        /** A discard handler runs on the native thread or on a cancelled caller: whatever it throws must not end either. */
        fun <T> discardQuietly(discard: (T) -> Unit, value: T) {
            try {
                discard(value)
            } catch (_: Throwable) {
                // Nothing to report to: the value was abandoned, and a failed wipe handler (even one that throws an
                // Error) must neither kill the native thread nor replace a caller's CancellationException.
            }
        }

        /** Whether [error] means the binding cannot run in this environment rather than that a call failed. */
        fun isNativeUnavailable(error: Throwable): Boolean =
            error is LinkageError || error is IllegalCallerException || error is SecurityException

        fun classify(error: Throwable): NativeOutcome<Nothing> =
            if (isNativeUnavailable(error)) NativeOutcome.NativeUnavailable(error) else NativeOutcome.Threw(error)
    }
}
