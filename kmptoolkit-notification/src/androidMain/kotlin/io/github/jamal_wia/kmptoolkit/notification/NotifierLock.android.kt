package io.github.jamal_wia.kmptoolkit.notification

import java.util.concurrent.locks.ReentrantLock

internal actual fun notifierLock(): NotifierLock = ReentrantNotifierLock()

/** Android [NotifierLock]: a plain [ReentrantLock]. */
private class ReentrantNotifierLock : NotifierLock {

    private val lock = ReentrantLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
