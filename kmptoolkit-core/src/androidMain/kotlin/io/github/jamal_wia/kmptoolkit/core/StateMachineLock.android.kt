package io.github.jamal_wia.kmptoolkit.core

import java.util.concurrent.locks.ReentrantLock

@ToolkitInternalApi
public actual fun newReentrantLock(): ReentrantLockHandle = object : ReentrantLockHandle {
    private val delegate: ReentrantLock = ReentrantLock()

    override fun lock() = delegate.lock()

    override fun unlock() = delegate.unlock()

    override fun tryLock(): Boolean = delegate.tryLock()
}
