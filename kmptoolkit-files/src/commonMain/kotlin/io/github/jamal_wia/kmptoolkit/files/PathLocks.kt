package io.github.jamal_wia.kmptoolkit.files

/**
 * A process-wide [KeyedMutex] over `PathKey.of(path, CaseFolding.Always)`, for read-modify-write cycles of one file
 * across unrelated parts of an app. Library modules never use it (they keep private [KeyedMutex] instances), so
 * holding it can never deadlock against a kmptoolkit call.
 *
 * @since 5.0.0
 */
public object PathLocks {
    private val mutex: KeyedMutex<String> = KeyedMutex()

    /** Runs [action] while no other [withLock] or [tryWithLock] of the same file runs; see [KeyedMutex.withLock]. */
    public suspend fun <T> withLock(path: String, action: suspend () -> T): T =
        mutex.withLock(PathKey.of(path, CaseFolding.Always), action)

    /** Runs [action] only if nobody holds or awaits the file; see [KeyedMutex.tryWithLock]. */
    public fun tryWithLock(path: String, action: () -> Unit): Boolean =
        mutex.tryWithLock(PathKey.of(path, CaseFolding.Always), action)
}
