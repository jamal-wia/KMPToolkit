package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * The FX thread's ordering rule, apart from the thread itself: a block handed over runs after every
 * block queued before it, whichever thread handed it over. Inline when the caller is already on the
 * FX thread and nothing is queued; queued behind the rest otherwise.
 *
 * [runLater] is how a block reaches the FX thread (`Platform.runLater`, or a test's manual queue),
 * [isFxThread] says whether the caller is on it, and [onEdge] hears `+1` when a queued block starts
 * running and `-1` when it ends, so the owner can account for the engine's own work on the FX
 * thread. Every one of them may be called from any thread.
 */
internal class FxQueue(
    private val runLater: (Runnable) -> Unit,
    private val isFxThread: () -> Boolean,
    private val onEdge: (delta: Int) -> Unit,
) {

    /**
     * How many blocks [post] and [call] have queued with [runLater] that have not started yet,
     * so a call on the FX thread knows whether running inline would overtake an earlier one. An
     * atomic rather than a coroutine tool: [post] serves the engine's plain synchronous transport
     * calls, and the count must be right on whichever thread makes one.
     */
    private val queued: AtomicInteger = AtomicInteger(0)

    /** The blocks queued and not yet started; for tests of the counter. */
    internal val pending: Int get() = queued.get()

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
        if (isFxThread() && queued.get() == 0) {
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
     * Like [post], but always queued behind whatever is already waiting, even on the FX thread with nothing queued:
     * for a step that must run after the calls the caller's thread made right after the current one.
     */
    fun postLater(block: () -> Unit) {
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
        if (isFxThread() && queued.get() == 0) task() else runLaterCounted(task)
        return result.await()
    }

    /**
     * [runLater], counted in [queued] until [block] is taken off the queue — uncounted
     * before it runs, since a call made from inside it comes after it anyway and only what is still
     * queued may hold that call back. Throws as `runLater` does.
     */
    private fun runLaterCounted(block: () -> Unit) {
        queued.incrementAndGet()
        try {
            runLater(
                Runnable {
                    queued.decrementAndGet()
                    // Counted only once it starts running, never at enqueue: after an exit runLater
                    // drops blocks, and a dropped block must never look like work in progress.
                    onEdge(1)
                    try {
                        block()
                    } finally {
                        onEdge(-1)
                    }
                },
            )
        } catch (e: IllegalStateException) {
            // Never queued, so never counted.
            queued.decrementAndGet()
            throw e
        }
    }
}
