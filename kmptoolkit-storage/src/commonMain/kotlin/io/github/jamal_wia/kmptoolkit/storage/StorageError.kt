package io.github.jamal_wia.kmptoolkit.storage

/**
 * Why a [KeyValueStorage] operation failed.
 *
 * These are typed causes, not messages: nothing here is meant to be shown to a user. Mapping a
 * cause onto copy in the right language is the consuming app's job — see
 * `docs/01-architecture.md`.
 *
 * A plain [KeyValueStorage] over `SharedPreferences` or `NSUserDefaults` fails only under genuine
 * platform trouble (an unwritable data directory). An [EncryptedKeyValueStorage] has real, reachable
 * failure modes — a Keystore key destroyed by a lock-screen change, a Keychain that is unavailable
 * before first unlock, a prompt the user dismissed — which is the reason every operation returns a
 * [StorageResult] rather than a bare value.
 *
 * Each case has exactly one reaction an app should take:
 *
 * | Case | Reaction |
 * |---|---|
 * | [Unavailable] | Retry later; nothing is lost. |
 * | [AccessDenied] | Retry only on a user action; never in a loop, never delete. |
 * | [Unsupported] | Retrying is pointless; tell the user or choose another store explicitly. |
 * | [Undecryptable] | Remove the affected key (or every key when it is `null`) and re-acquire the value. |
 * | [OperationFailed] | Log it; the same call may succeed on retry. |
 * | [InvalidValue] | Never retry the same value; drop it or fix the caller. |
 *
 * ### Matching
 *
 * Match this interface exhaustively, with no `else` branch: a new case is added only in a major
 * version, listed under `Breaking` in the changelog, so that the compiler points at every `when` that
 * needs a decision. An `else` would silently send a future case into the retry or the delete branch.
 * The descriptive enums ([UnsupportedReason], [SecureStorageProtection], [StorageOperation]) may grow
 * in a minor version instead; a `when` over them takes an `else` that fails closed.
 */
public sealed interface StorageError {

    /**
     * The store cannot be used right now, and retrying later can succeed; nothing is lost. Examples: a
     * keychain that is locked and cannot show UI, a key-store daemon that is still starting, a lock
     * wait or an OS call that ran into its time limit, a device that is locked, a backing store that
     * cannot be opened yet (on iOS, code that runs before the device's first unlock after boot; on
     * Android, a Keystore provider that refuses to load or to generate a key).
     *
     * Nothing here says the data is gone, so never delete anything in reaction to this case. A user
     * refusal is [AccessDenied], not this case, and an environment that can never work is
     * [Unsupported].
     *
     * On Android 13+ (API 33) it is also what a read reports when decrypting failed with an
     * `android.security.KeyStoreException` that the platform itself calls transient (found anywhere
     * in the cause chain); the entry is intact and the same read can succeed a moment later.
     */
    public data class Unavailable(public val cause: Throwable? = null) : StorageError

