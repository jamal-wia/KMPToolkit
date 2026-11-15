package io.github.jamal_wia.kmptoolkit.storage

import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKeyFactory
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

    /**
     * [encrypt] for the value stored under [key]: a cipher that can, binds the ciphertext to the key name, so
     * the same bytes moved to another key do not decrypt there. The default does not bind (nothing to bind with).
     */
    suspend fun encryptFor(key: String, plaintext: String): String = encrypt(plaintext)

    /**
     * [decrypt] for the value stored under [key]; reads what [encryptFor] wrote, and nothing older: an entry without
     * the version byte is not read here, see [decryptLegacyFor].
     *
     * @throws java.security.GeneralSecurityException (a `BadPaddingException`) the bytes are not an envelope this
     *   cipher wrote for [key]: another version, another key name, or tampered.
     */
    suspend fun decryptFor(key: String, encoded: String): String = decrypt(encoded)

    /**
     * Reads an entry written before the version byte existed (4.0.0 and 5.0.0): bound to [key] (5.0.0), else not bound
     * at all (4.0.0 and older). Only the one-time migration pass and the reads before it finishes call this; once the
     * store has been migrated an unversioned entry is lost. The default has no older format.
     */
    suspend fun decryptLegacyFor(key: String, encoded: String): String = decryptFor(key, encoded)

    /** The security level the platform reports for the key behind this cipher; the default cannot say. */
    suspend fun keySecurityLevel(): AndroidKeySecurityLevel = AndroidKeySecurityLevel.UNKNOWN
}

/**
 * The key material behind a [ValueCipher] could not be obtained — the key store would not load, or
 * would not generate a key. Distinct from a failed decryption: nothing is wrong with the stored
 * value, and a later attempt can succeed.
 */
internal class StorageKeyUnavailableException(cause: Throwable) : Exception(cause)

/**
 * The key cannot be generated because the hardware it was required to live in is not there
 * ([AndroidKeyBacking.REQUIRE_STRONGBOX] on a device without a StrongBox). Permanent for this device: nothing to retry,
 * reported as [StorageError.Unsupported].
 */
internal class KeyHardwareUnavailableException(cause: Throwable) : Exception(cause)

/**
 * The platform refused to use the key because the user is not authenticated (outside the validity window of
 * [AndroidUserAuthentication]); distinct from a locked device, which is [StorageError.Unavailable]. Reported as
 * [StorageError.AccessDenied]; the key is kept.
 */
internal class UserAuthenticationRequiredException(cause: Throwable) : Exception(cause)

/** Where [KeystoreValueCipher] gets its symmetric key from. */
internal interface SecretKeySource {

    /**
     * Whether the keys this source hands out are bound to user authentication: a refusal for lack of authentication is
     * then [UserAuthenticationRequiredException], not a locked device. The default is `false`.
     */
    val userAuthenticationRequired: Boolean get() = false

    /** @throws StorageKeyUnavailableException the key cannot be obtained. */
    suspend fun key(): SecretKey

    /**
     * Reports that [stale], a key this source handed out, turned out to be unusable. The next [key]
     * looks the key up again instead of answering [stale] — unless a newer key has replaced it by
     * then, which is never dropped. The default does nothing: a source with no cache has nothing to
     * forget.
     */
    suspend fun invalidate(stale: SecretKey) {}

    /**
     * Reports that [dead], a key this source handed out, is permanently unusable: the platform will never accept it
     * again (`KeyPermanentlyInvalidatedException`), and looking the alias up again would hand out the same dead entry
     * for ever. The source gets rid of the dead key, unless a newer key has replaced it by then, which is never
     * dropped, so that the next [key] is a new one. The default only forgets the key, like [invalidate].
     *
     * @throws StorageKeyUnavailableException the dead key could not be removed; nothing changed and a later attempt
     *   can succeed.
     */
    suspend fun discard(dead: SecretKey) = invalidate(dead)

    /** The security level the platform reports for the key this source hands out; the default cannot say. */
    suspend fun securityLevel(): AndroidKeySecurityLevel = AndroidKeySecurityLevel.UNKNOWN
}

