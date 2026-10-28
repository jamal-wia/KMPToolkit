package io.github.jamal_wia.kmptoolkit.language

import platform.Foundation.NSRecursiveLock

internal actual fun languageLock(): LanguageLock = RecursiveLanguageLock()

/** iOS [LanguageLock]: an `NSRecursiveLock` — reentrant, unlike `NSLock`, for the reason [LanguageLock] gives. */
private class RecursiveLanguageLock : LanguageLock {

    private val lock = NSRecursiveLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