    /**
     * The store refused this call now: a prompt the user denied or dismissed, an access-control rule,
     * or — on a store whose key the app supplies — a key that does not open the store.
     *
     * Nothing is lost and nothing was changed. Retry only on a user action (the user tapped the
     * button again, the app asked its key provider again); a retry prompts again, so retrying in a
     * loop spams the user. Never delete anything in reaction to this case: the data is intact and the
     * caller is merely not allowed in.
     *
     * @param platformCode the raw platform status where the platform reports refusal as a number
     *   (an iOS `OSStatus` such as `-128`, `errSecUserCanceled`), or `null` when there is none.
     * @param cause the underlying exception, if any.
     */
    public data class AccessDenied(
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : StorageError

    /**
     * This store cannot work in this environment, for [reason]; retrying will not help.
     *
     * Tell the user what is missing or choose another store explicitly — never fall back silently to
     * something weaker. [reason] names the single fix: a missing entitlement, a runtime without
     * native-access support, a device without the required secure hardware.
     *
     * @param reason why the store is unsupported here; see [UnsupportedReason].
     * @param cause the underlying exception, if any.
     */
    public data class Unsupported(
        public val reason: UnsupportedReason,
        public val cause: Throwable? = null,
    ) : StorageError

    /**
     * Data cannot be decrypted with the key available now: the entry [key], or the whole store when
     * [key] is `null` (the store's key is gone or has been replaced).
     *
     * The usual cause is that the platform destroyed the encryption key while the ciphertext
     * survived: on Android the Keystore entry is dropped when the user adds, changes, or removes
     * the device lock, and an app restored to a new device from a backup carries the ciphertext
     * without the key. Treat the value as permanently lost — remove the affected keys (or call
     * [KeyValueStorage.clear]) and re-acquire whatever they held. On an OS-key store `remove` and
     * `clear` always work, even when [key] is `null`, and the next `put` or
     * [EncryptedKeyValueStorage.ensureKey] starts a fresh key. Only an [EncryptedKeyValueStorage]
     * produces this.
     *
     * @param key the entry that cannot be read, or `null` when the whole store cannot be (Android and
     *   iOS always name the entry; a store with one key for all its values reports `null` when that
     *   key is lost).
     * @param cause the underlying exception, if any.
     */
    public data class Undecryptable(
        public val key: String?,
        public val cause: Throwable? = null,
    ) : StorageError

    /**
     * The store was usable but [operation] itself failed on I/O or an unexpected platform status — a
     * write that could not be committed to disk, a Keychain call that returned an unexpected
     * `OSStatus`. The call may succeed on retry; log it.
     *
     * A value the store can never accept is not this case but [InvalidValue], so a caller that
     * retries [OperationFailed] never retries one forever.
     *
     * @param key the entry the operation targeted, or `null` for [StorageOperation.CLEAR],
     *   [StorageOperation.ENSURE_KEY] and [StorageOperation.CHANGE_KEY], which target the whole store.
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

    /**
     * The value under [key] can never be accepted or parsed: a typed read (`getInt`, `getLong`,
     * `getBoolean`) of a stored value that is not of that type, or a `put` of a key or value holding
     * an unpaired UTF-16 surrogate.
     *
     * Never retry the same value: the outcome cannot change. For a typed read it means two callers
     * disagree about the key's type, which is a bug worth seeing; [KeyValueStorage.remove] drops the
     * stored value. For a `put` nothing was changed.
     *
     * @param key the key the rejected value was read from or written to.
     * @param cause the rejection reason, if any. It names the key's expected type or the rule that was
     *   broken and never contains the value, so it is safe to log even for an encrypted store.
     */
    public data class InvalidValue(
        public val key: String,
        public val cause: Throwable? = null,
    ) : StorageError
}

/**
 * The operation an error refers to: the methods of [KeyValueStorage] and [EncryptedKeyValueStorage]
 * and the key-management calls of the stores that have them.
 *
 * A machine token, never display text. The set may grow in a minor version, so give a `when` over it
 * an `else` that treats an unknown operation as a plain failure to log (never as a reason to retry or
 * to delete anything).
 */
public enum class StorageOperation {

    /** [KeyValueStorage.get]. */
    GET,

    /** [KeyValueStorage.put]. */
    PUT,

    /** [KeyValueStorage.remove]. */
    REMOVE,

    /** [KeyValueStorage.clear]. */
    CLEAR,

    /**
     * [EncryptedKeyValueStorage.ensureKey], and the queries that ask the same question — is the key
     * store usable (`probeDesktopKeyStore()`, `androidKeySecurityLevel()`): their I/O or key-info
     * failure is reported with this operation and `key = null`.
     */
    ENSURE_KEY,

    /** `AppKeyEncryptedKeyValueStorage.changeKey`: re-encrypting the store under a new application key. */
    CHANGE_KEY,
}

/**
 * Why a store is [StorageError.Unsupported] here: one entry per distinct fix.
 *
 * Machine tokens, never display text. The set may grow in a minor version, so give a `when` over it an
 * `else` that refuses (shows a generic "not available here", never retries).
 */
public enum class UnsupportedReason {

    /**
     * No logged-in user session: no D-Bus session bus, or a Windows logon without user credentials
     * (a service, a key-based ssh session).
     */
    NO_USER_SESSION,

    /**
     * A session exists but no key store serves it: no Secret Service provider, `libsecret` missing,
     * no default keychain.
     */
    NO_KEY_STORE,

    /** JDK 21 without `kmptoolkit-storage-jna`: run on JDK 22 or newer, or add that artifact. */
    UNSUPPORTED_RUNTIME,

    /**
     * Native code cannot load or is not permitted (`--illegal-native-access=deny`, a `noexec`
     * temporary directory for JNA).
     */
    NATIVE_ACCESS_UNAVAILABLE,

    /** The app lacks an entitlement or signature the store needs (Apple `-34018`, an access group not granted). */
    MISSING_ENTITLEMENT,

    /** A desktop OS other than macOS, Windows or Linux. */
    UNSUPPORTED_OS,

    /** Required secure hardware is absent (`AndroidKeyBacking.REQUIRE_STRONGBOX` on a device without StrongBox). */
    HARDWARE_UNAVAILABLE,
}