/**
 * AES-256-GCM over a key from a [SecretKeySource].
 *
 * The output is `Base64(version ‖ iv ‖ ciphertext ‖ tag)`, with the version byte and the key name authenticated as
 * associated data. Entries of the older format (`Base64(iv ‖ ciphertext ‖ tag)`) are read only through
 * [decryptLegacyFor]. An entry too short to hold its header and a tag is classified by length before any decryption
 * (not a version-1 envelope for the ordinary reader, malformed for the legacy one), because the AndroidKeyStore
 * answers a GCM input shorter than the tag with `IllegalBlockSizeException`, which is neither of the two answers the
 * callers act on. The IV is the one the platform generated for that
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
    /** A seam for the permanent-invalidation classifier; production uses [isPermanentlyInvalidatedKey]. */
    private val isPermanentlyInvalidated: (Throwable) -> Boolean =
        { error: Throwable -> isPermanentlyInvalidatedKey(error) },
) : ValueCipher {

    override suspend fun encrypt(plaintext: String): String = encryptVersioned(plaintext, key = null)

    override suspend fun encryptFor(key: String, plaintext: String): String = encryptVersioned(plaintext, key)

    override suspend fun decrypt(encoded: String): String = decryptVersioned(encoded, key = null)

    override suspend fun decryptFor(key: String, encoded: String): String = decryptVersioned(encoded, key)

    override suspend fun keySecurityLevel(): AndroidKeySecurityLevel = keySource.securityLevel()

    /**
     * Tries the binding to [key] first (5.0.0); an entry that fails its tag that way is read once more as an unbound
     * one (4.0.0 and older have none). A ciphertext moved from another key fails both, so it is never taken for this
     * key's value; a moved entry that was itself unbound is accepted until the migration pass has rewritten it.
     */
    override suspend fun decryptLegacyFor(key: String, encoded: String): String = try {
        decryptUnversioned(encoded, aad = legacyAadOf(key))
    } catch (_: BadPaddingException) {
        decryptUnversioned(encoded, aad = null)
    }

    /** The key name behind a fixed prefix: what 5.0.0 authenticated (never empty: empty associated data is none). */
    private fun legacyAadOf(key: String): ByteArray = (AAD_PREFIX + key).toByteArray(Charsets.UTF_8)

    /** Authenticated, so a version byte that is changed fails the tag: the version byte, then the key name if any. */
    private fun aadOf(key: String?): ByteArray =
        if (key == null) byteArrayOf(FORMAT_VERSION) else byteArrayOf(FORMAT_VERSION) + legacyAadOf(key)

    private suspend fun encryptVersioned(plaintext: String, key: String?): String {
        val cipher: Cipher = initialised { candidate: SecretKey, fresh: Cipher -> fresh.init(Cipher.ENCRYPT_MODE, candidate) }
        cipher.updateAAD(aadOf(key))
        val ciphertext: ByteArray = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(FORMAT_VERSION) + cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private suspend fun decryptVersioned(encoded: String, key: String?): String {
        // The key is resolved before the stored bytes are looked at, so that a key store which will
        // not open is reported as Unavailable (retry later) rather than as Undecryptable (the
        // value is lost). With the checks the other way round, an entry that is also malformed would
        // hide the outage behind a permanent-sounding error.
        val first: SecretKey = keySource.key()
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > HEADER_LENGTH_BYTES) { "ciphertext is too short to contain a version and an IV" }
        // Not an IllegalArgumentException: an unversioned entry (or another version) is an entry this reader cannot
        // open, the same answer as a failed tag.
        if (bytes[0] != FORMAT_VERSION) throw BadPaddingException("unsupported envelope version")
        // Classified by length, before any crypto: the AndroidKeyStore answers a decryption whose input is shorter than
        // the tag with IllegalBlockSizeException, not a bad-tag error, so a too-short body would be neither "not this
        // format" (the legacy reader) nor "malformed" (skipped by the migration). A legacy empty value is exactly 28
        // bytes, and one in 256 of them starts with the version byte.
        if (bytes.size < HEADER_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            throw BadPaddingException("too short to be a version-1 envelope")
        }
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, bytes, VERSION_LENGTH_BYTES, IV_LENGTH_BYTES)
        val cipher: Cipher = initialised(first) { candidate: SecretKey, fresh: Cipher ->
            fresh.init(Cipher.DECRYPT_MODE, candidate, spec)
        }
        cipher.updateAAD(aadOf(key))
        val plaintext: ByteArray = cipher.doFinal(bytes, HEADER_LENGTH_BYTES, bytes.size - HEADER_LENGTH_BYTES)
        return plaintext.toString(Charsets.UTF_8)
    }

    /** The format before the version byte: `iv ‖ ciphertext ‖ tag`, optionally with the 5.0.0 key binding as [aad]. */
    private suspend fun decryptUnversioned(encoded: String, aad: ByteArray?): String {
        val first: SecretKey = keySource.key()
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
        // By length, before any crypto, for the reason given in decryptVersioned: the platform may not report it as a tag
        // failure. Malformed, so the migration pass skips it.
        require(bytes.size >= IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            "ciphertext is too short to contain an IV and a tag"
        }
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES)
        val cipher: Cipher = initialised(first) { candidate: SecretKey, fresh: Cipher ->
            fresh.init(Cipher.DECRYPT_MODE, candidate, spec)
        }
        if (aad != null) cipher.updateAAD(aad)
        val plaintext: ByteArray = cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES)
        return plaintext.toString(Charsets.UTF_8)
    }

    /**
     * A cipher [init] accepted a key for. A key the platform rejects ([InvalidKeyException]) is reported to the source
     * and the lookup is repeated: a key replaced in the key store by another instance is picked up instead of failing
     * every use for the life of the process. Each attempt gets a new [Cipher]; one whose `init` failed must not be
     * reused.
     *
     * A key the platform calls permanently invalidated ([isPermanentlyInvalidated]) is reported with
     * [SecretKeySource.discard]: looking it up again would only answer the same dead entry, which used to fail every
     * write for the life of the install. The source deletes it and the next lookup generates a new one, so the write
     * succeeds and entries of the dead key read as [StorageError.Undecryptable]. A concurrent caller may have been
     * handed the same dead entry again before it was deleted, so a permanent rejection gets one more attempt.
     *
     * A fault the platform itself calls transient ([isTransientKeystoreFailure]) is rethrown
     * unchanged and the key is kept: the key is fine, the key store is briefly not, and a re-lookup
     * against a sick key store is exactly what must not happen. A read reports it as `Unavailable`;
     * a write reports it as `OperationFailed` (the key is kept either way).
     */
    private suspend fun initialised(first: SecretKey? = null, init: (SecretKey, Cipher) -> Unit): Cipher {
        var key: SecretKey = first ?: keySource.key()
        var retries = 0
        while (true) {
            try {
                return Cipher.getInstance(TRANSFORMATION).also { init(key, it) }
            } catch (e: InvalidKeyException) {
                // A locked device (a key that needs it unlocked) and an unauthenticated user are states that pass: never a
                // reason to drop the key. The platform reports them the same way; the key's own options tell which.
                if (isDeviceLockedFailure(e)) {
                    throw if (keySource.userAuthenticationRequired) UserAuthenticationRequiredException(e) else e
                }
                if (isTransient(e)) throw e
                val permanent: Boolean = isPermanentlyInvalidated(e)
                if (retries >= (if (permanent) MAX_PERMANENT_RETRIES else MAX_RETRIES)) throw e
                retries++
                if (permanent) keySource.discard(key) else keySource.invalidate(key)
                key = keySource.key()
            }
        }
    }

    internal companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Prefix of the associated data that binds a ciphertext to the key it is stored under. */
        const val AAD_PREFIX = "kmptoolkit-storage-key:"

        /** GCM's standard nonce length, and the only one AndroidKeyStore generates. */
        const val IV_LENGTH_BYTES = 12

        /** The envelope version this cipher writes and the only one it reads: the first byte of every entry. */
        const val FORMAT_VERSION: Byte = 1

        const val VERSION_LENGTH_BYTES = 1

        /** The version byte and the IV ahead of the ciphertext. */
        const val HEADER_LENGTH_BYTES = VERSION_LENGTH_BYTES + IV_LENGTH_BYTES

        /** Full-length GCM authentication tag. */
        const val TAG_LENGTH_BITS = 128

        /** A rejected key is looked up once more: a key another instance replaced is then picked up. */
        const val MAX_RETRIES = 1

        /** A permanent rejection may meet the same dead entry once more, handed out to a concurrent caller. */
        const val MAX_PERMANENT_RETRIES = 2
    }
}

