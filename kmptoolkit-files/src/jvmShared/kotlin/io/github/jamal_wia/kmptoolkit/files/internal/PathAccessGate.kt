package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.PathKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Coordinates the access of one process to paths, for the Windows ops: a rename cannot replace a target that is open,
 * so readers hold a shared permit while their file is open and a replace holds an exclusive permit on both of its
 * paths. Blocking, because FileAccess is blocking. The registry holds an entry only while somebody holds or awaits it,
 * so it does not grow with the paths ever used. Keys fold by [PathKey] with [CaseFolding.Always] (NTFS is
 * case-insensitive). Locks are fair: a waiting exclusive holder blocks later shared requests, so continuous readers
 * cannot starve a replace. A shared permit must be released by the thread that took it.
 */
internal class PathAccessGate {

    /** A held permit; [release] is idempotent. */
    internal interface Permit {
        fun release()
    }

    private class Entry {
        val lock: ReentrantReadWriteLock = ReentrantReadWriteLock(true)
        var users: Int = 0
    }

    private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

    /** How many keys are held or awaited right now; 0 when idle. */
    val activeKeyCount: Int
        get() = entries.size

    private fun join(key: String): Entry =
        entries.compute(key) { _, existing -> (existing ?: Entry()).also { it.users++ } }!!

    private fun leave(key: String) {
        entries.compute(key) { _, existing ->
            if (existing == null) null else {
                existing.users--
                if (existing.users <= 0) null else existing
            }
        }
    }

    /** Waits until no exclusive permit is held or awaited on [path], then holds a shared one. */
    fun shared(path: String): Permit {
        val key: String = PathKey.of(path, CaseFolding.Always)
        val entry: Entry = join(key)
        try {
            entry.lock.readLock().lock()
        } catch (t: Throwable) {
            leave(key)
            throw t
        }
        return OncePermit {
            entry.lock.readLock().unlock()
            leave(key)
        }
    }

    /** Waits for every holder of every one of [paths] (distinct keys, taken in ascending key order), then holds all. */
    fun exclusive(vararg paths: String): Permit {
        val keys: List<String> = paths.map { PathKey.of(it, CaseFolding.Always) }.distinct().sorted()
        val taken: MutableList<Pair<String, Entry>> = mutableListOf()
        try {
            for (key: String in keys) {
                val entry: Entry = join(key)
                try {
                    entry.lock.writeLock().lock()
                } catch (t: Throwable) {
                    leave(key)
                    throw t
                }
                taken += key to entry
            }
        } catch (t: Throwable) {
            release(taken)
            throw t
        }
        return OncePermit { release(taken) }
    }

    private fun release(taken: List<Pair<String, Entry>>) {
        for ((key: String, entry: Entry) in taken.asReversed()) {
            entry.lock.writeLock().unlock()
            leave(key)
        }
    }

    private class OncePermit(private val action: () -> Unit) : Permit {
        private var released: Boolean = false

        override fun release() {
            if (released) return
            released = true
            action()
        }
    }
}
