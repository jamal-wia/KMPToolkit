package io.github.jamal_wia.kmptoolkit.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One coroutine [Mutex] per key, held in a registry only while some caller holds or awaits it, so the registry does
 * not grow with the number of keys ever used. Keys are compared with equals/hashCode and must be immutable.
 * Not reentrant: a [withLock] nested in a [withLock] of the same key deadlocks. Safe from any thread on every
 * platform.
 *
 * Taking and releasing a key costs time proportional to the number of keys held or awaited at that moment (the
 * registry is copied on every change): it is built for up to hundreds of keys held at once, not tens of thousands.
 *
 * @since 5.0.0
 */
public class KeyedMutex<K : Any> {
    private class Entry(val mutex: Mutex, val users: Int)

    private val registry: MutableStateFlow<Map<K, Entry>> = MutableStateFlow(emptyMap())

    /**
     * Runs [action] while no other [withLock] or [tryWithLock] of [key] runs. Callers of one key run one at a time,
     * callers of different keys never wait for each other. The key is released when [action] returns, throws, or the
     * caller is cancelled while waiting or running.
     */
    public suspend fun <T> withLock(key: K, action: suspend () -> T): T {
        val mutex: Mutex = register(key)
        try {
            return mutex.withLock { action() }
        } finally {
            release(key)
        }
    }

    /**
     * Runs [action] only if no caller holds or awaits [key] right now, and returns whether it ran. Never suspends and
     * never waits; other keys' callers never make it fail. A [withLock] of [key] that arrives while [action] runs
     * waits for it. [action] should be short and must not suspend.
     */
    public fun tryWithLock(key: K, action: () -> Unit): Boolean {
        val mutex: Mutex = Mutex(locked = true)
        val entry: Entry = Entry(mutex, 1)
        while (true) {
            val current: Map<K, Entry> = registry.value
            if (current.containsKey(key)) return false
            if (registry.compareAndSet(current, current + (key to entry))) break
        }
        try {
            action()
        } finally {
            mutex.unlock()
            release(key)
        }
        return true
    }

    /** How many keys are held or awaited right now. 0 when idle: the leak check for tests. */
    public val activeKeyCount: Int
        get() = registry.value.size

    private fun register(key: K): Mutex {
        while (true) {
            val current: Map<K, Entry> = registry.value
            val existing: Entry? = current[key]
            val next: Entry = if (existing == null) Entry(Mutex(), 1) else Entry(existing.mutex, existing.users + 1)
            if (registry.compareAndSet(current, current + (key to next))) return next.mutex
        }
    }

    private fun release(key: K) {
        while (true) {
            val current: Map<K, Entry> = registry.value
            val existing: Entry = current[key] ?: return
            val next: Map<K, Entry> =
                if (existing.users <= 1) current - key else current + (key to Entry(existing.mutex, existing.users - 1))
            if (registry.compareAndSet(current, next)) return
        }
    }
}
