package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Names the scope inside which two engines contend for the same files: what a storage tells the
 * engine so that [UnitTransferLocks] can serialize transfers across engines over one directory.
 *
 * Implemented by the shipped storages, whose scope is their base directory; any other
 * [DownloaderStorage] is not locked across engines, since nothing is known about what it shares.
 */
internal interface TransferLockScope {
    /** The storage's absolute base directory: equal for every storage instance over one directory. */
    val transferLockScope: String

    /** The key a unit is serialized under within [transferLockScope]; see [UnitFileLayout.lockKey]. */
    fun transferLockKey(unit: DownloadUnit): String
}

/**
 * One [Mutex] per unit per storage scope, shared by every engine in the process: it makes the whole
 * transfer-and-commit of one unit exclusive across engines over the same directory.
 *
 * A second engine used to start its own transfer of a unit after the first engine's transfer had
 * finished but before the first had committed it. That transfer wrote a second, full-size partial
 * temp file nobody removed, and a downloader that follows the contract (it calls
 * [DownloaderStorage.beginTempFile] on a fresh start) deleted the complete file the other engine was
 * committing, failing that call with a storage error. Holding this lock from the re-check of the
 * unit to its commit makes the second engine wait and then find the resource available.
 *
 * Distinct from [UnitCommitLocks], which only serializes a commit and is taken inside the storage:
 * the same lock cannot be used here, since a [Mutex] is not reentrant and the commit runs inside this
 * one. Lock order is always: the engine's own unit mutex, then this lock, then the commit lock.
 *
 * An entry lives only while a call holds or awaits it, so the registry does not grow with the
 * catalogue. [entries] is an immutable map updated by compare-and-set, which works on Kotlin/Native.
 */
internal object UnitTransferLocks {

    private class Entry(val mutex: Mutex, val users: Int)

    private val entries: MutableStateFlow<Map<Pair<Any, String>, Entry>> = MutableStateFlow(emptyMap())

    /** Runs [action] while no other call holds the transfer lock of [unitKey] under [scope]. */
    suspend fun <T> withTransferLock(scope: Any, unitKey: String, action: suspend () -> T): T {
        val key: Pair<Any, String> = scope to unitKey
        var registered: Mutex? = null
        entries.update { current: Map<Pair<Any, String>, Entry> ->
            val entry: Entry? = current[key]
            val mutex: Mutex = entry?.mutex ?: Mutex()
            registered = mutex
            current + (key to Entry(mutex, (entry?.users ?: 0) + 1))
        }
        val mutex: Mutex = checkNotNull(registered)
        try {
            return mutex.withLock { action() }
        } finally {
            // NonCancellable: a call cancelled while waiting must still give its slot back.
            withContext(NonCancellable) {
                entries.update { current: Map<Pair<Any, String>, Entry> ->
                    val entry: Entry = current.getValue(key)
                    if (entry.users <= 1) current - key else current + (key to Entry(entry.mutex, entry.users - 1))
                }
            }
        }
    }
}
