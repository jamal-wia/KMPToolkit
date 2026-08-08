package io.github.jamal_wia.kmptoolkit.storage

/**
 * A small, string-keyed, string-valued store whose contents survive a process restart.
 *
 * Backed by `SharedPreferences` on Android and an `NSUserDefaults` suite on iOS — see
 * `docs/kmptoolkit-storage/05-platform-notes.md`. Construct one through the platform factory
 * `createKeyValueStorage(...)` and pass the interface into shared code; shared code never names the
 * factory.
 *
 * The contract every implementation keeps:
 *
 * - **Absent and empty are different.** A key that was never written reads back as
 *   `StorageResult.Success(null)`; a key written with `""` reads back as
 *   `StorageResult.Success("")`.
 * - **[put] overwrites.** There is no insert-only variant and no failure on an existing key.
 * - **[remove] of an absent key succeeds** and changes nothing.
 * - **[clear] removes only this store's own entries.** It never touches another store built from a
 *   different [StorageConfig], and on iOS it never reaches the standard `NSUserDefaults` domain.
 * - **Nothing throws.** Platform trouble arrives as [StorageResult.Failure] with a typed
 *   [StorageError].
 * - **Writes are durable when the call returns.** A value written by [put] is readable by a second
 *   instance over the same configuration, including after the process is restarted.
 *
 * Not thread-safe in the sense of ordering: two threads writing the same key race, and which one
 * wins is undefined. Concurrent calls will not corrupt the store — the platform backends are
 * internally synchronized — but a caller that needs read-modify-write atomicity has to provide it.
 *
 * Nothing here is encrypted. For values that must not be readable from a device backup or a rooted
 * phone, use [SecureKeyValueStorage].
 */
public interface KeyValueStorage {

    /**
     * The value stored under [key], or `StorageResult.Success(null)` when the key is absent.
     *
     * @return [StorageResult.Failure] only when the store could not be read at all — see
     *   [StorageError]. Use [getStringOrNull] when an unreadable value and an absent one should be
     *   handled the same way.
     */
    public fun get(key: String): StorageResult<String?>

    /**
     * Stores [value] under [key], replacing any previous value.
     *
     * The value is readable by any other instance over the same [StorageConfig] once this returns.
     */
    public fun put(key: String, value: String): StorageResult<Unit>

    /** Removes [key]. Succeeds, and changes nothing, when the key was never written. */
    public fun remove(key: String): StorageResult<Unit>

    /**
     * Removes every entry this store owns.
     *
     * Scoped to this store's own [StorageConfig] — a second store configured with a different
     * `name` is unaffected, as is anything the app or another SDK wrote elsewhere.
     */
    public fun clear(): StorageResult<Unit>
}

/**
 * A [KeyValueStorage] whose values are encrypted at rest by the platform's own key store.
 *
 * The interface is deliberately identical to [KeyValueStorage] — the difference is a guarantee, not
 * a shape, so shared code can take either and a test can substitute one for the other. What the
 * guarantee covers, and equally what it does not, is stated in
 * `docs/kmptoolkit-storage/05-platform-notes.md`; the short version:
 *
 * - **Values are encrypted; key names are not.** A key name is a program constant, not a secret.
 * - **The encryption key never leaves the platform key store.** Android holds it in the
 *   AndroidKeyStore, iOS keeps the value itself in the Keychain. Neither is in the app's own files,
 *   so a copied data directory yields nothing usable.
 * - **A value can become permanently unreadable.** Changing the device lock destroys the Android
 *   Keystore entry; a read then fails with [StorageError.Undecryptable] rather than returning
 *   garbage. Any secret stored here must be re-acquirable — this is a place to cache a token, not
 *   the only copy of something a user cannot get back.
 *
 * Every operation is slower than the plain store's — a Keystore cipher round trip on Android, an
 * IPC hop to `securityd` on iOS. Store a handful of secrets in it, not a working set.
 */
public interface SecureKeyValueStorage : KeyValueStorage
