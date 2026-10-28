package io.github.jamal_wia.kmptoolkit.language

import java.util.concurrent.locks.ReentrantLock

internal actual fun languageLock(): LanguageLock = ReentrantLanguageLock()

/** Android [LanguageLock]: a plain [ReentrantLock]. */
private class ReentrantLanguageLock : LanguageLock {

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
