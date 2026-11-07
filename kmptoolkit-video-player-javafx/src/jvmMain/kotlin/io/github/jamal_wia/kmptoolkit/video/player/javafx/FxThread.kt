package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlinx.coroutines.CompletableDeferred

/** The JavaFX application thread: starting the toolkit, and getting work onto that thread. */
internal object FxThread {

    private const val START_TIMEOUT_SECONDS: Long = 10L
    private const val TOOLKIT_EXITED_MESSAGE: String = "Platform.exit has been called"

    /**
     * How many blocks [post] and [call] have queued with `Platform.runLater` that have not started yet,
     * so a call on the FX thread knows whether running inline would overtake an earlier one. An
     * atomic rather than a coroutine tool: [post] serves the engine's plain synchronous transport
     * calls, and the count must be right on whichever thread makes one.
     */
    private val queued: AtomicInteger = AtomicInteger(0)

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
            startup(Runnable { started.countDown() })
            true
        } catch (e: IllegalStateException) {
            // "Toolkit already initialized" — the app, or an earlier player, started it.
            // After Platform.exit startup throws this too, and runLater would drop the block: wait for
            // nothing, and let the runtime remember it as the permanent failure it is. The message is
            // the only signal OpenJFX gives.
            if (e.message == TOOLKIT_EXITED_MESSAGE) throw e
            // On the FX thread the toolkit is running and accepts work: nothing left to confirm.
            if (Platform.isFxApplicationThread()) return
            Platform.runLater { started.countDown() }
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
     * Whether the toolkit has exited. Only for a toolkit already seen running: `Platform.startup` then
     * throws at once without side effects, with the exit message after Platform.exit. Called before the
     * toolkit was ever initialised it would start it.
     */
    fun hasExited(): Boolean = try {
        startup(Runnable { })
        false
    } catch (e: IllegalStateException) {
        e.message == TOOLKIT_EXITED_MESSAGE
    }

    /** Whether the caller is the FX application thread; false where JavaFX cannot even load. */
    fun isCurrent(): Boolean = try {
        Platform.isFxApplicationThread()
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
