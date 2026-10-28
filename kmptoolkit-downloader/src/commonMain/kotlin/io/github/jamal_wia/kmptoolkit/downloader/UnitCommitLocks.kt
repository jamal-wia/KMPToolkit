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
}
