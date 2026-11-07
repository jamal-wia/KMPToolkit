package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a plaintext value into something safe to leave in a `SharedPreferences` file, and back.
 *
 * A seam rather than inlined `Cipher` calls, for one reason: it lets
 * [AndroidSecureKeyValueStorage]'s own behavior — absent keys, overwrites, `clear()`, the mapping
 * from a failed decryption to [StorageError.Undecryptable] — be tested independently of the key
 * store, which no JVM test environment provides.
 */
internal interface ValueCipher {

    /** Encrypts [plaintext] and encodes the result as an ASCII string. */
    suspend fun encrypt(plaintext: String): String

    /**
     * Reverses [encrypt].
     *
     * @throws StorageKeyUnavailableException the key material could not be obtained at all.
     * @throws Exception the input is not something this cipher produced, or the key that produced
     *   it no longer exists.
     */
    suspend fun decrypt(encoded: String): String
}

/**
 * The key material behind a [ValueCipher] could not be obtained — the key store would not load, or
 * would not generate a key. Distinct from a failed decryption: nothing is wrong with the stored
 * value, and a later attempt can succeed.
 */
internal class StorageKeyUnavailableException(cause: Throwable) : Exception(cause)

/** Where [KeystoreValueCipher] gets its symmetric key from. */
internal interface SecretKeySource {

    /** @throws StorageKeyUnavailableException the key cannot be obtained. */
    suspend fun key(): SecretKey

    /**
     * Reports that [stale], a key this source handed out, turned out to be unusable. The next [key]
     * looks the key up again instead of answering [stale] — unless a newer key has replaced it by
     * then, which is never dropped. The default does nothing: a source with no cache has nothing to
     * forget.
     */
    suspend fun invalidate(stale: SecretKey) {}
}

/**
 * AES-256-GCM over a key from a [SecretKeySource].
 *
 * The output is `Base64(iv ‖ ciphertext‖tag)`. The IV is the one the platform generated for that
 * particular encryption: with an AndroidKeyStore key, `setRandomizedEncryptionRequired` is left at
 * its default `true`, so the system refuses to let a caller supply one and GCM's catastrophic
 * nonce-reuse failure is not reachable from here.
 *
 * ### Why not Tink, and why not `EncryptedSharedPreferences`
 *
 * See `docs/kmptoolkit-storage/05-platform-notes.md`. In short: `tink-android` adds roughly a
 * megabyte to every consumer's APK to wrap the same `AndroidKeyStore` primitive this class calls
 * directly, and `androidx.security:security-crypto` has been deprecated upstream with no
 * replacement — and depends on Tink itself.
 */
internal class KeystoreValueCipher(
    private val keySource: SecretKeySource,
    /** A seam for the transient-fault classifier; production uses the platform's own verdict. */
    private val isTransient: (Throwable) -> Boolean = { error: Throwable -> isTransientKeystoreFailure(error) },
) : ValueCipher {

    override suspend fun encrypt(plaintext: String): String {
        val cipher: Cipher = initialised { candidate: SecretKey, fresh: Cipher -> fresh.init(Cipher.ENCRYPT_MODE, candidate) }
        val ciphertext: ByteArray = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override suspend fun decrypt(encoded: String): String {
        // The key is resolved before the stored bytes are looked at, so that a key store which will
        // not open is reported as Unavailable — retry later — rather than as Undecryptable — the
        // value is lost. With the checks the other way round, an entry that is also malformed would
        // hide the outage behind a permanent-sounding error.
        val first: SecretKey = keySource.key()
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH_BYTES) { "ciphertext is too short to contain an IV" }
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES)
        val cipher: Cipher = initialised(first) { candidate: SecretKey, fresh: Cipher ->
            fresh.init(Cipher.DECRYPT_MODE, candidate, spec)
        }
        val plaintext: ByteArray =
            cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES)
        return plaintext.toString(Charsets.UTF_8)
    }

    /**
     * A cipher [init] accepted a key for. A key the platform rejects ([InvalidKeyException], which
     * includes `KeyPermanentlyInvalidatedException`) is reported to the source, which forgets it, and
     * the lookup is repeated once: a key replaced in the key store by another instance is picked up
     * instead of failing every use for the life of the process. Each attempt gets a new [Cipher]; one
     * whose `init` failed must not be reused.
     *
     * A fault the platform itself calls transient ([isTransientKeystoreFailure]) is rethrown
     * unchanged and the key is kept: the key is fine, the key store is briefly not, and a re-lookup
     * against a sick key store is exactly what must not happen. A read reports it as `Unavailable`;
     * a write reports it as `OperationFailed` (the key is kept either way).
     */
    private suspend fun initialised(first: SecretKey? = null, init: (SecretKey, Cipher) -> Unit): Cipher {
        val key: SecretKey = first ?: keySource.key()
        try {
            return Cipher.getInstance(TRANSFORMATION).also { init(key, it) }
        } catch (e: InvalidKeyException) {
            if (isTransient(e)) throw e
            keySource.invalidate(key)
        }
        val retry: SecretKey = keySource.key()
        return Cipher.getInstance(TRANSFORMATION).also { init(retry, it) }
    }

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM's standard nonce length, and the only one AndroidKeyStore generates. */
        const val IV_LENGTH_BYTES = 12

        /** Full-length GCM authentication tag. */
        const val TAG_LENGTH_BITS = 128
    }
}

