package io.github.jamal_wia.kmptoolkit.smoke.storage

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/*
 * The cleanup of a run that is KILLED. `main`'s `finally` and `guarded`'s cleanup do not run when the JVM is stopped from outside: Gradle
 * cancelling the build (it sends SIGTERM to the app: `Process.destroy()` plus its descendants), the lead's `timeout`, a plain `kill`. The JVM
 * then runs only its shutdown hooks, so the cleanup is registered as one: best effort, bounded, and idempotent with the normal path.
 *
 * The JVM does NOT stop the main thread while it runs the hooks, so a hook that cleans up at once races a store call that is still running:
 * the call can make its key item, or recreate the directory, after the hook has cleaned up. The hook therefore does not race the run, it
 * lets the run clean up after itself (breaker round 3, m1):
 *  1. it sets the `closing` flag ([ShutdownCleanup.stop]); every step of the run checks it before it starts, so the run stops at its next
 *     step; a store call that is already in flight cannot be stopped and finishes first;
 *  2. it waits, at most `runWaitMillis`, for the run to end: the run's own guarded cleanup (store data, key item, with the control that
 *     the item exists) and its directory work run after the call that was in flight, so they see what that call made;
 *  3. only when that wait times out (a run that does not come back, such as a wedged native call) does the hook clean up itself.
 *
 * What it covers: SIGTERM (a Gradle cancel, `timeout`, `Process.destroy()`) and also SIGINT and SIGHUP when they reach the app (the JVM runs
 * its hooks on all three; Ctrl-C and a closed terminal reach the Gradle client, not the app, which is a child of the Gradle daemon).
 * What it cannot cover: SIGKILL (`kill -9`, `Process.destroyForcibly()`, an out-of-memory kill) and a crash of the JVM itself; a hard-killed
 * run may leave ONE key item labelled like ITEM_LABEL and one `kmptoolkit-smoke-*` temporary directory. The Gradle-side check
 * `verifySmokeLeftNoKeychainItem` after the packaged run reports the item of a run that was killed hard, but a CANCELLED Gradle build runs no
 * finalizer: the next macOS run that is not probe-only then reports the item.
 */

/** How long the shutdown cleanup waits for a key item removal, its own or another thread's: a hook must never hold the process for long. */
internal const val SHUTDOWN_BOUND_MILLIS: Long = 10_000L

/** The reason a step of the run refuses to start once the JVM is shutting down. */
internal const val STOPPED_FROM_OUTSIDE: String = "stopped from outside: the JVM is shutting down (the process was told to end)"

/** What every step of the run asks before it starts, so the run stops at its next step once the shutdown hook has begun. */
internal fun interface StopSignal {
    /** Returns normally while the run may go on; throws [SmokeFailure] ([STOPPED_FROM_OUTSIDE]) once the process is being stopped. */
    fun check()
}

/** A signal that never stops: for a run that nothing can stop from outside (the module's own tests). */
internal object NeverStopped : StopSignal {
    override fun check() = Unit
}

/**
 * A [KeyItemRemoval] that serialises the use of the binding and removes at most once for the run, whoever asks first (the normal path of
 * the run and the shutdown hook share one): a removal that is in progress or done is never started again, a later caller waits, at most
 * [waitMillis], for it and then returns, or throws its failure, so a removal that failed is never reported as done.
 *
 * The run's own removal ([remove]) is the one that counts. The hook uses [removeFromHook] only when the run did not come back in time; if
 * the run removes later, it removes AGAIN (without insisting the item is still there, the hook may have deleted it): it never trusts the
 * hook's old success, because a store call that was still in flight can have made the item after the hook was done. The binding takes
 * one thread at a time, so the two never run together: the second waits for the first, bounded by [waitMillis]. [onFinished] is told
 * when the run's own removal has ended, however it ended.
 */
