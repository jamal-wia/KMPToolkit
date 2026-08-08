package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    fun encrypt(plaintext: String): String

    /**
     * Reverses [encrypt].
     *
     * @throws StorageKeyUnavailableException the key material could not be obtained at all.
     * @throws Exception the input is not something this cipher produced, or the key that produced
     *   it no longer exists.
     */
    fun decrypt(encoded: String): String
}

/**
 * The key material behind a [ValueCipher] could not be obtained — the key store would not load, or
 * would not generate a key. Distinct from a failed decryption: nothing is wrong with the stored
 * value, and a later attempt can succeed.
 */
internal class StorageKeyUnavailableException(cause: Throwable) : Exception(cause)

/** Where [KeystoreValueCipher] gets its symmetric key from. */
internal fun interface SecretKeySource {

    /** @throws StorageKeyUnavailableException the key cannot be obtained. */
    fun key(): SecretKey
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
internal class KeystoreValueCipher(private val keySource: SecretKeySource) : ValueCipher {

    override fun encrypt(plaintext: String): String {
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySource.key())
        val ciphertext: ByteArray = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        // The key is resolved before the stored bytes are looked at, so that a key store which will
        // not open is reported as Unavailable — retry later — rather than as Undecryptable — the
        // value is lost. With the checks the other way round, an entry that is also malformed would
        // hide the outage behind a permanent-sounding error.
        val key: SecretKey = keySource.key()
        val bytes: ByteArray = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_LENGTH_BYTES) { "ciphertext is too short to contain an IV" }
        val cipher: Cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES),
        )
        val plaintext: ByteArray =
            cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES)
        return plaintext.toString(Charsets.UTF_8)
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
 */
internal class AndroidKeystoreKeySource(private val alias: String) : SecretKeySource {

    @Volatile
    private var cached: SecretKey? = null

    override fun key(): SecretKey {
        cached?.let { return it }
        val resolved: SecretKey = try {
            loadOrCreateKey()
        } catch (error: Exception) {
            throw StorageKeyUnavailableException(error)
        }
        cached = resolved
        return resolved
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        existingKey(keyStore)?.let { return it }
        return generateKey()
    }

    private fun existingKey(keyStore: KeyStore): SecretKey? = try {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: UnrecoverableKeyException) {
        // The alias exists but its key is gone — the user changed the device lock, or the entry was
        // restored to a device that cannot use it. Every value written under it is already
        // unreadable, so drop the alias and start a new key: reads of old entries fail with
        // Undecryptable, which is what they are, and new writes work again instead of the store
        // being bricked for the lifetime of the install.
        runCatching { keyStore.deleteEntry(alias) }
        null
    }

    private fun generateKey(): SecretKey {
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

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_SIZE_BITS = 256
    }
}
