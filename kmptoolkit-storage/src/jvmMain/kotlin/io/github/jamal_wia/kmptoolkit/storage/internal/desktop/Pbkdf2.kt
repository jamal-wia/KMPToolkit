package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.CharBuffer
import java.security.GeneralSecurityException
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

    /** Every code point below this one is its own NFC form and cannot be affected by a neighbour (the first one that can is U+0300). */
    private const val FIRST_CHAR_THAT_CAN_CHANGE_UNDER_NFC: Char = '\u0300'

    /** Whether a header's iteration count may be derived with. Check before calling [deriveKek]. */
    fun isAcceptableIterationCount(iterations: Long): Boolean = iterations in ITERATIONS_FLOOR..ITERATIONS_CEILING

    /**
     * [passphrase] as NFC, as characters. The returned array is owned by the caller, who wipes it.
     *
     * Text in which every character is below U+0300 (ASCII, Latin-1 and Latin Extended text, the usual typed passphrase)
     * is already NFC: U+0300 is the first code point that can combine with a previous one or that has a different NFC
     * form, so such text is only copied into the returned array and no `String` is made. Any other text goes through
     * `java.text.Normalizer`, which works on immutable `String`s (it copies its input into one and returns another): those
     * copies of the passphrase cannot be wiped and stay on the heap until they are collected (the JDK has no normalizer
     * that works on a caller-owned buffer). Only the caller's array and this method's result array are wiped. (The JDK's PBKDF2
     * keeps a copy of the passphrase too, for every passphrase: see [derive].)
     */
    fun normalized(passphrase: CharArray): CharArray {
        if (passphrase.all { char: Char -> char < FIRST_CHAR_THAT_CAN_CHANGE_UNDER_NFC }) return passphrase.copyOf()
        return Normalizer.normalize(CharBuffer.wrap(passphrase), Normalizer.Form.NFC).toCharArray()
    }

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
     *
     * @throws IllegalStateException when the JDK has no PBKDF2 provider (the cause is the `GeneralSecurityException`).
     *
     * Not everything this call holds can be wiped. The JDK's key object (`PBKDF2KeyImpl`) clones [password] into a field of
     * its own and keeps the derived key in another, and clears both only when the object is garbage collected (a cleaner); no
     * public API clears them sooner, and `PBEKeySpec.clearPassword` wipes only the spec's copy. This holds for every
     * passphrase, ASCII included: until a collection the heap holds the NFC characters and the 32-byte key-encryption key
     * besides the arrays this class wipes. Re-implementing PBKDF2 over `Mac` would not help (HMAC keeps pads equivalent to
     * the key), so this is stated, not fixed.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, lengthBytes * 8)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } catch (error: GeneralSecurityException) {
            // A provider set without PBKDF2 (restricted or FIPS): an environment problem, not a wrong passphrase.
            throw IllegalStateException("The PBKDF2 provider failed", error)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The 32-byte key-encryption key of a passphrase store: NFC, then PBKDF2 with [salt] and [iterations].
     * [passphrase] is not modified and not wiped (the caller owns it); the normalised character array made here is wiped.
     * Text with a character from U+0300 up also passes through immutable `String`s of `Normalizer` that cannot be wiped (see [normalized]),
     * and the JDK's PBKDF2 keeps a copy of the passphrase and of the derived key until garbage collection for every passphrase (see [derive]).
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
