package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.KeyedMutex

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
 * One lock per unit per storage scope, shared by every engine in the process: it makes the whole
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
 * the same lock cannot be used here, since a lock is not reentrant and the commit runs inside this
 * one. Lock order is always: the engine's own unit mutex, then this lock, then the commit lock.
 *
 * An entry lives only while a call holds or awaits it, so the registry does not grow with the
 * catalogue. The lock is a [KeyedMutex] of `kmptoolkit-files`, which works on Kotlin/Native.
 */
internal object UnitTransferLocks {

    private val locks: KeyedMutex<Pair<String, String>> = KeyedMutex()

    /** Keys held or awaited right now; for leak checks in tests. */
    internal val activeKeyCount: Int get() = locks.activeKeyCount

    /** Runs [action] while no other call holds the transfer lock of [unitKey] under [scope]. */
    suspend fun <T> withTransferLock(scope: String, unitKey: String, action: suspend () -> T): T =
        locks.withLock(scope to unitKey, action)

    /**
     * Runs [action] only if no call holds or awaits the transfer lock of [unitKey] under [scope] right
     * now, and says whether it ran. Never waits, so it can be used where nothing may suspend (a
     * cancel). A caller that finds the lock held leaves the unit's files alone: they belong to the
     * transfer in flight, or to one that is about to start.
     */
    fun tryWithTransferLock(scope: String, unitKey: String, action: () -> Unit): Boolean =
        locks.tryWithLock(scope to unitKey, action)
}
