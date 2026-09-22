package io.github.jamal_wia.kmptoolkit.video.player

import platform.Foundation.NSRecursiveLock

internal actual fun newReentrantLock(): ReentrantLockHandle = object : ReentrantLockHandle {
    private val delegate: NSRecursiveLock = NSRecursiveLock()

    override fun lock() = delegate.lock()

    override fun unlock() = delegate.unlock()

    override fun tryLock(): Boolean = delegate.tryLock()
}
