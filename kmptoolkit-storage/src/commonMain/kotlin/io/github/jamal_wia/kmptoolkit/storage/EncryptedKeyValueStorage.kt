package io.github.jamal_wia.kmptoolkit.storage

/**
 * A [KeyValueStorage] whose values are encrypted at rest under a key that is never stored in
 * plaintext beside them; every operation on existing data first resolves and verifies that key.
 *
 * This is the parent of every encrypted store the suite offers: [SecureKeyValueStorage] (an OS key
 * store holds the key) and, on desktop, the app-keyed store (the app supplies the key). Shared code
 * that needs "encrypted, either kind" takes this type — it still excludes plain stores, so a
 * parameter of this type can never be handed plaintext storage by mistake — and reads [protection]
 * to learn who holds the key.
 *
 * The additions over [KeyValueStorage] are a guarantee, [protection], and one operation,
 * [ensureKey], that lets the app choose when the key is resolved. Everything else is the plain
 * contract: absent and empty are different, `put` overwrites, `remove` of an absent key succeeds,
 * `clear` removes only this store's entries, nothing throws.
 *
 * Encrypted stores add failure modes a plain store does not have, all of them typed
 * ([StorageError.Unavailable], [StorageError.AccessDenied], [StorageError.Unsupported],
 * [StorageError.Undecryptable]). Reading a result as "absent" when it is one of those — which is what
 * the `...Or` helpers do — turns "unreadable" into "signed out"; those helpers are deprecated on this
 * type for that reason. Use `get` and handle [StorageResult.Failure].
 */
@MustUseReturnValues
public interface EncryptedKeyValueStorage : KeyValueStorage {

    /**
     * The lower-bound guarantee of who holds this store's key and who is kept out. Constant for the
     * instance and known without I/O.
     */
    public val protection: SecureStorageProtection

    /**
     * Resolves — on a store that has none yet, creates — and verifies the key now, so the app chooses
     * when the one OS prompt or passphrase check happens instead of meeting it at an arbitrary later
     * write.
     *
     * On the first run the value is absent, so the first `get` fetches no key at all; calling this
     * right after explaining the prompt to the user is the way to place it. Never falls back to a
     * weaker key. Does not mutate the store on iOS, where one Keychain item holds each value and there
     * is no key to create. Every other operation still verifies the key itself, so skipping this call
     * is always safe — it only moves the prompt.
     *
     * @return [StorageResult.Success] when the key is available and verified; otherwise the typed
     *   failure the store's other operations would answer, with no effect on the stored values.
     */
    public suspend fun ensureKey(): StorageResult<Unit>
}
