package io.github.jamal_wia.kmptoolkit.permission

/**
 * The decision of [awaitPossiblePrompt]: which of a decided status, the grace period elapsing with the app still
 * active, or the app becoming active again after it resigned ends the wait, and that the wait ends - its
 * observers and its request released, its value delivered - exactly once, a cancellation included. Driven only
 * from the main queue, like every event that reaches it; not thread-safe.
 */
internal class PossiblePromptWait<T>(
    private val fallback: () -> T,
    private val release: () -> Unit,
    private val deliver: (T) -> Unit,
) {
    private var resigned: Boolean = false
    private var finished: Boolean = false

    fun onWillResignActive() {
        resigned = true
    }

    fun onDidBecomeActive() {
        if (resigned) finish(fallback())
    }

    fun onGraceElapsed() {
        if (!resigned) finish(fallback())
    }

    fun onDecided(value: T) {
        finish(value)
    }

    fun onCancelled() {
        if (finished) return
        finished = true
        release()
    }

    private fun finish(value: T) {
        if (finished) return
        finished = true
        release()
        deliver(value)
    }
}
