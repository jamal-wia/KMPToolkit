package io.github.jamal_wia.kmptoolkit.storage

import android.content.SharedPreferences

/**
 * [SecureKeyValueStorage] over a `SharedPreferences` file whose *values* pass through a
 * [ValueCipher] on the way in and out.
 *
 * Key names are stored in the clear. A key name is a program constant — `"access_token"` — not a
 * secret, and encrypting it would only buy deterministic ciphertext that leaks equality anyway.
 * The values are the secrets and they are the thing that is encrypted.
 *
 * Kept in a different file from [AndroidKeyValueStorage] (see [secureStoreId]) so a bug in one code
 * path cannot read or overwrite the other's data.
 */
internal class AndroidSecureKeyValueStorage(
    private val preferences: SharedPreferences,
    private val cipher: ValueCipher,
) : SecureKeyValueStorage {

    override fun get(key: String): StorageResult<String?> {
        val stored: String = try {
            preferences.getString(key, null)
        } catch (error: ClassCastException) {
            return StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error),
            )
        } ?: return StorageResult.Success(null)

        return try {
            StorageResult.Success(cipher.decrypt(stored))
        } catch (error: StorageKeyUnavailableException) {
            StorageResult.Failure(StorageError.Unavailable(error.cause ?: error))
        } catch (error: Exception) {
            // Everything else means these particular bytes cannot be turned back into a value:
            // truncated or hand-edited Base64, a tag that fails to authenticate, a key the platform
            // destroyed. The entry is not coming back; say so rather than returning null, which a
            // caller would read as "never written" and silently re-login over.
            StorageResult.Failure(StorageError.Undecryptable(key, error))
        }
    }

    override fun put(key: String, value: String): StorageResult<Unit> {
        val encrypted: String = try {
            cipher.encrypt(value)
        } catch (error: StorageKeyUnavailableException) {
            return StorageResult.Failure(StorageError.Unavailable(error.cause ?: error))
        } catch (error: Exception) {
            return StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.PUT, key = key, cause = error),
            )
        }
        return commit(StorageOperation.PUT, key) { it.putString(key, encrypted) }
    }

    override fun remove(key: String): StorageResult<Unit> =
        commit(StorageOperation.REMOVE, key) { it.remove(key) }

    /**
     * Empties the file. The AndroidKeyStore key itself is deliberately left in place: it is shared
     * by every entry in this store, deleting it would be indistinguishable from the invalidation
     * this module treats as data loss, and an empty file is already unreadable to anyone.
     */
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
