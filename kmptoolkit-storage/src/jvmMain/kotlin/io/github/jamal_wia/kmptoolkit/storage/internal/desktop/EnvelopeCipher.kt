package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.charset.StandardCharsets

/**
 * AES-256-GCM for the desktop encrypted stores (plan 4.3): entries, the key-check and the wrapping of the data key.
 *
 * Frozen by format v1: every encryption uses a fresh 96-bit nonce from [random] (a `SecureRandom`), never a counter or a
 * derived value, and data keys come from it too. A tag that does not verify is the only failure that answers
 * "wrong key or tampered" (`null` / `false`); any other security-provider failure, on encryption and on decryption alike, is
 * thrown as a [JcaFailure] (an `IllegalStateException`), so an environment problem is never mistaken for lost data (that verdict
 * makes an app delete things) and the stores answer it as `OperationFailed`. Every JCA call goes through the one boundary [Jca],
 * which owns the three rules: any exception a provider throws is typed, only a bad-tag exception WITHOUT a cause is a tag that
 * does not verify (a PKCS#11 provider reports a token error as a bad-tag exception WITH a cause), and an `Error` is never caught.
 * This class only adds the format's sizes and the nonce discipline.
 *
 * Stateless apart from [random]; safe from any thread. Key arguments are borrowed, never kept and never wiped here.
 * The class, [sealEntry], [unwrapDataKey] and [verifyKeyCheck] are open only so that a test can count how many values were
 * sealed (the proof that a refused value was never encoded), see which key an unwrap returned (the proof that it is wiped when
 * the key-check then fails) and make a step fail; nothing in the library overrides them.
 */
internal open class EnvelopeCipher(private val random: JcaRandom = Jca.newRandom()) {

    /** A fresh random 256-bit data key. The caller owns and wipes it. */
    fun newDataKey(): ByteArray = randomBytes(SecureFileFormat.DATA_KEY_BYTES)

    /** A fresh random 16-byte salt. */
    fun newSalt(): ByteArray = randomBytes(SecureFileFormat.SALT_BYTES)

    /** Seals [value] under [dataKey] for the entry [key] of a file of [kind]; the entry name is authenticated. */
    open fun sealEntry(dataKey: ByteArray, kind: StoreKind, key: String, value: ByteArray): EncryptedEntry {
        val nonce: ByteArray = newNonce()
        val sealed: ByteArray = gcmEncrypt(dataKey, nonce, SecureFileFormat.entryAad(kind, key.toStrictUtf8()), value)
        return EncryptedEntry(key, nonce, sealed)
    }

    /** The plaintext of [entry], or `null` when its tag does not verify under [dataKey] for this name and kind. */
    fun openEntry(dataKey: ByteArray, kind: StoreKind, entry: EncryptedEntry): ByteArray? =
        gcmDecrypt(dataKey, entry.nonce, SecureFileFormat.entryAad(kind, entry.key.toStrictUtf8()), entry.sealed)

    /** The key-check of a file whose header prefix is [headerPrefix]: GCM over an empty plaintext, nonce then tag. */
    fun sealKeyCheck(dataKey: ByteArray, headerPrefix: ByteArray): ByteArray {
        val nonce: ByteArray = newNonce()
        return nonce + gcmEncrypt(dataKey, nonce, keyCheckAad(headerPrefix), ByteArray(0))
    }

    /** Whether [keyCheck] verifies under [dataKey] for exactly [headerPrefix]; any changed header byte makes it `false`. */
    open fun verifyKeyCheck(dataKey: ByteArray, headerPrefix: ByteArray, keyCheck: ByteArray): Boolean {
        if (keyCheck.size != SecureFileFormat.KEY_CHECK_BYTES) return false
        val nonce: ByteArray = keyCheck.copyOfRange(0, SecureFileFormat.NONCE_BYTES)
        val tag: ByteArray = keyCheck.copyOfRange(SecureFileFormat.NONCE_BYTES, keyCheck.size)
        return gcmDecrypt(dataKey, nonce, keyCheckAad(headerPrefix), tag) != null
    }

    /** Wraps [dataKey] under [kek] (kinds 3 and 4): nonce 12, ciphertext 32, tag 16. [aad] is [SecureFileFormat.wrapAad]. */
    fun wrapDataKey(kek: ByteArray, dataKey: ByteArray, aad: ByteArray): ByteArray {
        require(dataKey.size == SecureFileFormat.DATA_KEY_BYTES) { "a data key is 32 bytes" }
        val nonce: ByteArray = newNonce()
        return nonce + gcmEncrypt(kek, nonce, aad, dataKey)
    }

    /** The data key inside [wrapped], or `null` when [kek] or the header bytes in [aad] are not the ones it was wrapped with. */
    open fun unwrapDataKey(kek: ByteArray, wrapped: ByteArray, aad: ByteArray): ByteArray? {
        if (wrapped.size != SecureFileFormat.WRAPPED_KEY_BYTES) return null
        val nonce: ByteArray = wrapped.copyOfRange(0, SecureFileFormat.NONCE_BYTES)
        return gcmDecrypt(kek, nonce, aad, wrapped.copyOfRange(SecureFileFormat.NONCE_BYTES, wrapped.size))
    }

    private fun newNonce(): ByteArray = randomBytes(SecureFileFormat.NONCE_BYTES)

    /** [size] random bytes; a random source that fails is a [JcaFailure] like any other provider failure. */
    private fun randomBytes(size: Int): ByteArray = Jca.randomBytes(random, size)

    private fun keyCheckAad(headerPrefix: ByteArray): ByteArray = headerPrefix + KEY_CHECK_LABEL

    internal companion object {
        private val KEY_CHECK_LABEL: ByteArray = "kmptoolkit-keycheck".toByteArray(StandardCharsets.UTF_8)
        private const val TAG_BITS: Int = SecureFileFormat.TAG_BYTES * 8

        /** AES-256-GCM encryption with an externally given 96-bit [nonce]: ciphertext followed by the 16-byte tag. For KATs and [EnvelopeCipher]. */
        fun gcmEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
            require(key.size == SecureFileFormat.DATA_KEY_BYTES) { "AES-256 needs a 32-byte key" }
            require(nonce.size == SecureFileFormat.NONCE_BYTES) { "the nonce is 96 bits" }
            return Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plaintext)
        }

        /** The plaintext, or `null` only when the tag does not verify; any other provider failure is thrown as a [JcaFailure]. */
        fun gcmDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
            require(key.size == SecureFileFormat.DATA_KEY_BYTES) { "AES-256 needs a 32-byte key" }
            if (nonce.size != SecureFileFormat.NONCE_BYTES || sealed.size < SecureFileFormat.TAG_BYTES) return null
            return Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed)
        }

        /** Overwrites [bytes] with zeros. */
        fun wipe(bytes: ByteArray) {
            bytes.fill(0)
        }
    }
}
