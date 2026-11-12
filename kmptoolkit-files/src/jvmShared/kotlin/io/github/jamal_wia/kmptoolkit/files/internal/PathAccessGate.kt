package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.files.CaseFolding
import io.github.jamal_wia.kmptoolkit.files.PathKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates the access of one process to paths, for the Windows ops: a rename cannot replace a target that is open,
 * so readers hold a shared permit while their file is open and a replace holds an exclusive permit on both of its
 * paths. Blocking, because FileAccess is blocking. The registry holds an entry only while somebody holds or awaits it,
 * so it does not grow with the paths ever used. Keys fold by [PathKey] with [CaseFolding.Always] (NTFS is
 * case-insensitive). Locks are fair: a waiting exclusive holder blocks later shared requests, so continuous readers
 * cannot starve a replace. A permit may be released by any thread: it is made of semaphore permits, which no thread
 * owns, so a close on another thread than the open can never leak it. The engine never holds a shared permit (an open
 * ReadableFile) while it asks for an exclusive one, which keeps the gate free of deadlock.
 */
internal class PathAccessGate {

    /** A held permit; [release] is idempotent. */
    internal interface Permit {
        fun release()
    }

    private class Entry {
        // A fair semaphore: a shared holder takes one permit, an exclusive holder all of them, and the FIFO queue makes
        // a waiting exclusive holder block later shared requests.
        val permits: Semaphore = Semaphore(ALL_PERMITS, true)
        var users: Int = 0
    }

    private companion object {
        const val ALL_PERMITS: Int = Int.MAX_VALUE
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
            entry.permits.acquire(1)
        } catch (t: Throwable) {
            leave(key)
            throw t
        }
        return OncePermit {
            entry.permits.release(1)
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
                    entry.permits.acquire(ALL_PERMITS)
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
            entry.permits.release(ALL_PERMITS)
            leave(key)
        }
    }

    private class OncePermit(private val action: () -> Unit) : Permit {
        private val released: AtomicBoolean = AtomicBoolean(false)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            action()
        }
    }
}
