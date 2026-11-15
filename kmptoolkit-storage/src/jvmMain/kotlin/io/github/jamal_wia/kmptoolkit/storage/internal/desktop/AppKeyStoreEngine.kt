package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.nio.CharBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher

/** The key-encryption key derived off the lock, and what it was derived for. */
private class KekCarry(
    /** The key object whose provider supplied the secret this KEK comes from. */
    val forKey: AppEncryptionKey,
    /** The file it opens ([Request.Existing]) or `null` for a NEW file ([Request.Fresh]). */
    val snapshot: Snapshot?,
    val kek: ByteArray,
    /** Fresh only: the salt and iteration count the KEK was derived with (passphrase keys). */
    val salt: ByteArray?,
    val iterations: Int,
) : Carry {
    override fun wipe() {
        kek.fill(0)
    }
}

/** What a provider returned, validated: owned by the scope until [wipe]. */
private class Secret(val forKey: AppEncryptionKey, val chars: CharArray?, val bytes: ByteArray?) {
    fun wipe() {
        chars?.fill(' ')
        bytes?.fill(0)
    }
}

/**
 * The provider's secret of ONE operation: the provider is called at most once per operation (plan 4.8), however many
 * rounds the operation needs, and the secret is wiped when the operation ends. (A different key object, after a
 * `changeKey` of this instance in the middle of the operation, is a different provider and is asked again.)
 */
private class SecretScope : OpScope {
    private var held: Secret? = null

    /** The validated secret of [key], or the typed failure; a provider that throws is `Unavailable`, cancellation propagates. */
    suspend fun obtain(key: AppEncryptionKey): Pair<Secret?, StorageError?> {
        held?.let { current: Secret ->
            if (current.forKey === key) return current to null
            current.wipe()
            held = null
        }
        val secret: Secret = when (key) {
            is AppEncryptionKey.Passphrase -> {
                val chars: CharArray = try {
                    key.provider()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return null to StorageError.Unavailable(error)
                }
                if (chars.isEmpty()) return null to refuse(chars, null, "an empty passphrase has no key")
                try {
                    CharBuffer.wrap(chars).toStrictUtf8().fill(0)
                } catch (_: IllegalArgumentException) {
                    return null to refuse(chars, null, "the passphrase has an unpaired UTF-16 surrogate")
                }
                Secret(key, chars, null)
            }
            is AppEncryptionKey.Raw -> {
                val bytes: ByteArray = try {
                    key.provider()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return null to StorageError.Unavailable(error)
                }
                if (bytes.size != SecureFileFormat.DATA_KEY_BYTES) return null to refuse(null, bytes, "a raw key is exactly 32 bytes")
                Secret(key, null, bytes)
            }
        }
        held = secret
        return secret to null
    }

    /** A key was supplied and cannot work: `AccessDenied`, the cause never contains the secret. */
    private fun refuse(chars: CharArray?, bytes: ByteArray?, reason: String): StorageError {
        chars?.fill(' ')
        bytes?.fill(0)
        return StorageError.AccessDenied(cause = IllegalArgumentException(reason))
    }

    override fun wipe() {
        held?.wipe()
        held = null
    }
}

/** The data key of this instance, valid only for the header bytes it was unwrapped from (plan 4.8). */
private class CachedKey(val headerPrefix: ByteArray, val dataKey: ByteArray)

/**
 * The key lifecycle of the app-key store (plan 4.8, kinds 3 and 4): no OS call, no binding, no `Lost` state; a wrong key is
 * `AccessDenied` on every operation, including a `get` of an absent key and `clear()`.
 *
 * - **The provider and PBKDF2 run OFF the lock.** The round that needs the key says so ([Request]), the engine releases
 *   both locks, calls the provider once, derives the KEK (up to 10 000 000 iterations: it never eats another caller's
 *   10 s budget) and comes back; the next round re-reads the file and uses the KEK only if the header and key-check are
 *   the ones it was derived for.
 * - **The cache is per instance and bound to the exact header bytes**; it is only touched inside the store locks. A header
 *   that differs drops (and wipes) the entry, so an instance that holds the old key gets `AccessDenied` after another
 *   instance's `changeKey`.
 * - **`changeKey`** verifies the current key, asks the new provider and derives its KEK off the lock, then rewrites the
 *   whole file under one atomic replace; the instance binds to the new key in the same lock hold.
 */
