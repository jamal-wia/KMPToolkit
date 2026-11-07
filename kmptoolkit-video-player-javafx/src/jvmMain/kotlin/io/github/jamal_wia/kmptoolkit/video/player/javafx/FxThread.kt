package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** The JavaFX application thread: starting the toolkit, and getting work onto that thread. */
internal object FxThread {

    private const val START_TIMEOUT_SECONDS: Long = 10L
    private const val TOOLKIT_EXITED_MESSAGE: String = "Platform.exit has been called"
    private const val EXIT_SILENCE_MS: Long = 2_000L
    private const val NANOS_PER_MILLI: Long = 1_000_000L

    /**
     * How many blocks [post] and [call] have queued with `Platform.runLater` that have not started yet,
     * so a call on the FX thread knows whether running inline would overtake an earlier one. An
     * atomic rather than a coroutine tool: [post] serves the engine's plain synchronous transport
     * calls, and the count must be right on whichever thread makes one.
     */
    private val queued: AtomicInteger = AtomicInteger(0)

    /**
     * The JavaFX application thread, as soon as this object has seen it run something. A cheap,
     * definitive exit signal on every platform where the thread ends with the toolkit; on macOS it
     * does not (the thread outlives an implicit exit), which is why [hasExited] also has [probe]. A
     * state flow rather than a volatile (D-21).
     */
    private val toolkitThread: MutableStateFlow<Thread?> = MutableStateFlow(null)

    private fun noteToolkitThread() {
        toolkitThread.value = Thread.currentThread()
    }

    /**
     * A heartbeat block handed to the FX thread. The toolkit ends in three ways: `Platform.exit()`
     * (OpenJFX says so on a later `startup`), implicit exit when the host's last window closes, and
     * the thread dying; after the last two OpenJFX gives no signal at all, and `runLater` silently
     * drops what it is given. A block that has stayed unanswered for [EXIT_SILENCE_MS] therefore
     * counts as an exited toolkit, until it does run (a busy thread that recovers is alive again).
     * At most one probe is outstanding, so a dead toolkit costs one block, never a backlog.
     */
    private class Probe {
        val issuedAtNanos: Long = System.nanoTime()
        // A latch: [hasExited] is plain synchronous code and must be able to wait for it (D-21).
        val answered: CountDownLatch = CountDownLatch(1)

        fun silentMs(): Long = (System.nanoTime() - issuedAtNanos) / NANOS_PER_MILLI
    }

    private val probe: MutableStateFlow<Probe?> = MutableStateFlow(null)

    /** The outstanding probe, or a freshly issued one when the last was answered. */
    private fun currentProbe(): Probe {
        while (true) {
            val current: Probe? = probe.value
            if (current != null && current.answered.count != 0L) return current
            val fresh = Probe()
            if (probe.compareAndSet(current, fresh)) {
                try {
                    runLater(
                        Runnable {
                            noteToolkitThread()
                            fresh.answered.countDown()
                        },
                    )
                } catch (e: IllegalStateException) {
                    // The toolkit was never initialized: nothing to probe, nothing exited.
                    fresh.answered.countDown()
                }
                return fresh
            }
        }
    }

    /**
     * How a block is handed to the FX thread; `Platform.runLater` unless a test swaps it, to
     * simulate a toolkit that has exited (which silently drops what it is given).
     */
    // Volatile: a test swaps it on its own thread while engines read it on theirs.
    @Volatile
    internal var runLater: (Runnable) -> Unit = { Platform.runLater(it) }

    /** `Platform.startup`; a seam so a test can simulate a toolkit that has exited. */
    // Volatile: a test swaps it on its own thread while engines read it on theirs.
    @Volatile
    internal var startup: (Runnable) -> Unit = { Platform.startup(it) }

