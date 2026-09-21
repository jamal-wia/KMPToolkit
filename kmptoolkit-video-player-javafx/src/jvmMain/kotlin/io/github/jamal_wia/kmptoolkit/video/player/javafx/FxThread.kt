package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import kotlinx.coroutines.CompletableDeferred

/** The JavaFX application thread: starting the toolkit, and getting work onto that thread. */
internal object FxThread {

    private const val START_TIMEOUT_SECONDS: Long = 10L

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
     * Runs [block] on the FX thread — inline when already there. Dropped when the toolkit has exited
     * (the app called `Platform.exit()`): the engine's transport calls tolerate the wrong state.
     */
    fun post(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) {
            block()
            return
        }
        try {
            Platform.runLater(block)
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
        if (Platform.isFxApplicationThread()) task() else Platform.runLater(task)
        return result.await()
    }
}
