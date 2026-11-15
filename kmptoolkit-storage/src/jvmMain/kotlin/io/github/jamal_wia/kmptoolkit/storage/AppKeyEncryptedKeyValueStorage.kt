package io.github.jamal_wia.kmptoolkit.storage

/**
 * A desktop store whose data key is wrapped by a key the app provides ([AppEncryptionKey]): a passphrase or a raw 256-bit
 * key. It is NOT a [SecureKeyValueStorage]: no operating-system key store is involved, so it never claims OS custody.
 * [protection] is [SecureStorageProtection.PASSPHRASE] or [SecureStorageProtection.APP_PROVIDED_KEY].
 *
 * A wrong key answers [StorageError.AccessDenied] on every operation, including a `get` of an absent key and `clear()`, with
 * nothing read or changed: a wrong passphrase never reads as "absent" and never as [StorageError.Undecryptable], so the
 * "remove on Undecryptable" recipe never fires on a typo. A forgotten passphrase has no reset in the API: the app deletes
 * the store's files and starts over.
 *
 * The interface carries its own `@MustUseReturnValues` (a parent's annotation does not cover the members of a sub-interface),
 * so a dropped [changeKey] result is reported by the compiler's return-value checker.
 */
@MustUseReturnValues
public interface AppKeyEncryptedKeyValueStorage : EncryptedKeyValueStorage {

    /**
     * Re-encrypts the store under a fresh data key wrapped by [newKey], atomically: a new salt, a new key-check, every value
     * re-encrypted. The current key must verify first ([StorageError.AccessDenied] otherwise, nothing changed). A crash
     * leaves either the old or the new file, both complete. Copies of the file taken before the change stay readable with
     * the old key; they are not re-encrypted. Failures are reported with [StorageOperation.CHANGE_KEY].
     *
     * Another instance over the same file that still holds the old key answers [StorageError.AccessDenied] on its next
     * operation; the instance that ran this call is bound to [newKey] from now on.
     */
    public suspend fun changeKey(newKey: AppEncryptionKey): StorageResult<Unit>
}
