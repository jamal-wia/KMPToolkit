package io.github.jamal_wia.kmptoolkit.files.testing

import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

internal actual fun newMonitor(): Monitor = ReentrantMonitor()

private class ReentrantMonitor : Monitor {
    private val lock: ReentrantLock = ReentrantLock()

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
