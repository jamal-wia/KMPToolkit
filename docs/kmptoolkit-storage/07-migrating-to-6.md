# kmptoolkit-storage — Migrating to 6.0.0

What changes for code written against 5.x, in the order you will meet it. Items 1 to 4 and 6 are compile errors or warnings you will be
shown (item 3 also changes behaviour); item 5 and items 7 to 11 are decisions or behaviour changes that no compiler points at. The full list is under `Breaking` in
`CHANGELOG.md`.

## 1. Exhaustive `when` over `StorageError`

`StorageError` gains `AccessDenied`, `Unsupported(reason)` and `InvalidValue`, and `Undecryptable.key` becomes `String?`. A `when` over the error needs the new
branches, each with a reaction:

<!-- sample: migrate-exhaustive-when -->
```kotlin
suspend fun react(storage: EncryptedKeyValueStorage, result: StorageResult.Failure) {
    when (val error: StorageError = result.error) {
        is StorageError.Unavailable -> retryLater()
        is StorageError.AccessDenied -> askAgainOnUserAction()          // never loop, never delete
        is StorageError.Unsupported -> fallBackExplicitly(error.reason)  // e.g. keep the session in memory
        is StorageError.Undecryptable -> when (val key: String? = error.key) {
            null -> storeLost()                       // the store cannot be read: sign in again (clear() then works, see 05-platform-notes)
            else -> storage.remove(key).also(::logIfFailure)
        }
        is StorageError.OperationFailed -> log(error)                   // retryable I/O
        is StorageError.InvalidValue -> dropValue(error.key)            // never retry the same value
    }
}
```

The `when (val key ...)` form avoids a smart cast on `error.key`, which Kotlin refuses for a public property declared in another module. The `.also(::logIfFailure)`
keeps the compiler's return-value checker satisfied (every `StorageResult`-returning function is annotated; a consumer who enables the checker gets a warning for an ignored result).

`StorageOperation` gains `ENSURE_KEY` and `CHANGE_KEY`, and `StorageOperation`, `UnsupportedReason` and `SecureStorageProtection` may grow in a minor version, so a `when` over any of them takes an `else`
that treats an unknown value as a failure to log, never as a reason to retry or to delete:

<!-- sample: migrate-operation-when -->
```kotlin
fun describe(operation: StorageOperation): String = when (operation) {
    StorageOperation.GET -> "read"
    StorageOperation.PUT -> "write"
    StorageOperation.REMOVE -> "remove"
    StorageOperation.CLEAR -> "clear"
    else -> "key management, or an operation added later"
}
```

## 2. Custom `SecureKeyValueStorage` implementations

`SecureKeyValueStorage` now extends `EncryptedKeyValueStorage`, which adds `protection` and `suspend fun ensureKey()`. A decorator or a hand-written fake must provide them. Kotlin's `by` delegation provides them
automatically; otherwise report what the wrapped store reports for `protection` and forward `ensureKey()`:

<!-- sample: migrate-delegation -->
```kotlin
class LoggingSecureStorage(private val delegate: SecureKeyValueStorage) : SecureKeyValueStorage by delegate {
    override suspend fun put(key: String, value: String): StorageResult<Unit> {
        log("put $key")
        return delegate.put(key, value)
    }
}
```

A plain `KeyValueStorage` can no longer stand in for a `SecureKeyValueStorage`: the two are no longer the same shape. `kmptoolkit-storage-testing`'s `InMemoryKeyValueStorage` gains the constructor parameters `protection` and `backing`
(source-compatible with `InMemoryKeyValueStorage()`; a binary break, so recompile) and implements `ensureKey()`.

## 3. iOS factory calls

`createSecureKeyValueStorage(config, accessibility, accessGroup)` becomes `createSecureKeyValueStorage(config, options: IosSecureStorageOptions)`:

<!-- sample: migrate-ios-factory -->
```kotlin
fun makeSharedSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage(
    StorageConfig("com.example.tokens"),
    IosSecureStorageOptions(
        accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK,
        accessGroup = "ABCDE12345.com.example.shared",
    ),
)
```

