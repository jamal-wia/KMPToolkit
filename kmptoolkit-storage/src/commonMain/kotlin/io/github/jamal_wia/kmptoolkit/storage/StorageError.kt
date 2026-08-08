package io.github.jamal_wia.kmptoolkit.storage

/**
 * Why a [KeyValueStorage] operation failed.
 *
 * These are typed causes, not messages: nothing here is meant to be shown to a user. Mapping a
 * cause onto copy in the right language is the consuming app's job — see
 * `docs/01-architecture.md`.
 *
 * A plain [KeyValueStorage] over `SharedPreferences` or `NSUserDefaults` fails only under genuine
 * platform trouble (an unwritable data directory). A [SecureKeyValueStorage] has real, reachable
 * failure modes — a Keystore key destroyed by a lock-screen change, a Keychain that is unavailable
 * before first unlock — which is the reason every operation returns a [StorageResult] rather than a
 * bare value.
 */
public sealed interface StorageError {

    /**
     * The backing store could not be opened at all, so no key in it is readable or writable.
     *
     * On Android this is a Keystore provider that refuses to load or to generate a key; on iOS a
     * Keychain that is not available yet, which happens when code runs before the device's first
     * unlock after boot. Retrying later can succeed — nothing here says the data is gone.
     */
    public data class Unavailable(public val cause: Throwable? = null) : StorageError

    /**
     * [key] holds bytes that cannot be turned back into a value with the key material available
     * now. The entry exists; its plaintext does not.
     *
     * The usual cause is that the platform destroyed the encryption key while the ciphertext
     * survived: on Android the Keystore entry is dropped when the user adds, changes, or removes
     * the device lock, and an app restored to a new device from a backup carries the ciphertext
     * without the key. Treat the value as permanently lost — remove it and re-acquire whatever it
     * held. Only a [SecureKeyValueStorage] produces this.
     */
    public data class Undecryptable(
        public val key: String,
        public val cause: Throwable? = null,
    ) : StorageError

    /**
     * The store was open but [operation] itself failed — a write that could not be committed to
     * disk, a Keychain call that returned an unexpected `OSStatus`.
     *
     * @param key the entry the operation targeted, or `null` for [StorageOperation.CLEAR], which
     *   targets the whole store.
     * @param platformCode the raw platform status code where the platform reports failure as a
     *   number rather than an exception — an iOS Security-framework `OSStatus` such as `-25300`.
     *   `null` when the platform threw instead, in which case [cause] carries the detail.
     */
    public data class OperationFailed(
        public val operation: StorageOperation,
        public val key: String? = null,
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : StorageError
}

/** The operation an error refers to. Mirrors the methods of [KeyValueStorage]. */
public enum class StorageOperation {

    /** [KeyValueStorage.get]. */
    GET,

    /** [KeyValueStorage.put]. */
    PUT,

    /** [KeyValueStorage.remove]. */
    REMOVE,

    /** [KeyValueStorage.clear]. */
    CLEAR,
}