/**
 * Whether [error], or anything in its cause chain, is `KeyPermanentlyInvalidatedException`: the platform has
 * invalidated the key for good (a key that required a lock screen the user removed, or biometrics that changed). Any
 * other rejection may be a fault that passes, so it never leads to a key being deleted.
 */
internal fun isPermanentlyInvalidatedKey(error: Throwable): Boolean {
    val seen: MutableSet<Throwable> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is KeyPermanentlyInvalidatedException) return true
        current = current.cause
    }
    return false
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
 * ### Why the lookup runs under a lock, and why the lock is per alias
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
 * The lock is [aliasLock], one per alias for the whole process, not one per instance: every
 * `createSecureKeyValueStorage` call builds a new source, so two stores over the same configuration
 * are two instances racing for one alias, while two stores over different aliases share nothing and
 * never wait for each other (one slow alias used to stall every store in the process). Inside the
 * lock the key store is asked again, so the second caller finds the first caller's key instead of
 * making its own. It is held only while a key is resolved, dropped or deleted; once resolved,
 * [cache] answers for the whole process without taking any lock. The key-store calls under the lock
 * are blocking but never suspend and never call back into foreign code.
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
    /** The properties a key gets when it is generated; an existing key keeps the ones it has. */
    private val options: AndroidSecureStorageOptions = AndroidSecureStorageOptions(),
) : SecretKeySource {

    override suspend fun key(): SecretKey {
        // A resolved key is answered without any lock; only a miss waits for the alias's own lock.
        cache[cacheKey]?.let { cached: SecretKey -> return cached }
        return aliasLock.withLock {
            cache[cacheKey] ?: resolve().also { resolved: SecretKey ->
                cache[cacheKey] = resolved
                resolvedOnce += cacheKey
            }
        }
    }

    /** Compare-and-remove: only the handle that failed is dropped, never a fresh key another caller made. */
    override suspend fun invalidate(stale: SecretKey) {
        aliasLock.withLock {
            if (cache[cacheKey] === stale) cache.remove(cacheKey)
        }
    }

    /**
     * Compare-and-delete: only the handle the platform reported dead is deleted from the key store, and only while it
     * is still the one cached. A newer key another caller made is never touched. The key store entry goes first, so a
     * failed deletion leaves the cache as it was and the next report retries; the next [key] then finds the alias empty
     * and generates a new key, under the same lock.
     */
    override suspend fun discard(dead: SecretKey) {
        aliasLock.withLock {
            if (cache[cacheKey] !== dead) return
            try {
                keyStore.deleteKey(alias)
            } catch (error: Exception) {
                throw StorageKeyUnavailableException(error)
            }
            cache.remove(cacheKey)
        }
    }

    private val cacheKey: Pair<AliasedKeyStore, String> = keyStore to alias

    /**
     * Only the first resolution in the process may delete an alias, and only one the platform has proven permanently
     * invalidated ([resolveExistingKey]). A later one is a re-lookup after [invalidate], and a keystore fault during it
     * also surfaces as an unrecoverable entry on API 24-32, where nothing tells it from a dead key: deleting then would
     * destroy every stored value over a hiccup. It fails with [StorageKeyUnavailableException] instead, and the next
     * process start recovers a key that was proven dead.
     */
    private fun resolve(): SecretKey = try {
        keyStore.existingKey(alias, mayDelete = cacheKey !in resolvedOnce) ?: keyStore.generateKey(alias, options)
    } catch (error: KeyHardwareUnavailableException) {
        throw error
    } catch (error: Exception) {
        throw StorageKeyUnavailableException(error)
    }

    override suspend fun securityLevel(): AndroidKeySecurityLevel = keyStore.securityLevel(key())

    override val userAuthenticationRequired: Boolean = options.userAuthentication != null

    /** This alias's lock, shared by every instance over it — see the class KDoc. */
    private val aliasLock: Mutex get() = aliasLocks.computeIfAbsent(cacheKey) { Mutex() }

    private companion object {
        /**
         * One lock per alias (and key store) for the whole process: it serializes the resolution, invalidation and
         * deletion of that alias's key and nothing else, so a slow alias never stalls another one. Entries are never
         * removed, like [cache]'s: one small object per alias the process has used.
         */
        val aliasLocks: ConcurrentHashMap<Pair<AliasedKeyStore, String>, Mutex> = ConcurrentHashMap()

        /**
         * The resolved key per alias, for the whole process — not per instance: an instance that cached a key
         * another instance later replaced would keep using the dead handle for the life of the process. Keyed by
         * the key store too, so a test's fake store never sees another's keys. Read without a lock (a hit costs
         * nothing), written only under the alias's lock.
         */
        val cache: ConcurrentHashMap<Pair<AliasedKeyStore, String>, SecretKey> = ConcurrentHashMap()

        /** Aliases resolved at least once in this process; each entry is touched only under its own alias's lock. */
        val resolvedOnce: MutableSet<Pair<AliasedKeyStore, String>> = ConcurrentHashMap.newKeySet()
    }
}