Handle `Unsupported(MISSING_ENTITLEMENT)` where `OperationFailed` used to signal an entitlement problem (a missing entitlement or an access group the app is not in, the Keychain's `-34018`), and `AccessDenied` for
a cancelled or failed authentication. A store that wants `synchronizable = true` must also pass `AFTER_FIRST_UNLOCK` or `WHEN_UNLOCKED` (the default accessibility is `THIS_DEVICE_ONLY`, which cannot sync).
Behaviour changes with no compile error: `accessibility` now applies on **every** write, so existing items take the configured class on their next write, and `clear()` now also removes the store's synchronizable
(iCloud Keychain) items and, with `accessGroup = null`, only the items in the app's default access group (it used to delete the store's items in every group the app belongs to). Reads and writes of a store with
`accessGroup = null` are not narrowed to the default group; see the known limit in [`04-api-reference.md`](04-api-reference.md#iossecurestorageoptions).

## 4. `Undecryptable.key`

`key` is `String?`. `null` means the whole store cannot be read (its key is lost or replaced, or the file does not parse): ask the user to sign in again. On an OS-key store `clear()` then works and the next `put` starts a
fresh key. Android and iOS keep naming the entry.

## 5. Code retrying `OperationFailed`

Typed reads (`getInt`, `getLong`, `getBoolean`) of an unparsable value and `put` of a key or value with an unpaired surrogate now fail with `InvalidValue` instead of `OperationFailed`. Stop retrying them: the same
value fails the same way. The cause of an `InvalidValue` names the rule and never the stored value.

## 6. `...Or` helpers on encrypted stores

`getStringOrNull`, `getStringOr`, `getIntOr`, `getLongOr` and `getBooleanOr` are deprecated (a warning) on an `EncryptedKeyValueStorage` receiver. Replace them with `get()` and explicit failure handling, so "unreadable"
is never read as "signed out". Tests that use the fixture as a plain store declare the variable as `KeyValueStorage`.

Also visible in tests: `StorageResult.Success.toString()` prints `Success(value=<redacted>)`. A failed `assertEquals` between two successes shows the same text for both; compare `.value`.

## 7. Desktop apps with their own encrypted file

Follow [the migration recipe](03-guide.md#recipe-migrating-from-an-app-encrypted-file): the new store is the truth; old copies are erased last, one key at a time (never `clear()` on a shared store), and a rerun
never copies over a value already in the new store; then rotate tokens server-side, because the old key sat next to the ciphertext.

## 8. Desktop JDK 21 apps

Add `kmptoolkit-storage-jna`, or package on JDK 22 or newer. Add `--enable-native-access=ALL-UNNAMED` to the app's `jvmArgs`: it is needed on JDK 22+ with FFM and on JDK 24+ with JNA, and it is accepted and harmless on JDK 21
([`02-getting-started.md`](02-getting-started.md#7-desktop-a-secure-store)).

## 9. Desktop files shared between OS users

This is no longer possible. Store files, temporary files and lock files are created owner-only (POSIX 0600; on Windows an ACL for the owner and SYSTEM), and directories the library creates are 0700; an existing directory is
never changed. On macOS the mode is the whole guarantee: an extended ACL entry that the parent directory marks inheritable is still inherited by every new file and directory (see the [platform notes](05-platform-notes.md#desktop)). An app that shared a store file with another OS user stops working: each user gets their own store. File systems without permissions (FAT, exFAT) are unchanged. Writes are now coordinated across processes through a
`<file>.lock` sibling, which a directory listing of a store directory shows; a write that cannot get the lock within 10 s answers `Unavailable`.

## 10. `kmptoolkit-files` callers

Recompile. `WriteOptions` gains the trailing field `ownerOnly` (a binary change of the constructor, `copy` and `componentN`) and `FileAccess.createDirectories` gains the parameter `ownerOnly`: implementations of
`FileAccess` add it. See [`../kmptoolkit-files/04-api-reference.md`](../kmptoolkit-files/04-api-reference.md).

## 11. Android

- `createSecureKeyValueStorage` gains `options: AndroidSecureStorageOptions`: source-compatible, a binary break for precompiled callers.
- **Do not downgrade the library below this version after shipping it**, or the secure values written since become unreadable: secure entries are written with a version byte that an older version cannot read.
- The first call after the upgrade that reaches the Keystore key re-encrypts the readable entries of the old format once; after that an entry without the version byte is `Undecryptable(key)`. See
  [`05-platform-notes.md`](05-platform-notes.md#the-android-failure-you-must-handle).
- After a permanent Keystore key invalidation the store generates a new key: old entries are `Undecryptable(key)`, and writes succeed (they used to be able to fail for ever).
