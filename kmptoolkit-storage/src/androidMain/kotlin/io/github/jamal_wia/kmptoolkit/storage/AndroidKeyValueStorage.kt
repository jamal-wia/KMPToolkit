package io.github.jamal_wia.kmptoolkit.storage

import android.content.SharedPreferences

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
 * few hundred bytes; this module is for a handful of small values, not a working set.
 */
internal class AndroidKeyValueStorage(
    private val preferences: SharedPreferences,
) : KeyValueStorage {

    override fun get(key: String): StorageResult<String?> = try {
        StorageResult.Success(preferences.getString(key, null))
    } catch (error: ClassCastException) {
        // The file holds this key with a non-String type — another component wrote it, or the app
        // used to. Not recoverable by reading again, and not a value this store can return.
        StorageResult.Failure(
            StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error),
        )
    }

    override fun put(key: String, value: String): StorageResult<Unit> =
        commit(StorageOperation.PUT, key) { it.putString(key, value) }

    override fun remove(key: String): StorageResult<Unit> =
        commit(StorageOperation.REMOVE, key) { it.remove(key) }

    override fun clear(): StorageResult<Unit> =
        commit(StorageOperation.CLEAR, key = null) { it.clear() }

    private inline fun commit(
        operation: StorageOperation,
        key: String?,
        edit: (SharedPreferences.Editor) -> SharedPreferences.Editor,
    ): StorageResult<Unit> {
        val committed: Boolean = try {
            edit(preferences.edit()).commit()
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
}
