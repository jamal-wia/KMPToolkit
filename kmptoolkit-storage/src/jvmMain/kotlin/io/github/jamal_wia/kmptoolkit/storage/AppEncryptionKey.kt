package io.github.jamal_wia.kmptoolkit.storage

import kotlin.coroutines.cancellation.CancellationException

/**
 * Where an app-keyed store's key comes from ([createAppKeyEncryptedKeyValueStorage]). The app owns the secret; the library
 * never chooses a key source and never keeps a key beyond the derived data key in memory.
 *
 * Providers are called on first use and at most once per operation after a refusal; every array a provider returns is wiped
 * as soon as it has been used, so a provider returns a fresh array on every call. A provider that throws is
 * [StorageError.Unavailable] with the exception as the cause (no key was supplied; retrying later can succeed), except
 * [CancellationException], which propagates. Neither type reveals its secret in `toString`.
 */
public sealed interface AppEncryptionKey {

    /**
     * A key derived from a passphrase with PBKDF2-HMAC-SHA256 over the Unicode-NFC-normalised UTF-8 passphrase and a
     * 16-byte random salt. The same text typed composed or decomposed opens the same store.
     *
     * @param provider supplies the passphrase. An empty one is [StorageError.AccessDenied].
     * @param iterations the PBKDF2 iteration count for NEW or RE-KEYED files, `600_000..10_000_000`. The header's own count
     *   wins when an existing file is opened, within that same range; a header outside it is [StorageError.Undecryptable]
     *   with no derivation run.
     * @throws IllegalArgumentException when [iterations] is outside `600_000..10_000_000`.
     */
    public class Passphrase(
        public val provider: suspend () -> CharArray,
        public val iterations: Int = DEFAULT_ITERATIONS,
    ) : AppEncryptionKey {
        init {
            require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) {
                "iterations must be in $MIN_ITERATIONS..$MAX_ITERATIONS, was $iterations"
            }
        }

        override fun toString(): String = "AppEncryptionKey.Passphrase"

        private companion object {
            const val DEFAULT_ITERATIONS: Int = 600_000
            const val MIN_ITERATIONS: Int = 600_000
            const val MAX_ITERATIONS: Int = 10_000_000
        }
    }

    /**
     * A raw 256-bit key that comes from outside the store's directory (a systemd credential, a container secret, a KMS, a
     * hardware token).
     *
     * @param provider supplies exactly 32 bytes; any other length is [StorageError.AccessDenied].
     */
    public class Raw(
        public val provider: suspend () -> ByteArray,
    ) : AppEncryptionKey {
        override fun toString(): String = "AppEncryptionKey.Raw"
    }
}
