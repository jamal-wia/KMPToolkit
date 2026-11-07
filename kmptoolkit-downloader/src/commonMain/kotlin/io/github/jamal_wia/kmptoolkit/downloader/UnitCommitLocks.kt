package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.coroutines.NonCancellable
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
 * catalogue. Kotlin/Native shares this object between threads; [registryLock] guards the map.
 */
internal object UnitCommitLocks {

    private class Entry {
        val mutex: Mutex = Mutex()

        /** Commits holding or awaiting [mutex]; the entry is dropped when it returns to zero. */
        var users: Int = 0
    }

    private val registryLock: Mutex = Mutex()
    private val entries: MutableMap<Pair<String, String>, Entry> = mutableMapOf()

    /** Runs [action] while no other commit of [unitId] under [baseDirectory] runs in this process. */
    suspend fun <T> withUnitLock(baseDirectory: String, unitId: String, action: suspend () -> T): T {
        val key: Pair<String, String> = baseDirectory to unitId
        val entry: Entry = registryLock.withLock {
            entries.getOrPut(key) { Entry() }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { action() }
        } finally {
            // NonCancellable: a commit cancelled while waiting must still give its slot back, or the
            // entry would never be dropped.
            withContext(NonCancellable) {
                registryLock.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(key)
                }
            }
        }
    }

    /**
     * Runs [action] only when no commit of [unitId] under [baseDirectory] holds or awaits the unit,
     * and returns whether it ran. Never suspends and never waits, so a caller that cannot suspend
     * ([DownloaderStorage.deleteTempFile]) and that may itself hold another lock cannot deadlock
     * against a commit. It also returns false when the registry is merely busy for a moment (a
     * commit of any unit registering or leaving, another call of this function), so a `false`
     * means "not now", never "nothing to do".
     *
     * A commit registers its entry under [registryLock], so holding the registry for the whole of
     * [action] keeps every commit of every unit from starting until it returns: the unit is
     * excluded without registering an entry of its own (which could not be removed again without
     * suspending). [action] must therefore be O(1) and must not suspend; slow work belongs in
     * [tryDetachThenDispose].
     */
    fun tryWithUnitLock(baseDirectory: String, unitId: String, action: () -> Unit): Boolean {
        if (!registryLock.tryLock()) return false
        try {
            if ((baseDirectory to unitId) in entries) return false
            action()
            return true
        } finally {
            registryLock.unlock()
        }
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
