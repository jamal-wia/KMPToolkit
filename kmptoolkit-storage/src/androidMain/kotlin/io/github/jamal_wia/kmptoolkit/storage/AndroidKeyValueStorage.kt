package io.github.jamal_wia.kmptoolkit.storage

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [KeyValueStorage] over a single `SharedPreferences` file.
 *
 * The file is the store: `clear()` empties that file and nothing else, so a second store built from
 * a different [StorageConfig] is untouched.
 *
 * Writes use `commit()` rather than `apply()`. `apply()` returns before the write reaches disk,
 * which would quietly break the durability half of [KeyValueStorage]'s contract — a token written
 * immediately before the process is killed would be gone — and it has no way to report a failure,
 * which would leave [StorageError.OperationFailed] unreachable. The cost is a blocking write of a
 * few hundred bytes, run on [dispatcher] rather than the caller's thread; this module is for a
 * handful of small values, not a working set.
 *
 * The first read of a file blocks until `SharedPreferences` has loaded it from disk, which is why
 * reads move to [dispatcher] too.
 */
internal class AndroidKeyValueStorage(
    private val preferences: SharedPreferences,
    private val dispatcher: CoroutineDispatcher,
) : KeyValueStorage {

    override suspend fun get(key: String): StorageResult<String?> = withContext(dispatcher) {
        try {
            StorageResult.Success(preferences.getString(key, null))
        } catch (error: ClassCastException) {
            // The file holds this key with a non-String type — another component wrote it, or the
            // app used to. Not recoverable by reading again, and not a value this store can return.
            StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error),
            )
        }
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> =
        commit(StorageOperation.PUT, key) { it.putString(key, value) }

    override suspend fun remove(key: String): StorageResult<Unit> =
        commit(StorageOperation.REMOVE, key) { it.remove(key) }

    override suspend fun clear(): StorageResult<Unit> =
        commit(StorageOperation.CLEAR, key = null) { it.clear() }

    private suspend fun commit(
        operation: StorageOperation,
        key: String?,
        edit: (SharedPreferences.Editor) -> SharedPreferences.Editor,
    ): StorageResult<Unit> = withContext(dispatcher) {
        preferences.commitEdit(operation, key, edit)
    }
}

/**
 * Applies [change] and writes it through with `commit()`, mapping both ways a commit can fail onto
 * [StorageError.OperationFailed]. Shared by both Android stores, which differ only in what they put.
 */
internal inline fun SharedPreferences.commitEdit(
    operation: StorageOperation,
    key: String?,
    change: (SharedPreferences.Editor) -> SharedPreferences.Editor,
): StorageResult<Unit> {
    val committed: Boolean = try {
        change(edit()).commit()
    } catch (error: RuntimeException) {
        return StorageResult.Failure(
            StorageError.OperationFailed(operation, key = key, cause = error),
        )
    }
    return if (committed) {
        StorageResult.Success(Unit)
    } else {
        StorageResult.Failure(StorageError.OperationFailed(operation, key = key))
    }
}
