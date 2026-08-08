package io.github.jamal_wia.kmptoolkit.notification

import platform.Foundation.NSLock

internal actual fun notifierLock(): NotifierLock = NsNotifierLock()

/** iOS [NotifierLock]: an `NSLock`, the same primitive the donor implementation used. */
private class NsNotifierLock : NotifierLock {

    private val lock = NSLock()

    override fun <T> withLock(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}
