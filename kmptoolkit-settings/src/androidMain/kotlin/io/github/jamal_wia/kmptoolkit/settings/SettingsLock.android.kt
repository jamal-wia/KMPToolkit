package io.github.jamal_wia.kmptoolkit.settings

import java.util.concurrent.locks.ReentrantLock

internal actual fun settingsLock(): SettingsLock = ReentrantSettingsLock()

/** Android [SettingsLock]: a plain [ReentrantLock]. */
private class ReentrantSettingsLock : SettingsLock {

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
