package io.github.jamal_wia.kmptoolkit.files.testing

import kotlinx.coroutines.CoroutineScope

/**
 * A blocking lock for the in-memory file system. [io.github.jamal_wia.kmptoolkit.files.FileAccess] is a blocking
 * API that any thread may call, so its test double needs a lock a blocking caller can wait on; coroutine
 * primitives cannot do that from a plain function. Reentrant.
 *
 * An interface plus an `expect fun` factory, rather than an `expect class`, as the rest of the suite does
 * (`kmptoolkit-notification`'s `NotifierLock`): it keeps the module free of the still-Beta `expect class`
 * warning (KT-61573) without a compiler flag.
 */
internal interface Monitor {

    /** Runs [block] holding the lock, releasing it even if [block] throws. */
    fun <T> withLock(block: () -> T): T
}

/** The platform's reentrant lock: a `ReentrantLock` on Android and the desktop JVM, an `NSRecursiveLock` on iOS. */
internal expect fun newMonitor(): Monitor

/** Runs [block] once per worker index on [workers] threads at the same time and waits for all of them. */
internal expect fun runConcurrently(workers: Int, block: (index: Int) -> Unit)

/** Runs the suspending [block] to completion on the calling thread, which blocks until it is done. */
internal expect fun <T> runBlockingForContract(block: suspend CoroutineScope.() -> T): T