internal class AppKeyStoreEngine(
    guard: EncryptedFileGuard,
    dispatcher: CoroutineDispatcher,
    cipher: EnvelopeCipher,
    initialKey: AppEncryptionKey,
) : EncryptedStoreEngine(guard, dispatcher, cipher) {

    /** The key this instance is bound to; changed only by `changeKey`, under the store locks. */
    @Volatile
    private var key: AppEncryptionKey = initialKey

    /** Touched only inside the store locks. */
    private var cached: CachedKey? = null

    val protection: SecureStorageProtection
        get() = if (key is AppEncryptionKey.Passphrase) SecureStorageProtection.PASSPHRASE else SecureStorageProtection.APP_PROVIDED_KEY

    // Both kinds belong to this store type; a file of the other kind than the bound key is a key that cannot open it (AccessDenied).
    override val kinds: Set<StoreKind> = setOf(StoreKind.PASSPHRASE, StoreKind.RAW_KEY)

    override val clearNeedsKey: Boolean = true

    override fun unavailableReason(): StorageError? = null

    override fun roundsExhausted(): StorageError = StorageError.Unavailable()

    override fun newScope(): OpScope = SecretScope()

    override fun newFileKind(): StoreKind = kindOf(key)

    private fun kindOf(key: AppEncryptionKey): StoreKind =
        if (key is AppEncryptionKey.Passphrase) StoreKind.PASSPHRASE else StoreKind.RAW_KEY

    private fun adopt(headerPrefix: ByteArray, dataKey: ByteArray) {
        cached?.dataKey?.fill(0)
        cached = CachedKey(headerPrefix.copyOf(), dataKey.copyOf())
    }

    private fun unwrap(header: HeaderBody, kek: ByteArray): ByteArray? = when (header) {
        is HeaderBody.Passphrase ->
            cipher.unwrapDataKey(kek, header.wrappedKey, SecureFileFormat.wrapAad(StoreKind.PASSPHRASE, header.beforeWrappedKey()))
        is HeaderBody.RawKey ->
            cipher.unwrapDataKey(kek, header.wrappedKey, SecureFileFormat.wrapAad(StoreKind.RAW_KEY, header.beforeWrappedKey()))
        else -> null
    }

    override fun resolve(store: EncryptedFileGuard.StoreFile, parsed: FileState.Parsed, carry: Carry?): Resolution {
        val file: ParsedStoreFile = parsed.file
        if (file.kind != kindOf(key)) {
            // A passphrase store opened with a raw key, or the other way round: the supplied key cannot open it, and
            // nothing is read, moved or changed.
            return Resolution.Fail(StorageError.AccessDenied(cause = IllegalArgumentException("The key type does not open this store")))
        }
        val current: CachedKey? = cached
        if (current != null) {
            if (current.headerPrefix.contentEquals(file.headerPrefix)) {
                // The cached key is for exactly this header; the key-check still has to hold for this file.
                return if (cipher.verifyKeyCheck(current.dataKey, file.headerPrefix, file.keyCheck)) {
                    Resolution.Have(current.dataKey)
                } else {
                    Resolution.Fail(StorageError.Undecryptable(null))
                }
            }
            // Any difference in the header drops the entry (another instance changed the key).
            current.dataKey.fill(0)
            cached = null
        }
        val derived: KekCarry? = carry as? KekCarry
        if (derived?.snapshot != null && derived.snapshot.matches(parsed)) {
            // An unwrap failure is the wrong key (never Undecryptable); an unwrap that works with a failing key-check is corruption.
            val dataKey: ByteArray = unwrap(file.header, derived.kek)
                ?: return Resolution.Fail(StorageError.AccessDenied(cause = IllegalArgumentException("The key does not open this store")))
            try {
                // verifyKeyCheck throws for a failing crypto provider: the unwrapped key is wiped on that path too.
                if (!cipher.verifyKeyCheck(dataKey, file.headerPrefix, file.keyCheck)) return Resolution.Fail(StorageError.Undecryptable(null))
                adopt(file.headerPrefix, dataKey)
            } finally {
                dataKey.fill(0)
            }
            return Resolution.Have(checkNotNull(cached).dataKey)
        }
        return Resolution.Need(Request.Existing(Snapshot.of(parsed)))
    }

    override suspend fun prepare(request: Request, op: Op, scope: OpScope): Prepared =
        derive(request, scope as SecretScope, key, op.operation, op.key)

    private suspend fun derive(request: Request, scope: SecretScope, forKey: AppEncryptionKey, operation: StorageOperation, entryKey: String?): Prepared {
        val (secret: Secret?, refusal: StorageError?) = scope.obtain(forKey)
        if (secret == null) return Prepared.Failed(checkNotNull(refusal))
        return try {
            when (request) {
                is Request.Existing -> {
                    val header: HeaderBody = request.snapshot.header
                    when {
                        secret.chars != null && header is HeaderBody.Passphrase ->
                            Prepared.Ready(KekCarry(forKey, request.snapshot, Pbkdf2.deriveKek(secret.chars, header.salt, header.iterations.toInt()), null, 0))
                        secret.bytes != null && header is HeaderBody.RawKey ->
                            Prepared.Ready(KekCarry(forKey, request.snapshot, secret.bytes.copyOf(), null, 0))
                        // The file is of the other kind than this key: not a file this key can open.
                        else -> Prepared.Failed(StorageError.AccessDenied(cause = IllegalArgumentException("The key does not open this store")))
                    }
                }
                Request.Fresh -> when {
                    secret.chars != null -> {
                        val iterations: Int = (forKey as AppEncryptionKey.Passphrase).iterations
                        val salt: ByteArray = cipher.newSalt()
                        Prepared.Ready(KekCarry(forKey, null, Pbkdf2.deriveKek(secret.chars, salt, iterations), salt, iterations))
                    }
                    else -> Prepared.Ready(KekCarry(forKey, null, checkNotNull(secret.bytes).copyOf(), null, 0))
                }
            }
        } catch (error: IllegalArgumentException) {
            Prepared.Failed(StorageError.AccessDenied(cause = error))
        } catch (error: IllegalStateException) {
            // The JDK has no PBKDF2 (or its key derivation failed): an environment problem. The passphrase is not wrong, so
            // never AccessDenied, and nothing was read or written yet.
            Prepared.Failed(StorageError.OperationFailed(operation, key = entryKey, cause = error))
        }
    }

    /** The header, key-check and data key of a NEW file wrapped by [carry]'s KEK. The caller wipes [NewKey.dataKey]. */
    private fun newFile(forKey: AppEncryptionKey, carry: KekCarry): Triple<HeaderBody, ByteArray, ByteArray> {
        val dataKey: ByteArray = cipher.newDataKey()
        try {
            val header: HeaderBody = if (forKey is AppEncryptionKey.Passphrase) {
                val salt: ByteArray = checkNotNull(carry.salt)
                val before: ByteArray = HeaderBody.Passphrase(salt, carry.iterations.toLong(), ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES)).beforeWrappedKey()
                val wrapped: ByteArray = cipher.wrapDataKey(carry.kek, dataKey, SecureFileFormat.wrapAad(StoreKind.PASSPHRASE, before))
                HeaderBody.Passphrase(salt, carry.iterations.toLong(), wrapped)
            } else {
                val wrapped: ByteArray = cipher.wrapDataKey(carry.kek, dataKey, SecureFileFormat.wrapAad(StoreKind.RAW_KEY, ByteArray(0)))
                HeaderBody.RawKey(wrapped)
            }
            val keyCheck: ByteArray = cipher.sealKeyCheck(dataKey, SecureFileFormat.headerPrefix(header))
            return Triple(header, keyCheck, dataKey)
        } catch (error: Throwable) {
            // Wrapping or sealing failed (a provider failure): the caller never receives the key, so nobody else would wipe it.
            dataKey.fill(0)
            throw error
        }
    }

    override suspend fun createPlan(store: EncryptedFileGuard.StoreFile, op: Op, carry: Carry?): CreatePlan {
        // A KEK derived for another key object (changeKey of this instance meanwhile) must never wrap a file of this one.
        val bound: AppEncryptionKey = key
        val fresh: KekCarry = (carry as? KekCarry)?.takeIf { it.snapshot == null && it.forKey === bound } ?: return CreatePlan.Need(Request.Fresh)
        val (header: HeaderBody, keyCheck: ByteArray, dataKey: ByteArray) = newFile(bound, fresh)
        return CreatePlan.Ready(
            header = header,
            keyCheck = keyCheck,
            dataKey = dataKey,
            install = { null },
            commit = { adopt(SecureFileFormat.headerPrefix(header), dataKey) },
            dispose = { dataKey.fill(0) },
        )
    }

    // ---- changeKey ----

    private sealed interface ChangeStep {
        class Done(val result: StorageResult<Unit>) : ChangeStep
        class NeedCurrent(val request: Request) : ChangeStep
        data object NeedNew : ChangeStep
    }

    /**
     * Verifies the current key, re-encrypts every value under a fresh data key wrapped by [newKey] with a new salt, and
     * replaces the file atomically; this instance is bound to [newKey] afterwards. An absent file has nothing to re-encrypt:
     * the instance is simply bound to [newKey] and the new provider is asked on the first write.
     */
    suspend fun changeKey(newKey: AppEncryptionKey): StorageResult<Unit> = withStoreContext(dispatcher) {
        val currentScope = SecretScope()
        // Re-keying with the key object this instance already uses (a new salt under the same passphrase) is one operation
        // with one provider: both derivations share the scope, so the provider is asked once (plan 4.8).
        val newScope: SecretScope = if (newKey === key) currentScope else SecretScope()
        var currentCarry: Carry? = null
        var newCarry: Carry? = null
        try {
            repeat(MAX_ROUNDS) {
                when (val locked: StorageResult<ChangeStep> = guard.locked(StorageOperation.CHANGE_KEY, null) { store: EncryptedFileGuard.StoreFile -> changeRound(store, newKey, currentCarry, newCarry) }) {
                    is StorageResult.Failure -> return@withStoreContext locked
                    is StorageResult.Success -> when (val step: ChangeStep = locked.value) {
                        is ChangeStep.Done -> return@withStoreContext step.result
                        is ChangeStep.NeedCurrent -> {
                            currentCarry?.wipe()
                            currentCarry = null
                            when (val prepared: Prepared = derive(step.request, currentScope, key, StorageOperation.CHANGE_KEY, null)) {
                                is Prepared.Ready -> currentCarry = prepared.carry
                                is Prepared.Failed -> return@withStoreContext StorageResult.Failure(prepared.error)
                            }
                        }
                        ChangeStep.NeedNew -> {
                            newCarry?.wipe()
                            newCarry = null
                            when (val prepared: Prepared = derive(Request.Fresh, newScope, newKey, StorageOperation.CHANGE_KEY, null)) {
                                is Prepared.Ready -> newCarry = prepared.carry
                                // The current key verified; the NEW key is what cannot work.
                                is Prepared.Failed -> return@withStoreContext StorageResult.Failure(prepared.error)
                            }
                        }
                    }
                }
            }
            StorageResult.Failure(roundsExhausted())
        } finally {
            currentCarry?.wipe()
            newCarry?.wipe()
            currentScope.wipe()
            newScope.wipe()
        }
    }

    private fun changeRound(store: EncryptedFileGuard.StoreFile, newKey: AppEncryptionKey, currentCarry: Carry?, newCarry: Carry?): ChangeStep {
        val state: FileState = store.classify(kinds)
        if (state is FileState.Absent) {
            bind(newKey)
            return ChangeStep.Done(StorageResult.Success(Unit))
        }
        if (state is FileState.Unparsable) return ChangeStep.Done(StorageResult.Failure(StorageError.Undecryptable(null)))
        state as FileState.Parsed
        val file: ParsedStoreFile = state.file
        val oldKey: ByteArray = when (val resolution: Resolution = resolve(store, state, currentCarry)) {
            is Resolution.Need -> return ChangeStep.NeedCurrent(resolution.request)
            is Resolution.Fail -> return ChangeStep.Done(StorageResult.Failure(resolution.error))
            Resolution.Lost -> error("an app-key store has no lost key")
            is Resolution.Have -> resolution.dataKey
        }
        val fresh: KekCarry = (newCarry as? KekCarry)?.takeIf { it.snapshot == null && it.forKey === newKey } ?: return ChangeStep.NeedNew
        val (header: HeaderBody, keyCheck: ByteArray, newDataKey: ByteArray) = newFile(newKey, fresh)
        try {
            val entries: List<EncryptedEntry> = file.entries.map { entry: EncryptedEntry ->
                val plain: ByteArray = cipher.openEntry(oldKey, file.kind, entry)
                    ?: return ChangeStep.Done(StorageResult.Failure(StorageError.Undecryptable(entry.key)))
                try {
                    cipher.sealEntry(newDataKey, header.kind, entry.key, plain)
                } finally {
                    plain.fill(0)
                }
            }
            // The new header can be longer than the old one: a file that would pass the read limit is refused before it is written.
            if (SecureFileFormat.encodedSize(header, entries) > guard.maxStoreBytes) {
                return ChangeStep.Done(
                    StorageResult.Failure(
                        StorageError.OperationFailed(StorageOperation.CHANGE_KEY, key = null, cause = IllegalStateException("The re-keyed store would exceed ${guard.maxStoreBytes} bytes")),
                    ),
                )
            }
            store.write(SecureFileFormat.encode(header, keyCheck, entries), ExistingFile.Replace)
            adopt(SecureFileFormat.headerPrefix(header), newDataKey)
            key = newKey
            return ChangeStep.Done(StorageResult.Success(Unit))
        } finally {
            newDataKey.fill(0)
        }
    }

    private fun bind(newKey: AppEncryptionKey) {
        cached?.dataKey?.fill(0)
        cached = null
        key = newKey
    }
}