    /**
     * Starts the toolkit, or confirms an already running one accepts work.
     *
     * Implicit exit is switched off only when this call is the one that started the toolkit: with no
     * window of its own the engine would otherwise never trigger it anyway, but an app that runs its
     * own JavaFX windows keeps whatever exit policy it chose.
     *
     * Called on the FX thread itself, an already running toolkit is confirmed by being there: waiting
     * for a block queued behind the caller would block the only thread that can run it. Throws
     * [FxThreadUnresponsiveException] when the toolkit is running but the FX thread did not answer
     * in time — a busy thread, not a missing runtime.
     */
    fun startToolkit() {
        // A latch, not a CompletableDeferred: this is reached from the non-suspending isAvailable() and
        // must block its caller (never the FX thread, see the early return) (D-21).
        val started = CountDownLatch(1)
        val startedHere: Boolean = try {
            startup(Runnable {
                noteToolkitThread()
                started.countDown()
            })
            true
        } catch (e: IllegalStateException) {
            // "Toolkit already initialized" — the app, or an earlier player, started it.
            // After Platform.exit startup throws this too, and runLater would drop the block: wait for
            // nothing, and let the runtime remember it as the permanent failure it is. The message is
            // the only signal OpenJFX gives.
            if (e.message == TOOLKIT_EXITED_MESSAGE) throw e
            // On the FX thread the toolkit is running and accepts work: nothing left to confirm.
            if (Platform.isFxApplicationThread()) {
                noteToolkitThread()
                return
            }
            Platform.runLater {
                noteToolkitThread()
                started.countDown()
            }
            false
        }
        if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw FxThreadUnresponsiveException(
                "The JavaFX application thread did not respond within $START_TIMEOUT_SECONDS s",
            )
        }
        if (startedHere) Platform.setImplicitExit(false)
    }

    /**
     * Whether the toolkit has exited, however it ended. Only for a toolkit already seen running:
     * `Platform.startup` then throws at once without side effects, with the exit message after
     * `Platform.exit()`. An implicit exit (the last window closed) and a dead toolkit give no
     * message: a dead [toolkitThread] answers at once, and otherwise a [Probe] that the FX thread
     * leaves unanswered for [EXIT_SILENCE_MS]. With [wait], waits up to that long for the answer, so
     * a live idle toolkit answers in well under a millisecond and a stalled or exited one in at most
     * [EXIT_SILENCE_MS], once: the next call finds the same probe already old. Without it, never
     * waits. Never waits on the FX thread itself, which is alive by being there. Called before the
     * toolkit was ever initialised it would start it.
     */
    fun hasExited(wait: Boolean = true): Boolean {
        try {
            startup(Runnable { })
        } catch (e: IllegalStateException) {
            if (e.message == TOOLKIT_EXITED_MESSAGE) return true
        }
        val thread: Thread? = toolkitThread.value
        if (thread != null && !thread.isAlive) return true
        if (isCurrent()) return false
        val outstanding: Probe = currentProbe()
        if (wait) {
            val remainingMs: Long = EXIT_SILENCE_MS - outstanding.silentMs()
            if (remainingMs > 0L) outstanding.answered.await(remainingMs, TimeUnit.MILLISECONDS)
        }
        return outstanding.answered.count != 0L && outstanding.silentMs() >= EXIT_SILENCE_MS
    }

    /** Whether the caller is the FX application thread; false where JavaFX cannot even load. */
    fun isCurrent(): Boolean = try {
        Platform.isFxApplicationThread().also { if (it) noteToolkitThread() }
    } catch (e: LinkageError) {
        false
    }

    /**
     * Runs [block] on the FX thread, after every block queued before it. Inline when already there
     * and nothing is queued; queued behind the rest otherwise — run inline then, a `pause()` made on
     * the FX thread (an engine callback reacting to an event, say) would overtake a `start()` queued
     * from another thread a moment earlier, and the source would play on while the player above
     * reports Paused. After the toolkit has exited (the app called `Platform.exit()`) `runLater`
     * silently drops the block rather than throwing, so it never runs: the engine's transport calls
     * tolerate the wrong state, and nothing is playing any more either.
     */
    fun post(block: () -> Unit) {
        if (Platform.isFxApplicationThread() && queued.get() == 0) {
            block()
            return
        }
        try {
            runLaterCounted(block)
        } catch (e: IllegalStateException) {
            // Only when the toolkit was never initialized; after an exit runLater drops silently.
        }
    }

    /**
     * Runs [block] on the FX thread and suspends for its result or exception. Never resumes if the
     * toolkit has exited, as `runLater` then drops the block: a caller bounds the wait itself.
     */
    suspend fun <T> call(block: () -> T): T {
        val result = CompletableDeferred<T>()
        val task: () -> Unit = {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        if (Platform.isFxApplicationThread() && queued.get() == 0) task() else runLaterCounted(task)
        return result.await()
    }

    /**
     * `Platform.runLater`, counted in [queued] until [block] is taken off the queue — uncounted
     * before it runs, since a call made from inside it comes after it anyway and only what is still
     * queued may hold that call back. Throws as `runLater` does.
     */
    private fun runLaterCounted(block: () -> Unit) {
        queued.incrementAndGet()
        try {
            runLater(
                Runnable {
                    queued.decrementAndGet()
                    block()
                },
            )
        } catch (e: IllegalStateException) {
            // Never queued, so never counted.
            queued.decrementAndGet()
            throw e
        }
    }
}

/**
 * The JavaFX toolkit is running but its application thread did not answer in time, as when it is
 * busy. Unlike a missing runtime this may pass, so [SystemJavaFxRuntime] does not remember it.
 */
internal class FxThreadUnresponsiveException(message: String) : IllegalStateException(message)
