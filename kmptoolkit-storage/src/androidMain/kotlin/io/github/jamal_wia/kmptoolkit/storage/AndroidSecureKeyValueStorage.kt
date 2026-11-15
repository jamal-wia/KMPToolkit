package io.github.jamal_wia.kmptoolkit.storage

import android.content.SharedPreferences
import java.util.Collections
import java.util.WeakHashMap
import javax.crypto.BadPaddingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 *
 * ### The envelope format and the one-time migration
 *
 * Every entry is a versioned envelope (see [KeystoreValueCipher]). Entries written before the version byte existed
 * (4.0.0 and 5.0.0) are read only while the file carries no [FORMAT_KEY] stamp. The first operation that can resolve
 * the key then runs a one-time migration pass: every readable legacy entry is re-encrypted into the versioned format
 * with compare-and-set, and only when the whole pass succeeded is the stamp written. From then on an unversioned
 * entry is [StorageError.Undecryptable], which closes the old fallback that accepted unbound entries for ever. A
 * fresh store is stamped by its first write.
 *
 * ### Locks
 *
 * Reads take no lock. Every write (and the migration's compare-and-set) takes the file's [StoreLocks.file] mutex around
 * the `commit()` only; the encryption happens before it, off the lock. The migration pass additionally holds the
 * file's [StoreLocks.migration] mutex, taken with `tryLock`: an operation that finds a pass running does not wait for
 * it, it reads legacy entries itself and writes versioned ones. The lock order is migration, then file; neither is
 * ever taken inside the key's alias lock, and neither is held while a callback into foreign code runs.
 */
internal class AndroidSecureKeyValueStorage(
    private val preferences: SharedPreferences,
    private val cipher: ValueCipher,
    private val dispatcher: CoroutineDispatcher,
) : SecureKeyValueStorage {

    /** The Android Keystore holds the key and the app's UID sandbox keeps everything else out. */
    override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED

    private val locks: StoreLocks = storeLocks(preferences)

    /**
     * Provisional: resolves (on a first run, creates) the Keystore key and runs one cipher round, which
     * is what the first `put` would do, without touching the stored entries. Same cancellation and error
     * idiom as [get] and [put]; the full contract of this call on Android is defined by the Android
     * platform group.
     */
    override suspend fun ensureKey(): StorageResult<Unit> = withContext(dispatcher) {
        try {
            cipher.encrypt("")
            StorageResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: StorageKeyUnavailableException) {
            StorageResult.Failure(StorageError.Unavailable(error.cause ?: error))
        } catch (error: Exception) {
            if (isTransientKeystoreFailure(error)) {
                StorageResult.Failure(StorageError.Unavailable(error))
            } else {
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.ENSURE_KEY, cause = error))
            }
        }
    }

    override suspend fun get(key: String): StorageResult<String?> = withContext(dispatcher) {
        if (key == FORMAT_KEY) return@withContext StorageResult.Success(null)
        // Decided before the entry is read: an entry that was legacy when it was read stays readable even if a pass
        // (this call's, or a concurrent one) stamps the file before it is decrypted.
        val legacyAllowed: Boolean = !isStamped()
        val stored: String = try {
            preferences.getString(key, null)
        } catch (error: ClassCastException) {
            return@withContext StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.GET, key = key, cause = error),
            )
        } ?: return@withContext StorageResult.Success(null)

        migrateIfPending()
        try {
            StorageResult.Success(read(key, stored, legacyAllowed))
        } catch (cancellation: CancellationException) {
            // The wait for the key was cancelled, not the value lost — never report it as one.
            throw cancellation
        } catch (error: StorageKeyUnavailableException) {
            StorageResult.Failure(StorageError.Unavailable(error.cause ?: error))
        } catch (error: Exception) {
            // A keystore fault the platform itself calls transient (API 33+) is not a lost value:
            // the same bytes can decrypt a moment later, so it must not read as "discard it".
            if (isTransientKeystoreFailure(error)) {
                return@withContext StorageResult.Failure(StorageError.Unavailable(error))
            }
            // Everything else means these particular bytes cannot be turned back into a value:
            // truncated or hand-edited Base64, a tag that fails to authenticate, a key the platform
            // destroyed. The entry is not coming back; say so rather than returning null, which a
            // caller would read as "never written" and silently re-login over.
            StorageResult.Failure(StorageError.Undecryptable(key, error))
        }
    }

    override suspend fun put(key: String, value: String): StorageResult<Unit> = withContext(dispatcher) {
        if (key == FORMAT_KEY) return@withContext StorageResult.Failure(StorageError.InvalidValue(key))
        unpairedSurrogateFailure(key, value)?.let { return@withContext it }
        migrateIfPending()
        val encrypted: String = try {
            cipher.encryptFor(key, value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: StorageKeyUnavailableException) {
            return@withContext StorageResult.Failure(StorageError.Unavailable(error.cause ?: error))
        } catch (error: Exception) {
            return@withContext StorageResult.Failure(
                StorageError.OperationFailed(StorageOperation.PUT, key = key, cause = error),
            )
        }
        locks.file.withLock {
            preferences.commitEdit(StorageOperation.PUT, key) { editor: SharedPreferences.Editor ->
                editor.putString(key, encrypted)
                // The first write of a store with no other entry starts it in the versioned format; a store that may
                // still hold legacy entries is stamped only by the migration pass that rewrote them.
                if (!isStamped() && preferences.all.keys.none { other: String -> other != key }) {
                    editor.putString(FORMAT_KEY, FORMAT_STAMP)
                }
                editor
            }
        }
    }

    override suspend fun remove(key: String): StorageResult<Unit> = withContext(dispatcher) {
        if (key == FORMAT_KEY) return@withContext StorageResult.Success(Unit)
        locks.file.withLock { preferences.commitEdit(StorageOperation.REMOVE, key) { it.remove(key) } }
    }

    override suspend fun clear(): StorageResult<Unit> = withContext(dispatcher) {
        locks.file.withLock {
            preferences.commitEdit(StorageOperation.CLEAR, key = null) { editor: SharedPreferences.Editor ->
                val stamped: Boolean = isStamped()
                editor.clear()
                // The stamp is the file's format, not an entry: clear() empties the store and keeps it migrated.
                if (stamped) editor.putString(FORMAT_KEY, FORMAT_STAMP) else editor
            }
        }
    }

    private fun isStamped(): Boolean = preferences.contains(FORMAT_KEY)

    /** A versioned entry, or, when [legacyAllowed] (the file was not stamped), a legacy one. */
    private suspend fun read(key: String, stored: String, legacyAllowed: Boolean): String = try {
        cipher.decryptFor(key, stored)
    } catch (versioned: BadPaddingException) {
        if (!legacyAllowed) throw versioned
        cipher.decryptLegacyFor(key, stored)
    }

    /**
     * Runs the one-time migration pass when the file is not stamped and no other pass is running. Never fails the
     * operation that triggered it: whatever stops the pass leaves the file unstamped and the next operation retries.
     */
    private suspend fun migrateIfPending() {
        if (isStamped() || !locks.migration.tryLock()) return
        try {
            if (!isStamped()) migrate()
        } finally {
            locks.migration.unlock()
        }
    }

    /**
     * Re-encrypts every legacy entry into the versioned format, then stamps the file.
     *
     * Per entry: read the stored text, decrypt and re-encrypt it off every lock (slow, and it may wait for the key),
     * then under the file lock write the new text only if the entry still holds the text that was read (the
     * compare-and-set of the plan, S19): a write that landed meanwhile, such as a background token refresh, is never
     * overwritten with the old value, and a removal is never undone.
     *
     * An entry that fails its tag or is malformed is skipped (it was lost already, and stays [StorageError.Undecryptable]).
     * Any other failure, the key store being unavailable or a fault that may pass, stops the pass without stamping,
     * so that no readable legacy entry is ever cut off from its reader.
     */
    private suspend fun migrate() {
        val snapshot: Map<String, String> = buildMap {
            for ((name: String, value: Any?) in preferences.all) {
                if (name != FORMAT_KEY && value is String) put(name, value)
            }
        }
        if (snapshot.isEmpty()) return
        for ((name: String, stored: String) in snapshot) {
            val plaintext: String = try {
                try {
                    cipher.decryptFor(name, stored)
                    continue // already versioned
                } catch (_: BadPaddingException) {
                    cipher.decryptLegacyFor(name, stored)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: BadPaddingException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: Exception) {
                return
            }
            val renewed: String = try {
                cipher.encryptFor(name, plaintext)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return
            }
            val committed: Boolean = locks.file.withLock {
                val current: String? = try {
                    preferences.getString(name, null)
                } catch (_: ClassCastException) {
                    null // another component replaced the entry with another type: not ours to rewrite
                }
                if (current != stored) {
                    true // changed or removed since it was read: the newer state wins
                } else {
                    preferences.edit().putString(name, renewed).commit()
                }
            }
            if (!committed) return
        }
        locks.file.withLock { preferences.edit().putString(FORMAT_KEY, FORMAT_STAMP).commit() }
    }

    internal companion object {
        /**
         * The reserved name under which the file records that it is in the versioned format. A key of this exact
         * name cannot be stored: a put is [StorageError.InvalidValue], a get finds nothing.
         */
        const val FORMAT_KEY: String = ".kmptoolkit.envelope-format"

        private const val FORMAT_STAMP: String = "1"
    }
}

/** The two locks of one preferences file, shared by every store instance over it in the process. */
internal class StoreLocks {
    /** Serializes the commit of every write, so that the migration's compare-and-set is atomic against them. */
    val file: Mutex = Mutex()

    /** Held by the one migration pass at a time; taken with `tryLock`, never waited for. */
    val migration: Mutex = Mutex()
}

/**
 * Keyed by the `SharedPreferences` object, which the framework hands out once per file and process. Weak keys: the map
 * never keeps a preferences object (or a test's) alive.
 */
private val storeLocksRegistry: MutableMap<SharedPreferences, StoreLocks> =
    Collections.synchronizedMap(WeakHashMap())

private fun storeLocks(preferences: SharedPreferences): StoreLocks =
    synchronized(storeLocksRegistry) { storeLocksRegistry.getOrPut(preferences) { StoreLocks() } }
