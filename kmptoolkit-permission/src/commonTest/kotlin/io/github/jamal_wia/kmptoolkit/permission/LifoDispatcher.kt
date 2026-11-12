package io.github.jamal_wia.kmptoolkit.permission

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * A manual dispatcher that runs the NEWEST queued task first, driven by the test thread only. Under it a coroutine
 * that is launched and then followed by other work does not run before that work: the order a busy multi-threaded
 * dispatcher can produce, made repeatable. Tasks run only inside [step] and [drain].
 */
class LifoDispatcher : CoroutineDispatcher() {

    private val queue: ArrayDeque<Runnable> = ArrayDeque()

    val pending: Int get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    /** Runs the newest task; `false` when none is queued. */
    fun step(): Boolean {
        val next: Runnable = queue.removeLastOrNull() ?: return false
        next.run()
        return true
    }

    fun drain(limit: Int = 100_000) {
        var steps = 0
        while (step()) check(++steps < limit) { "the dispatcher never went idle" }
    }
}
