package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** The JavaFX application thread: starting the toolkit, and getting work onto that thread. */
internal object FxThread {

    private const val START_TIMEOUT_SECONDS: Long = 10L
    private const val TOOLKIT_EXITED_MESSAGE: String = "Platform.exit has been called"
    private const val EXIT_SILENCE_MS: Long = 2_000L
    private const val NANOS_PER_MILLI: Long = 1_000_000L

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
     * Silence counts only while an implicit exit is possible at all and no engine block is running
     * on the FX thread, and it is measured from the later of the probe's issue time and the last
     * start or end of an engine block (see [hasExited]). At most one probe is outstanding, so a dead
     * toolkit costs one block, never a backlog.
     */
    private class Probe {
        val issuedAtNanos: Long = System.nanoTime()
        // A latch: [hasExited] is plain synchronous code and must be able to wait for it (D-21).
        val answered: CountDownLatch = CountDownLatch(1)
    }

    /**
     * Engine blocks running on the FX thread now, and when one last started or ended
     * (`System.nanoTime`); null until one has. One immutable snapshot, so a reader never combines
     * two separate reads.
     */
    private class OwnWork(val running: Int, val lastEdgeNanos: Long?)

    // A state flow, not atomics (D-21/D-24): written on the FX thread at the edges of every counted
    // block, read by hasExited on any thread. One snapshot, so "nothing running" and "last edge" are
    // always read together.
    private val ownWork: MutableStateFlow<OwnWork> = MutableStateFlow(OwnWork(0, null))

    /** Milliseconds [p] has been silent: from the later of its issue and the last own-block edge. */
    private fun silentMs(p: Probe): Long {
        val now: Long = System.nanoTime()
        val edge: Long? = ownWork.value.lastEdgeNanos
        val sinceIssue: Long = now - p.issuedAtNanos
        val since: Long = if (edge == null) sinceIssue else minOf(sinceIssue, now - edge)
        return since / NANOS_PER_MILLI
    }

    private fun provenSilent(p: Probe): Boolean =
        ownWork.value.running == 0 && silentMs(p) >= EXIT_SILENCE_MS

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

    /**
     * The ordering rule, over [runLater] (read at every call, so a test's swap takes effect) and
     * `Platform.isFxApplicationThread`; the edges of each queued block feed [ownWork].
     */
    private val queue: FxQueue = FxQueue(
        runLater = { runLater(it) },
        isFxThread = { Platform.isFxApplicationThread() },
        onEdge = { delta -> ownWork.update { OwnWork(it.running + delta, System.nanoTime()) } },
    )

    /** `Platform.startup`; a seam so a test can simulate a toolkit that has exited. */
    // Volatile: a test swaps it on its own thread while engines read it on theirs.
    @Volatile
    internal var startup: (Runnable) -> Unit = { Platform.startup(it) }

    /**
     * Whether an implicit exit can happen at all: `Platform.isImplicitExit()`. A seam so a test can
     * simulate a host that keeps it on.
     */
    // Volatile: a test swaps it on its own thread while engines read it on theirs.
    @Volatile
    internal var implicitExitPossible: () -> Boolean = { Platform.isImplicitExit() }

    /** [implicitExitPossible], answering true (the conservative old behaviour) when it cannot tell. */
    private fun implicitExitPossibleSafely(): Boolean = try {
        implicitExitPossible()
    } catch (e: LinkageError) {
        true
    } catch (e: Exception) {
        true
    }

    /**
     * Whether this engine's own `Platform.startup` succeeded and the implicit exit it implies has not been switched off
     * yet. Sticky across a first start whose wait ended early (interrupted, or timed out as "busy, try again"): the retry
     * meets "Toolkit already initialized" for a toolkit this engine did start. Consumed (reset) once the switch is
     * made, so a later call that finds the toolkit running leaves whatever policy the host chose since. An atomic,
     * shared between threads (D-24).
     */
    private val engineStartedToolkit: AtomicBoolean = AtomicBoolean(false)

    /**
     * Starts the toolkit, or confirms an already running one accepts work.
     *
     * Implicit exit is switched off only for a toolkit this engine started, by this call or by an earlier call whose
     * wait ended early and is being retried: with no window of its own the engine would otherwise never trigger it
     * anyway, but an app that runs its own JavaFX windows keeps whatever exit policy it chose.
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
        try {
            startup(Runnable {
                noteToolkitThread()
                started.countDown()
            })
            engineStartedToolkit.set(true)
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
        }
        if (!started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw FxThreadUnresponsiveException(
                "The JavaFX application thread did not respond within $START_TIMEOUT_SECONDS s",
            )
        }
        if (engineStartedToolkit.compareAndSet(true, false)) Platform.setImplicitExit(false)
    }

    /**
     * Whether the toolkit has exited, however it ended. Only for a toolkit already seen running:
     * `Platform.startup` then throws at once without side effects, with the exit message after
     * `Platform.exit()`. An implicit exit (the last window closed) and a dead toolkit give no
     * message: a dead [toolkitThread] answers at once, and otherwise a [Probe] that the FX thread
     * leaves unanswered for [EXIT_SILENCE_MS]. Silence counts only while an implicit exit is
     * possible (without it OpenJFX ends only by `Platform.exit()` or with its thread, both seen
     * above, so a silent thread is a busy one) and never while the FX thread runs one of the
     * engine's own blocks; it is measured from the later of the probe's issue and the last start or
     * end of such a block. With [wait], waits at most [EXIT_SILENCE_MS] in total for the answer, so
     * a live idle toolkit answers in well under a millisecond and an exited one in at most that
     * long, once: the next call finds the same probe already old. Own work that stretched the
     * silence leaves the answer "not proven", which means not exited. Without [wait], never waits.
     * Never waits on the FX thread itself, which is alive by being there. Called before the toolkit
     * was ever initialised it would start it.
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
        // PlatformImpl.notifyFinishListeners ends the toolkit silently only when implicit exit is on;
        // otherwise a silent thread is a busy one.
        if (!implicitExitPossibleSafely()) return false
        val outstanding: Probe = currentProbe()
        if (wait) {
            val remainingMs: Long = EXIT_SILENCE_MS - silentMs(outstanding)
            if (remainingMs > 0L) outstanding.answered.await(remainingMs, TimeUnit.MILLISECONDS)
        }
        return outstanding.answered.count != 0L && provenSilent(outstanding)
    }

    /** Whether the caller is the FX application thread; false where JavaFX cannot even load. */
    fun isCurrent(): Boolean = try {
        Platform.isFxApplicationThread().also { if (it) noteToolkitThread() }
    } catch (e: LinkageError) {
        false
    }

    /**
     * Runs [block] on the FX thread, after every block queued before it: inline when already there
     * and nothing is queued, queued behind the rest otherwise. See [FxQueue.post].
     */
    fun post(block: () -> Unit) = queue.post(block)

    /**
     * Like [post], but always queued behind whatever is already waiting, even on the FX thread with
     * nothing queued. See [FxQueue.postLater].
     */
    fun postLater(block: () -> Unit) = queue.postLater(block)

    /**
     * Runs [block] on the FX thread and suspends for its result or exception. Never resumes if the
     * toolkit has exited: a caller bounds the wait itself. See [FxQueue.call].
     */
    suspend fun <T> call(block: () -> T): T = queue.call(block)
}

/**
 * The JavaFX toolkit is running but its application thread did not answer in time, as when it is
 * busy. Unlike a missing runtime this may pass, so [SystemJavaFxRuntime] does not remember it.
 */
internal class FxThreadUnresponsiveException(message: String) : IllegalStateException(message)
