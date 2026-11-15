package io.github.jamal_wia.kmptoolkit.smoke.storage

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The cleanup of a run that is KILLED. `main`'s `finally` and `guarded`'s cleanup do not run when the JVM is stopped from outside: Gradle
 * cancelling the build, the lead's `timeout`, Ctrl-C, a closed terminal. The JVM then runs only its shutdown hooks, so the same cleanup is
 * registered as one: best effort, bounded, and idempotent with the normal path.
 *
 * What it covers: SIGTERM, SIGINT and SIGHUP (the JVM runs its shutdown hooks on all three; `Process.destroy()` and `timeout` send SIGTERM).
 * What it cannot cover: SIGKILL (`kill -9`, `Process.destroyForcibly()`, an out-of-memory kill) and a crash of the JVM itself; a hard-killed
 * run may leave ONE key item labelled like ITEM_LABEL and one `kmptoolkit-smoke-*` temporary directory. The Gradle-side check
 * `verifySmokeLeftNoKeychainItem` after the packaged run is the backstop for the item (it reports it), as long as Gradle itself survives.
 */

/** How long the shutdown cleanup waits for a key item removal, its own or another thread's: a hook must never hold the process for long. */
internal const val SHUTDOWN_BOUND_MILLIS: Long = 10_000L

/**
 * A [KeyItemRemoval] that runs [inner] at most once, whoever asks first: the normal path of the run and the shutdown hook share one, so a
 * hook that fires while the run is cleaning up neither deletes twice nor lets the process end under the run's deletion (the binding takes
 * one thread at a time). A later caller waits, at most [waitMillis], for the first one to finish and then returns, or throws the first
 * one's failure, so a removal that failed is never reported as done.
 */
internal class OnceRemoval(private val inner: KeyItemRemoval, private val waitMillis: Long = SHUTDOWN_BOUND_MILLIS) : KeyItemRemoval {
    private val started: AtomicBoolean = AtomicBoolean(false)
    private val outcome: CompletableFuture<Unit> = CompletableFuture()

    override fun remove(expectPresent: Boolean) {
        if (started.compareAndSet(false, true)) {
            try {
                inner.remove(expectPresent)
                outcome.complete(Unit)
            } catch (failure: Throwable) {
                outcome.completeExceptionally(failure)
                throw failure
            }
            return
        }
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
 * Every failure is reported to [report], never swallowed. [report] defaults to the standard error stream.
 */
internal class ShutdownCleanup(
    private val directory: File,
    private val boundMillis: Long = SHUTDOWN_BOUND_MILLIS,
    private val report: (String) -> Unit = { line: String -> System.err.println(line) },
) {
    @Volatile
    private var removal: KeyItemRemoval? = null

    /**
     * Arms the hook with [item]'s removal and returns the removal the run itself must use: the same, wrapped so that it runs once. Called
     * BEFORE anything can make the key item, so a kill at any later moment finds it armed.
     */
    fun arm(item: KeyItemRemoval): KeyItemRemoval = OnceRemoval(item, boundMillis).also { removal = it }

    /** Registers [runNow] as a shutdown hook of this JVM. */
    fun install() {
        Runtime.getRuntime().addShutdownHook(Thread({ runNow() }, "smoke-shutdown-cleanup"))
    }

    /**
     * The cleanup: the key item (no control that it exists: a killed run may have died before the key was made), then the directory, both
     * even when the first fails. Bounded by [boundMillis] for the removal; never throws.
     */
    fun runNow() {
        removal?.let { armed: KeyItemRemoval ->
            try {
                runBounded("shutdown key item removal", boundMillis) { armed.remove(expectPresent = false) }
            } catch (failure: Throwable) {
                report("shutdown cleanup: ALSO FAILED (a key item labelled '$ITEM_LABEL' may be left in the login keychain): ${failure::class.java.name}: ${failure.message}")
            }
        }
        if (!deleteDirectory()) report("shutdown cleanup: ALSO FAILED: the temporary directory $directory could not be deleted")
    }

    /** Deletes the temporary directory; true when it is gone. Safe to call twice and from any thread. */
    fun deleteDirectory(): Boolean {
        directory.deleteRecursively()
        return !directory.exists()
    }
}
