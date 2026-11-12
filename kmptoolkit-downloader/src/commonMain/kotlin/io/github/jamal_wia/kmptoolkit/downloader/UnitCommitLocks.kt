package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.KeyedMutex

/**
 * One lock per committed unit per storage directory, shared by every storage instance in the
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
 * catalogue. The lock is a [KeyedMutex] of `kmptoolkit-files`, which is safe to share between threads on Kotlin/Native.
 */
internal object UnitCommitLocks {

    private val locks: KeyedMutex<Pair<String, String>> = KeyedMutex()

    /** Keys held or awaited right now; for leak checks in tests. */
    internal val activeKeyCount: Int get() = locks.activeKeyCount

    /** Runs [action] while no other commit of [unitId] under [baseDirectory] runs in this process. */
    suspend fun <T> withUnitLock(baseDirectory: String, unitId: String, action: suspend () -> T): T =
        locks.withLock(baseDirectory to unitId, action)

    /**
     * Runs [action] only when no commit of [unitId] under [baseDirectory] holds or awaits the unit,
     * and returns whether it ran. Never suspends and never waits, so a caller that cannot suspend
     * ([DownloaderStorage.deleteTempFile]) and that may itself hold another lock cannot deadlock
     * against a commit. `false` means exactly "a commit of this unit holds or awaits it, or another
     * call of this function holds it right now": other units' commits registering or leaving never
     * make it fail, so the answer is never "not now" for an unrelated reason.
     *
     * A commit of the unit that arrives while [action] runs waits for it instead of starting beside
     * it. [action] must be O(1) and must not suspend; slow work belongs in [tryDetachThenDispose].
     */
    fun tryWithUnitLock(baseDirectory: String, unitId: String, action: () -> Unit): Boolean =
        locks.tryWithLock(baseDirectory to unitId, action)

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
