package io.github.jamal_wia.kmptoolkit.settings

import platform.Foundation.NSLock

internal actual fun settingsLock(): SettingsLock = NsSettingsLock()

/** iOS [SettingsLock]: an `NSLock`. */
private class NsSettingsLock : SettingsLock {

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