/** The key-store operations [AndroidKeystoreKeySource] needs, and nothing else. */
internal interface AliasedKeyStore {

    /**
     * The key filed under [alias], or `null` when there is none to use — never filed, or filed but PROVEN permanently
     * invalidated, in which case the dead entry is removed so [generateKey] can replace it — but only when [mayDelete].
     * When it is `false`, and for every failure that is not such a proof (a locked device, a transient fault, anything
     * unknown), the failure is rethrown instead and nothing is deleted; see [resolveExistingKey].
     */
    fun existingKey(alias: String, mayDelete: Boolean = true): SecretKey?

    /** Generates a new key under [alias], replacing whatever the alias held. */
    fun generateKey(alias: String): SecretKey

    /**
     * Generates a new key under [alias] with the properties of [options]. A key store that has no use for them (a test
     * double) inherits this default and ignores them.
     *
     * @throws KeyHardwareUnavailableException the required hardware is not there.
     */
    fun generateKey(alias: String, options: AndroidSecureStorageOptions): SecretKey = generateKey(alias)

    /** The security level the platform reports for [key]; a store that cannot say answers UNKNOWN. */
    fun securityLevel(key: SecretKey): AndroidKeySecurityLevel = AndroidKeySecurityLevel.UNKNOWN

    /**
     * Deletes whatever [alias] holds; a no-op when it holds nothing. The default is for a test double that has no use
     * for it and must fail loudly if a key is ever deleted through it.
     */
    fun deleteKey(alias: String): Unit =
        throw UnsupportedOperationException("this key store does not delete keys")
}

