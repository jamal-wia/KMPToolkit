package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.CharBuffer
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 for the passphrase store (plan 4.3, 4.8, D-8). `javax.crypto` only, no dependency.
 *
 * The passphrase is Unicode NFC-normalised, then UTF-8 encoded, before derivation; this is frozen by format v1, so the
 * same text typed in NFC and in NFD opens the same file. The accepted iteration range is [ITERATIONS_FLOOR]..
 * [ITERATIONS_CEILING]; the floor never rises within v1 (a raised default applies to new or re-keyed files only), and a
 * count outside the range is refused before any derivation, because `javax.crypto` PBKDF2 cannot be cancelled and a file
 * could otherwise ask for 2^32-1 iterations.
 */
internal object Pbkdf2 {
    const val ITERATIONS_FLOOR: Int = 600_000
    const val ITERATIONS_CEILING: Int = 10_000_000
    const val DEFAULT_ITERATIONS: Int = 600_000
    const val KEY_BYTES: Int = 32
    private const val ALGORITHM: String = "PBKDF2WithHmacSHA256"

    /** Whether a header's iteration count may be derived with. Check before calling [deriveKek]. */
    fun isAcceptableIterationCount(iterations: Long): Boolean = iterations in ITERATIONS_FLOOR..ITERATIONS_CEILING

    /** [passphrase] as NFC, as characters. The returned array is owned by the caller, who wipes it. */
    fun normalized(passphrase: CharArray): CharArray =
        Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()

    /**
     * The UTF-8 bytes of the NFC form of [passphrase], for tests of the frozen encoding.
     *
     * @throws IllegalArgumentException when the passphrase holds an unpaired surrogate (no UTF-8 form).
     */
    fun normalizedUtf8(passphrase: CharArray): ByteArray {
        val chars: CharArray = normalized(passphrase)
        try {
            return CharBuffer.wrap(chars).toStrictUtf8()
        } finally {
            chars.fill(' ')
        }
    }

    /**
     * Raw PBKDF2-HMAC-SHA256 of [password] (no normalisation, no range check: the known-answer tests use RFC 7914 counts).
     * Blocking and not cancellable; a caller runs it off the caller's thread.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, lengthBytes * 8)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The 32-byte key-encryption key of a passphrase store: NFC, then PBKDF2 with [salt] and [iterations].
     * [passphrase] is not modified and not wiped (the caller owns it); the normalised copy made here is wiped.
     *
     * @throws IllegalArgumentException for an empty passphrase, a passphrase with an unpaired surrogate (it has no UTF-8
     *   form, and the JDK would silently turn it into `?`, so two different passphrases would derive one key), a salt that
     *   is not 16 bytes, or a count outside the range.
     */
    fun deriveKek(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(passphrase.isNotEmpty()) { "an empty passphrase has no key" }
        require(salt.size == SecureFileFormat.SALT_BYTES) { "salt must be 16 bytes" }
        require(isAcceptableIterationCount(iterations.toLong())) { "iteration count out of range" }
        val normalized: CharArray = normalized(passphrase)
        try {
            // Strict check before the JDK sees the characters: PBEKeySpec encodes with replacement.
            CharBuffer.wrap(normalized).toStrictUtf8().fill(0)
            return derive(normalized, salt, iterations, KEY_BYTES)
        } finally {
            normalized.fill(' ')
        }
    }
}