/**
 * An AES-256 key held in the AndroidKeyStore under [alias], generated on first use.
 *
 * The key never enters the process as raw bytes: `SecretKey` here is a handle the platform resolves
 * inside the key store, hardware-backed on a device that has a TEE or a StrongBox and
 * software-isolated on one that does not. The library does not require hardware backing and does
 * not check for it — refusing to store a token on the weaker device would only push the consumer
 * into storing it somewhere worse.
 *
 * ### Why the lookup runs under a process-wide lock
 *
 * "Look the alias up, generate a key if it is absent" is two key-store calls, and the second one
 * does not fail when the alias already exists — it silently replaces the entry. Two callers that
 * both miss would each generate a key, the later one would win the alias, and every value the
 * earlier one encrypted would be [StorageError.Undecryptable] from then on; worse, if the earlier
 * caller's key were the one left in its cache, every later write in that process would be encrypted
 * under a key that is no longer in the store. The recovery path in
 * [AliasedKeyStore.existingKey] has the same shape and a sharper edge: a caller still holding a
 * stale `UnrecoverableKeyException` would delete the fresh key another caller had just generated.
 *
 * The lock is [keyResolution], one for the whole process, not one per instance: every
 * `createSecureKeyValueStorage` call builds a new source, so two stores over the same configuration
 * are two instances racing for one alias. Inside the lock the key store is asked again, so the
 * second caller finds the first caller's key instead of making its own. It is held only while a key
 * is resolved or dropped; once resolved, [cache] answers for the whole process.
 *
 * Another *process* of the same app is not covered: the AndroidKeyStore has no atomic
 * create-if-absent, and nothing in-process can stand in for one. See
 * `docs/kmptoolkit-storage/05-platform-notes.md`.
 *
 * @param keyStore the two key-store calls this class orders. A seam so the ordering can be tested
 *   on the JVM, where no AndroidKeyStore exists; production always uses [AndroidKeyStoreAliases].
 */
internal class AndroidKeystoreKeySource(
    private val alias: String,
    private val keyStore: AliasedKeyStore = AndroidKeyStoreAliases,
) : SecretKeySource {

    override suspend fun key(): SecretKey = keyResolution.withLock {
        cache[cacheKey] ?: resolve().also {
            cache[cacheKey] = it
            resolvedOnce += cacheKey
        }
    }

    /** Compare-and-remove: only the handle that failed is dropped, never a fresh key another caller made. */
    override suspend fun invalidate(stale: SecretKey) {
        keyResolution.withLock {
            if (cache[cacheKey] === stale) cache.remove(cacheKey)
        }
    }

    private val cacheKey: Pair<AliasedKeyStore, String> = keyStore to alias

    /**
     * Only the first resolution in the process may delete an unrecoverable alias. A later one is a
     * re-lookup after [invalidate], and a keystore fault during it also surfaces as an unrecoverable
     * entry on API 24-32, where nothing tells it from a dead key: deleting then would destroy every
     * stored value over a hiccup. It fails with [StorageKeyUnavailableException] instead, and the
     * next process start recovers a key that really died.
     */
    private fun resolve(): SecretKey = try {
        keyStore.existingKey(alias, mayDelete = cacheKey !in resolvedOnce) ?: keyStore.generateKey(alias)
    } catch (error: Exception) {
        throw StorageKeyUnavailableException(error)
    }

    private companion object {
        /** Serializes key resolution across every instance in the process — see the class KDoc. */
        val keyResolution: Mutex = Mutex()

        /**
         * The resolved key per alias, for the whole process and guarded by [keyResolution] — not per
         * instance: an instance that cached a key another instance later replaced would keep using
         * the dead handle for the life of the process. Keyed by the key store too, so a test's fake
         * store never sees another's keys.
         */
        val cache: MutableMap<Pair<AliasedKeyStore, String>, SecretKey> = mutableMapOf()

        /** Aliases resolved at least once in this process, guarded by [keyResolution]. */
        val resolvedOnce: MutableSet<Pair<AliasedKeyStore, String>> = mutableSetOf()
    }
}

/** The two key-store operations [AndroidKeystoreKeySource] needs, and nothing else. */
internal interface AliasedKeyStore {

    /**
     * The key filed under [alias], or `null` when there is none to use — never filed, or filed but
     * unrecoverable, in which case the dead entry is removed so [generateKey] can replace it —
     * but only when [mayDelete]. When it is `false` an unrecoverable entry is rethrown instead and
     * nothing is deleted.
     */
    fun existingKey(alias: String, mayDelete: Boolean = true): SecretKey?

    /** Generates a new key under [alias], replacing whatever the alias held. */
    fun generateKey(alias: String): SecretKey
}

/** [AliasedKeyStore] over the real AndroidKeyStore. */
internal object AndroidKeyStoreAliases : AliasedKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256

    override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return try {
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: UnrecoverableKeyException) {
            // A keystore fault that is not "gone" also surfaces as UnrecoverableKeyException (its
            // cause is the KeyStoreException). When the platform calls it transient the key is
            // still there: deleting the alias would destroy every stored value over a hiccup.
            // Throw instead, which the caller reports as Unavailable (retry later).
            if (!mayDelete || isTransientKeystoreFailure(e)) throw e
            // The alias exists but its key is gone — the user changed the device lock, or the entry
            // was restored to a device that cannot use it. Every value written under it is already
            // unreadable, so drop the alias and start a new key: reads of old entries fail with
            // Undecryptable, which is what they are, and new writes work again instead of the store
            // being bricked for the lifetime of the install.
            runCatching { keyStore.deleteEntry(alias) }
            null
        }
    }

    override fun generateKey(alias: String): SecretKey {
        val generator: KeyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Deliberately not setUserAuthenticationRequired(true): this store is for values a
                // background sync or a cold start has to read, and an auth-bound key would make
                // every read fail outside a recent unlock. A consumer that wants biometric-gated
                // secrets needs a key bound to their own auth flow, not a general-purpose store.
                .build(),
        )
        return generator.generateKey()
    }
}
