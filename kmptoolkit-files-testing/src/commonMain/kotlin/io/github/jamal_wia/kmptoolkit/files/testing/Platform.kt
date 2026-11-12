package io.github.jamal_wia.kmptoolkit.files.testing

import kotlinx.coroutines.CoroutineScope

/**
 * A blocking lock for the in-memory file system. [io.github.jamal_wia.kmptoolkit.files.FileAccess] is a blocking
 * API that any thread may call, so its test double needs a lock a blocking caller can wait on; coroutine
 * primitives cannot do that from a plain function. Reentrant.
 */
internal expect class Monitor() {
    fun <T> withLock(block: () -> T): T
}

/** Runs [block] once per worker index on [workers] threads at the same time and waits for all of them. */
internal expect fun runConcurrently(workers: Int, block: (index: Int) -> Unit)

/** Runs the suspending [block] to completion on the calling thread, which blocks until it is done. */
internal expect fun <T> runBlockingForContract(block: suspend CoroutineScope.() -> T): T
