package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One [Mutex] per committed unit per storage directory, shared by every storage instance in the
 * process — what serializes [DownloaderStorage.commitResource] for one unit.
 *
 * Process-wide rather than a field of the storage, because the callers that race are not one
 * object: the group and the per-unit surface of an engine guard a unit with different mutexes, two
 * engines built over the same directory share nothing but the files, and a
 * [BackgroundResourceDownloader] may commit on its own while the engine commits too. Each of them
 * could see the unit unavailable and its complete temp file present, and both then committed: the
 * second one found its source already moved and failed with a spurious storage error, and on iOS
 * it first removed the destination the first one had just committed; two archive commits shared one
 * staging directory and wiped each other's extraction.
 *
 * Keyed by the storage's absolute base directory together with [DownloadUnit.id], so two storages
 * over different directories never wait for each other, and two over the same directory always do.
 * An entry lives only while a commit holds or awaits it, so the registry does not grow with the
 * catalogue. Kotlin/Native shares this object between threads; [entries] is updated by compare-and-set.
 */
internal object UnitCommitLocks {

    /**
     * One unit's slot. Immutable, replaced through [entries]'s compare-and-set: [users] commits hold
     * or await [mutex]; [detaching] marks a [tryWithUnitLock] that holds the unit for its O(1)
     * action, during which a commit of the unit waits on [entries] before it registers.
     */
    private class Entry(val mutex: Mutex, val users: Int, val detaching: Boolean)

    // A StateFlow of an immutable map instead of a Mutex over a mutable one: the non-suspending
    // tryWithUnitLock must examine and claim the registry without ever failing because other units'
    // commits are registering or leaving at that moment (a Mutex hands itself to a queued waiter on
    // unlock, so a tryLock on a busy registry fails for as long as anyone queues).
    private val entries: MutableStateFlow<Map<Pair<String, String>, Entry>> = MutableStateFlow(emptyMap())

    /** Runs [action] while no other commit of [unitId] under [baseDirectory] runs in this process. */
    suspend fun <T> withUnitLock(baseDirectory: String, unitId: String, action: suspend () -> T): T {
        val key: Pair<String, String> = baseDirectory to unitId
        val mutex: Mutex = register(key)
        try {
            return mutex.withLock { action() }
        } finally {
            // NonCancellable: a commit cancelled while waiting must still give its slot back, or the
            // entry would never be dropped.
            withContext(NonCancellable) {
                entries.update { current: Map<Pair<String, String>, Entry> ->
                    val entry: Entry = current.getValue(key)
                    if (entry.users <= 1) current - key else current + (key to Entry(entry.mutex, entry.users - 1, false))
                }
            }
        }
    }

    private suspend fun register(key: Pair<String, String>): Mutex {
        while (true) {
            var registered: Mutex? = null
            entries.update { current: Map<Pair<String, String>, Entry> ->
                val entry: Entry? = current[key]
                when {
                    entry == null -> {
                        val mutex = Mutex()
                        registered = mutex
                        current + (key to Entry(mutex, 1, false))
                    }

                    entry.detaching -> {
                        registered = null
                        current
                    }

                    else -> {
                        registered = entry.mutex
                        current + (key to Entry(entry.mutex, entry.users + 1, false))
                    }
                }
            }
            registered?.let { return it }
            // A detach holds the unit for an O(1) action; wait until it has finished.
            entries.first { current: Map<Pair<String, String>, Entry> -> current[key]?.detaching != true }
        }
    }

    /**
     * Runs [action] only when no commit of [unitId] under [baseDirectory] holds or awaits the unit,
     * and returns whether it ran. Never suspends and never waits, so a caller that cannot suspend
     * ([DownloaderStorage.deleteTempFile]) and that may itself hold another lock cannot deadlock
     * against a commit. `false` means exactly "a commit of this unit holds or awaits it, or another
     * call of this function holds it right now": other units' commits registering or leaving never
     * make it fail, so the answer is never "not now" for an unrelated reason.
     *
     * The unit is claimed with a [detaching] entry for the duration of [action], so a commit of the
     * unit that arrives meanwhile waits for it instead of starting beside it. [action] must be O(1)
     * and must not suspend; slow work belongs in [tryDetachThenDispose].
     */
    fun tryWithUnitLock(baseDirectory: String, unitId: String, action: () -> Unit): Boolean {
        val key: Pair<String, String> = baseDirectory to unitId
        var claimed = false
        entries.update { current: Map<Pair<String, String>, Entry> ->
            claimed = key !in current
            if (claimed) current + (key to Entry(Mutex(), 0, true)) else current
        }
        if (!claimed) return false
        try {
            action()
        } finally {
            entries.update { current: Map<Pair<String, String>, Entry> -> current - key }
        }
        return true
    }

    /**
     * [tryWithUnitLock] for slow cleanup: [detach] runs under the lock and must only make the
     * unit's data unreachable for commits (an atomic rename), returning a handle to it or null when
     * there was nothing; [dispose] then runs after the registry is released, so a recursive delete
     * never stalls other units' commits or makes their cancels skip. Returns whether [detach] ran.
     */
    fun <T : Any> tryDetachThenDispose(
        baseDirectory: String,
        unitId: String,
        detach: () -> T?,
        dispose: (T) -> Unit,
    ): Boolean {
        var detached: T? = null
        val ran: Boolean = tryWithUnitLock(baseDirectory, unitId) { detached = detach() }
        detached?.let(dispose)
        return ran
    }
}
