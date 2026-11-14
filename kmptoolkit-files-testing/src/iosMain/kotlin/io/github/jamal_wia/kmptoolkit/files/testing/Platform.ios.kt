package io.github.jamal_wia.kmptoolkit.files.testing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSRecursiveLock

internal actual fun newMonitor(): Monitor = RecursiveLockMonitor()

private class RecursiveLockMonitor : Monitor {
    private val lock: NSRecursiveLock = NSRecursiveLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

internal actual fun runConcurrently(workers: Int, block: (index: Int) -> Unit) {
    runBlocking {
        (0 until workers).map { index: Int -> async(Dispatchers.Default) { block(index) } }.awaitAll()
    }
}

internal actual fun <T> runBlockingForContract(block: suspend CoroutineScope.() -> T): T = runBlocking(block = block)