/** [AliasedKeyStore] over the real AndroidKeyStore. */
internal object AndroidKeyStoreAliases : AliasedKeyStore {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_SIZE_BITS = 256

    override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return resolveExistingKey(
            mayDelete = mayDelete,
            lookup = { (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey },
            delete = { keyStore.deleteEntry(alias) },
        )
    }

    override fun deleteKey(alias: String) {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias)
    }

    override fun generateKey(alias: String): SecretKey = generateKey(alias, AndroidSecureStorageOptions())

    override fun generateKey(alias: String, options: AndroidSecureStorageOptions): SecretKey {
        // Decided before any key is made, so that a request this platform level cannot honour generates nothing.
        val authentication: UserAuthenticationSpec? =
            options.userAuthentication?.let { requested: AndroidUserAuthentication ->
                userAuthenticationSpecOf(Build.VERSION.SDK_INT, requested)
            }
        return generateWithBacking(alias, options.keyBacking, Build.VERSION.SDK_INT) { name: String, strongBox: Boolean ->
            generate(name, strongBox, options.unlockedDeviceRequired, authentication)
        }
    }

    private fun generate(
        alias: String,
        strongBox: Boolean,
        unlockedDeviceRequired: Boolean,
        authentication: UserAuthenticationSpec?,
    ): SecretKey {
        val generator: KeyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder: KeyGenParameterSpec.Builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
        // Not user-authentication-bound unless the options ask for it (AndroidUserAuthentication): this store is, by
        // default, for values a background sync or a cold start has to read.
        if (authentication != null) {
            builder.setUserAuthenticationRequired(true)
            applyUserAuthentication(builder, authentication)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (strongBox) builder.setIsStrongBoxBacked(true)
            if (unlockedDeviceRequired) builder.setUnlockedDeviceRequired(true)
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    override fun securityLevel(key: SecretKey): AndroidKeySecurityLevel = try {
        val info: KeyInfo =
            SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        @Suppress("DEPRECATION")
        securityLevelOf(Build.VERSION.SDK_INT, { securityLevelConstant(info) }, { info.isInsideSecureHardware })
    } catch (_: Exception) {
        // The platform could not say: not a failure of the store.
        AndroidKeySecurityLevel.UNKNOWN
    }
}

/**
 * Looks an alias up and, only for a key the platform has PROVEN dead, deletes it so that a new one can be generated.
 *
 * `getEntry` answers `UnrecoverableKeyException` for a key that is gone, but also for faults that pass: a keystore
 * daemon that is restarting, a device that is locked while the key needs it unlocked, a provider that is busy. On the
 * older platforms nothing in the exception tells the two apart, and deleting the alias over a fault that passes
 * destroys every stored value. So the entry is deleted only when [isPermanentlyInvalidatedKey] holds for the exception
 * (`KeyPermanentlyInvalidatedException` in its cause chain, the plan's A-4 definition), only when [mayDelete] (the
 * first resolution in the process), and never for a locked device or a fault the platform calls transient. Every other
 * failure, including one that is none of these, is rethrown, nothing is deleted, and the caller reports
 * [StorageError.Unavailable]; a key that is dead for real is then replaced by the cipher's own invalidation handling at
 * its first use.
 *
 * A function over the two key-store calls so that the policy can be tested on the JVM, where no AndroidKeyStore exists.
 *
 * @param lookup `KeyStore.getEntry` for the alias, as the secret key or `null` when the alias holds none.
 * @param delete `KeyStore.deleteEntry` for the alias.
 * @return the key, or `null` when there is none to use (never filed, or proven dead and deleted).
 */
internal fun resolveExistingKey(mayDelete: Boolean, lookup: () -> SecretKey?, delete: () -> Unit): SecretKey? =
    try {
        lookup()
    } catch (e: UnrecoverableKeyException) {
        if (!mayDelete || isTransientKeystoreFailure(e) || isDeviceLockedFailure(e) || !isPermanentlyInvalidatedKey(e)) {
            throw e
        }
        // The platform has said the key is dead for good: every value written under it is already unreadable. Drop the
        // alias and start a new key, so that reads of old entries fail with Undecryptable, which is what they are, and
        // new writes work again instead of the store being bricked for the lifetime of the install.
        try {
            delete()
        } catch (_: Exception) {
            // Best effort: if the entry cannot be deleted now, generateKey replaces it anyway.
        }
        null
    }

/**
 * The platform parameters of an [AndroidUserAuthentication]: the validity window and, from API 30, which authenticators
 * open it (`KeyProperties.AUTH_*`); [authenticators] is `null` below API 30, where the platform has only the window.
 */
internal class UserAuthenticationSpec(val validitySeconds: Int, val authenticators: Int?)

/**
 * The parameters [authentication] maps to on platform level [sdkInt]. From API 30 the authenticators are named: a strong
 * biometric, plus the device credential when [AndroidUserAuthentication.allowDeviceCredential]. Below it the platform
 * accepts the device credential or a biometric for a time-bound key and cannot be told otherwise, so a request for a
 * biometric only is refused rather than silently weakened.
 *
 * @throws KeyHardwareUnavailableException a biometric-only request below API 30.
 */
internal fun userAuthenticationSpecOf(sdkInt: Int, authentication: AndroidUserAuthentication): UserAuthenticationSpec =
    if (sdkInt >= Build.VERSION_CODES.R) {
        val biometric: Int = KeyProperties.AUTH_BIOMETRIC_STRONG
        UserAuthenticationSpec(
            validitySeconds = authentication.validitySeconds,
            authenticators = if (authentication.allowDeviceCredential) biometric or KeyProperties.AUTH_DEVICE_CREDENTIAL else biometric,
        )
    } else if (!authentication.allowDeviceCredential) {
        throw KeyHardwareUnavailableException(
            IllegalStateException("a biometric-only key needs API ${Build.VERSION_CODES.R}, this device is API $sdkInt"),
        )
    } else {
        UserAuthenticationSpec(validitySeconds = authentication.validitySeconds, authenticators = null)
    }

/** The only place that names the user-authentication builder calls whose availability depends on the platform level. */
@Suppress("DEPRECATION")
private fun applyUserAuthentication(builder: KeyGenParameterSpec.Builder, spec: UserAuthenticationSpec) {
    val authenticators: Int? = spec.authenticators
    if (authenticators != null) {
        applyUserAuthenticationParameters(builder, spec.validitySeconds, authenticators)
    } else {
        builder.setUserAuthenticationValidityDurationSeconds(spec.validitySeconds)
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun applyUserAuthenticationParameters(builder: KeyGenParameterSpec.Builder, seconds: Int, authenticators: Int) {
    builder.setUserAuthenticationParameters(seconds, authenticators)
}

/**
 * Generates a key with the backing the options ask for: `DEFAULT` never asks for StrongBox; `PREFER_STRONGBOX` asks for
 * it and, when the device has none (or the platform predates it), generates the default key instead; `REQUIRE_STRONGBOX`
 * asks for it and, when there is none, generates nothing and fails with [KeyHardwareUnavailableException].
 *
 * A function over a [make] seam so the fallback can be tested on the JVM, where no Keystore exists.
 *
 * @param make generates one key, in a StrongBox or not, with a new generator each time.
 * @param isStrongBoxUnavailable whether a failure of [make] means the device has no StrongBox.
 */
internal fun generateWithBacking(
    alias: String,
    backing: AndroidKeyBacking,
    sdkInt: Int,
    isStrongBoxUnavailable: (Throwable) -> Boolean = { error: Throwable -> isStrongBoxUnavailableFailure(error) },
    make: (alias: String, strongBox: Boolean) -> SecretKey,
): SecretKey {
    val strongBoxPossible: Boolean = sdkInt >= Build.VERSION_CODES.P
    return when (backing) {
        AndroidKeyBacking.DEFAULT -> make(alias, false)
        AndroidKeyBacking.PREFER_STRONGBOX ->
            if (!strongBoxPossible) {
                make(alias, false)
            } else {
                try {
                    make(alias, true)
                } catch (error: Exception) {
                    if (!isStrongBoxUnavailable(error)) throw error
                    make(alias, false)
                }
            }
        AndroidKeyBacking.REQUIRE_STRONGBOX ->
            if (!strongBoxPossible) {
                throw KeyHardwareUnavailableException(IllegalStateException("StrongBox needs API ${Build.VERSION_CODES.P}"))
            } else {
                try {
                    make(alias, true)
                } catch (error: Exception) {
                    if (!isStrongBoxUnavailable(error)) throw error
                    throw KeyHardwareUnavailableException(error)
                }
            }
    }
}

/**
 * The level the platform reports in [info]: from API 31 its `securityLevel`; below, only "inside secure hardware" is
 * known, which cannot tell a trusted environment from a StrongBox, so a hardware key is UNKNOWN and a software key is
 * SOFTWARE.
 */
internal fun securityLevelOf(
    sdkInt: Int,
    level: () -> Int,
    insideSecureHardware: () -> Boolean,
): AndroidKeySecurityLevel =
    if (sdkInt >= Build.VERSION_CODES.S) {
        securityLevelOf(level())
    } else {
        if (insideSecureHardware()) AndroidKeySecurityLevel.UNKNOWN else AndroidKeySecurityLevel.SOFTWARE
    }

/** The only place that reads `KeyInfo.getSecurityLevel`, which exists from API 31. */
@TargetApi(Build.VERSION_CODES.S)
private fun securityLevelConstant(info: KeyInfo): Int = info.securityLevel

/** The `KeyProperties.SECURITY_LEVEL_*` constant as a level; one this library does not know is UNKNOWN. */
internal fun securityLevelOf(level: Int): AndroidKeySecurityLevel = when (level) {
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> AndroidKeySecurityLevel.SOFTWARE
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> AndroidKeySecurityLevel.TRUSTED_ENVIRONMENT
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> AndroidKeySecurityLevel.STRONGBOX
    else -> AndroidKeySecurityLevel.UNKNOWN
}
