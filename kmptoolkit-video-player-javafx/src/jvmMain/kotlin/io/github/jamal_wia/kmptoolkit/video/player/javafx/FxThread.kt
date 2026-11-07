package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javafx.application.Platform
import kotlinx.coroutines.CompletableDeferred

/** The JavaFX application thread: starting the toolkit, and getting work onto that thread. */
internal object FxThread {

    private const val START_TIMEOUT_SECONDS: Long = 10L

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
    @Volatile
    internal var runLater: (Runnable) -> Unit = Platform::runLater

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
        val started = CountDownLatch(1)
        val startedHere: Boolean = try {
            Platform.startup { started.countDown() }
            true
        } catch (e: IllegalStateException) {
            // "Toolkit already initialized" — the app, or an earlier player, started it.
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
