package io.github.jamal_wia.kmptoolkit.video.player

import java.util.concurrent.locks.ReentrantLock

internal actual fun newReentrantLock(): ReentrantLockHandle = object : ReentrantLockHandle {
    private val delegate: ReentrantLock = ReentrantLock()

    override fun lock() = delegate.lock()

    override fun unlock() = delegate.unlock()

    override fun tryLock(): Boolean = delegate.tryLock()
}