internal class OnceRemoval(
    private val inner: KeyItemRemoval,
    private val waitMillis: Long = SHUTDOWN_BOUND_MILLIS,
    private val onFinished: () -> Unit = {},
) : KeyItemRemoval {
    private val started: AtomicBoolean = AtomicBoolean(false)
    private val outcome: CompletableFuture<Unit> = CompletableFuture()
    private val binding: ReentrantLock = ReentrantLock()

    @Volatile
    private var removedByHook: Boolean = false

    override fun remove(expectPresent: Boolean) {
        if (started.compareAndSet(false, true)) {
            try {
                // Read inside the turn: the hook may finish its own removal while this call waits for the binding.
                inTurn { inner.remove(expectPresent && !removedByHook) }
                outcome.complete(Unit)
            } catch (failure: Throwable) {
                outcome.completeExceptionally(failure)
                throw failure
            } finally {
                onFinished()
            }
            return
        }
        awaitOutcome()
    }

    /**
     * The hook's removal, after it gave up waiting for the run: it removes now (an absent item is fine) unless the run's own removal has
     * already started, in which case it waits for that one instead.
     */
    fun removeFromHook() {
        if (!started.get()) {
            val ran: Boolean = inTurn {
                if (started.get()) {
                    false
                } else {
                    inner.remove(expectPresent = false)
                    removedByHook = true
                    true
                }
            }
            if (ran) return
        }
        awaitOutcome()
    }

    /** Runs [block] while no other thread uses the binding: waits for the lock at most [waitMillis]. */
    private fun <T> inTurn(block: () -> T): T {
        if (!binding.tryLock(waitMillis, TimeUnit.MILLISECONDS)) {
            throw SmokeFailure("the key store binding was still busy with another thread's key item removal after $waitMillis ms")
        }
        try {
            return block()
        } finally {
            binding.unlock()
        }
    }

    private fun awaitOutcome() {
        try {
            outcome.get(waitMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw SmokeFailure("the key item removal that another thread started did not finish within $waitMillis ms")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }
}

/**
 * Removes what a killed run leaves: the key item of its secure store (once [arm]ed) and the temporary [directory]. [install] registers it
 * as a JVM shutdown hook. It runs after a normal end too (`exitProcess`), where everything is already gone and it finds nothing to do.
 * The hook first stops the run and waits for it (at most [runWaitMillis]); see the overview at the top of this file. The key item removal
 * of the hook itself is bounded by [boundMillis]. Every failure is reported to [report], never swallowed. [report] defaults to the standard
 * error stream.
 *
 * What "the run is over" means for the hook: with [endsWithMain] (`main` of the app) it is `main`'s [markRunFinished], called after the verdict
 * is printed and written, so the JVM cannot halt under the lines that report a failed cleanup. Without it (the module's own tests, which have
 * no `main`) it is the end of the run's own key item removal, the last step of a secure run.
 */
internal class ShutdownCleanup(
    private val directory: File,
    private val boundMillis: Long = SHUTDOWN_BOUND_MILLIS,
    private val runWaitMillis: Long = SHUTDOWN_BOUND_MILLIS,
    private val endsWithMain: Boolean = false,
    private val report: (String) -> Unit = { line: String -> System.err.println(line) },
) {
    @Volatile
    private var removal: OnceRemoval? = null

    @Volatile
    private var closing: Boolean = false

    /** Completed when the run is over: `main` says so ([markRunFinished]), or, without a `main`, its key item removal ended. */
    private val runEnded: CompletableFuture<Unit> = CompletableFuture()

    /** The signal every step of the run checks before it starts: it throws once the hook has begun. */
    val stop: StopSignal = StopSignal { if (closing) throw SmokeFailure(STOPPED_FROM_OUTSIDE) }

    /**
     * Arms the hook with [item]'s removal and returns the removal the run itself must use: the same, serialised and run-once. Called
     * BEFORE anything can make the key item, so a kill at any later moment finds it armed.
     */
    fun arm(item: KeyItemRemoval): KeyItemRemoval = OnceRemoval(item, boundMillis, onFinished = { if (!endsWithMain) runEnded.complete(Unit) }).also { removal = it }

    /** `main` calls this when the run is over and its directory is dealt with, on every way out; the hook then finds nothing to wait for. */
    fun markRunFinished() {
        runEnded.complete(Unit)
    }

    /** Registers [runNow] as a shutdown hook of this JVM. */
    fun install() {
        Runtime.getRuntime().addShutdownHook(Thread({ runNow() }, "smoke-shutdown-cleanup"))
    }

    /**
     * The hook's work: stop the run, wait for it to end, and clean up itself only if it did not (the key item with no control that it
     * exists, a killed run may have died before the key was made), then the directory, both even when the first fails. The directory is
     * deleted in every case (a run that ended has already done it, deleting again is harmless). Never throws.
     */
    fun runNow() {
        closing = true
        val ended: Boolean = awaitRunEnd()
        if (!ended) {
            removal?.let { armed: OnceRemoval ->
                try {
                    runBounded("shutdown key item removal", boundMillis) { armed.removeFromHook() }
                } catch (failure: Throwable) {
                    report("shutdown cleanup: ALSO FAILED (a key item labelled '$ITEM_LABEL' may be left in the login keychain): ${failure::class.java.name}: ${failure.message}")
                }
            }
        }
        // A run that ended reports its own directory failure; only a hook that cleaned up in its place says it.
        if (!deleteDirectory() && !ended) report("shutdown cleanup: ALSO FAILED: the temporary directory $directory could not be deleted")
    }

    private fun awaitRunEnd(): Boolean = try {
        runEnded.get(runWaitMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: TimeoutException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    /** Deletes the temporary directory; true when it is gone. Safe to call twice and from any thread. */
    fun deleteDirectory(): Boolean {
        directory.deleteRecursively()
        return !directory.exists()
    }
}
