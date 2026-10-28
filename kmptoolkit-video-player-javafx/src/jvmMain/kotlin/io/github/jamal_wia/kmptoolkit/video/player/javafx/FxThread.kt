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
     * Starts the toolkit, or confirms an already running one accepts work.
     *
     * Implicit exit is switched off only when this call is the one that started the toolkit: with no
     * window of its own the engine would otherwise never trigger it anyway, but an app that runs its
     * own JavaFX windows keeps whatever exit policy it chose.
     */
    fun startToolkit() {
        val started = CountDownLatch(1)
        val startedHere: Boolean = try {
            Platform.startup { started.countDown() }
            true
        } catch (e: IllegalStateException) {
            // "Toolkit already initialized" — the app, or an earlier player, started it.
            Platform.runLater { started.countDown() }
            false
        }
        check(started.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "The JavaFX application thread did not respond within $START_TIMEOUT_SECONDS s"
        }
        if (startedHere) Platform.setImplicitExit(false)
    }

    /**
     * Runs [block] on the FX thread, after every block queued before it. Inline when already there
     * and nothing is queued; queued behind the rest otherwise — run inline then, a `pause()` made on
     * the FX thread (an engine callback reacting to an event, say) would overtake a `start()` queued
     * from another thread a moment earlier, and the source would play on while the player above
     * reports Paused. Dropped when the toolkit has exited (the app called `Platform.exit()`): the
     * engine's transport calls tolerate the wrong state.
     */
    fun post(block: () -> Unit) {
        if (Platform.isFxApplicationThread() && queued.get() == 0) {
            block()
            return
        }
        try {
            runLaterCounted(block)
        } catch (e: IllegalStateException) {
            // Toolkit has exited; nothing is playing any more either.
        }
    }

    /** Runs [block] on the FX thread and suspends for its result or exception. */
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
            Platform.runLater {
                queued.decrementAndGet()
                block()
            }
        } catch (e: IllegalStateException) {
            // Never queued, so never counted.
            queued.decrementAndGet()
            throw e
        }
    }
}
