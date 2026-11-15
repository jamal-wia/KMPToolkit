# kmptoolkit-storage — Platform notes

What is actually on disk, why the crypto looks the way it does, and the two platform traps that
shaped this module's implementation.

## Permissions and app configuration

**None.** No Android permission is declared or required, nothing goes into `Info.plist`, and no
entitlement is needed for the default configuration.

The one exception is opt-in and yours: passing `accessGroup` in `IosSecureStorageOptions` to the iOS
`createSecureKeyValueStorage` requires a matching **Keychain Sharing** entitlement in your app. The
library cannot check that for you — a group you do not have makes every operation fail with
`StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)` (the Keychain status `-34018`).

Following the repository rule in `docs/01-architecture.md`, this module declares nothing in its own
`AndroidManifest.xml`. There is, in fact, no manifest to declare it in.

## Where your data actually lives

Every identifier is derived from `StorageConfig.name`, which defaults to your application id. Given
a name `N`:

| | Android | iOS | Desktop (`jvm`) |
|---|---|---|---|
| Plain store | `SharedPreferences` file `N.kmptoolkit.storage` | `NSUserDefaults` suite `N.kmptoolkit.storage` | file `N.kmptoolkit.storage.properties` in the directory you pass |
| Secure store | `SharedPreferences` file `N.kmptoolkit.securestorage` | Keychain `kSecAttrService` = `N.kmptoolkit.securestorage` | none — see below |
| Encryption key | AndroidKeyStore alias `N.kmptoolkit.securestorage.key` | held by `securityd`; no alias of ours | — |

On desktop `N` has no default — there is no application id to derive it from — so
`StorageConfig.name` is required there.

These strings are an implementation detail — they are documented so you can find your own data with
`adb shell` or a Keychain dump, not so you can depend on them. They are also the reason
`StorageConfig` exists at all: nothing here is a constant baked into the library, so two apps, or
two KMPToolkit-based libraries in one process, cannot collide.

The suffix on the plain store is load-bearing on iOS. `NSUserDefaults(suiteName:)` returns the
**standard** defaults when the suite name equals the app's bundle identifier, and this module's
`clear()` is `removePersistentDomainForName` — on the standard domain that would wipe everything the
app and every embedded SDK ever wrote. Because the suffix is always appended, the resolved suite name
can never equal the bundle id, even if you pass it explicitly as `name`.

`NSUserDefaults` treats one key name specially: a string stored under `AppleLanguages` (the preferred languages, an array
the system owns) never reads back, so the plain iOS store refuses `put` for that key with
`StorageError.OperationFailed(PUT)` instead of answering `Success` and then `null`. Android and the JVM accept the name.

## Android: why not Tink, and why not `EncryptedSharedPreferences`

The code this module was ported from used **Tink** (`com.google.crypto.tink:tink-android`) with an
`AndroidKeysetManager`: a keyset stored in its own preferences file, wrapped by an Android Keystore
master key, encrypting values with AES-256-GCM. This module instead calls the Android Keystore
directly — `KeyGenParameterSpec` for an AES-256 key, `Cipher("AES/GCM/NoPadding")` for the values,
`Base64(iv ‖ ciphertext‖tag)` on disk.

The three candidates and what decided it:

| Option | Cost to a consumer | Status | Verdict |
|---|---|---|---|
| `tink-android` | roughly **1 MB** of APK, plus a protobuf runtime, in every consumer whether or not they use the secure store | healthy, actively maintained | rejected on size |
| `androidx.security:security-crypto` | similar — it depends on Tink itself | `MasterKey` / `EncryptedSharedPreferences` **deprecated upstream since 1.1.0-alpha06, with no replacement shipped** | rejected on both |
| AndroidKeyStore + `Cipher` | **zero** added dependencies | platform API, available since API 23; this module's `minSdk` is 24 | **chosen** |

The reasoning, stated plainly because a consumer deserves to know why their APK would have grown:

- **A megabyte is a lot to pay for an abstraction over an API we then call anyway.** Tink's value is
  its key management — key rotation, keysets with multiple versions, an envelope format that
  survives an algorithm migration. This module stores a handful of short-lived tokens under a single
  key, and none of that machinery is exercised. Tink would have added weight to every consumer,
  including the majority who use only the plain store, in exchange for capability nobody here uses.
- **The deprecated option is not an option.** `androidx.security-crypto`'s replacement was never
  shipped; building a library's security story on an API its own maintainers have abandoned is a
  migration scheduled for an inconvenient date.
- **The direct implementation is small enough to read.** It is one key, one transformation, one
  framing: the cipher itself is about a hundred lines (`ValueCipher.kt`, with its key source and errors, a few
  hundred) — small enough that a consumer auditing what happens to their
  tokens can finish in a sitting, which is not true of Tink.

What this trade costs, honestly:

- **No key rotation and no versioned envelope.** Changing the algorithm later means every existing
  entry reads back as `Undecryptable`. Acceptable because that is already a reachable state (see
  below) and every consumer must handle it; not acceptable for data a user cannot re-acquire, which
  this store is not for.
- **No FIPS story, no key-hierarchy features.** If you need those, you need Tink, and you should
  build on it directly rather than through this module.
- **We own the framing.** IV length, tag length and Base64 flags are ours to get right, and are
  pinned by tests in `KeystoreValueCipherTest` for exactly that reason.

Two further choices inside that implementation:

- **`setUserAuthenticationRequired` is deliberately left off.** An auth-bound key would make every
  read fail outside a recent unlock, which breaks a cold start and a background sync. Biometric
  gating is a different feature with a different key, bound to your own auth flow.
- **Hardware backing is neither required nor checked.** On a device with a TEE or StrongBox the
  platform uses it; on one without, the same API gives a software-isolated key. Refusing to store a
  token on the weaker device would only push you into storing it somewhere worse.

### The Android failure you must handle

Since 5.0.0 a secure value is also bound to the key name it is stored under: the key name is authenticated
associated data of the AES-GCM encryption, so a ciphertext copied from one key to another in the preferences file
(for example the access token over the password) fails its tag and reads as `Undecryptable`. Nothing written earlier
becomes unreadable: an entry that fails the bound check is read once more as an unbound one, which is what 4.0.0 and
earlier wrote. Such an old entry stays movable between keys until it is written again. The format on disk is unchanged
(`Base64(iv ‖ ciphertext‖tag)`), but a downgrade to 4.0.0 cannot read entries written by 5.0.0.

The Keystore entry is destroyed when the user **adds, changes, or removes the device lock**, and an
app restored to a new device from a backup carries the ciphertext without the key. In both cases the
value is gone while the entry remains, and reads return `StorageError.Undecryptable`. The module
recovers as far as it can — an alias whose key has become unrecoverable is dropped and a new key
generated, so *new* writes work again instead of the store being bricked for the life of the install
— but the old values are not recoverable by anyone.

A decrypt that fails for a *transient* keystore reason is the exception: when anything in the
failure's cause chain is an `android.security.KeyStoreException` whose `isTransientFailure()` is
`true` (a keystore daemon restarting, a provider briefly busy), the read returns
`StorageError.Unavailable` — retry later, nothing is lost — instead of `Undecryptable`. The cause
chain is walked because the platform usually delivers that exception wrapped in an
`InvalidKeyException` or a `ProviderException`. **This is API 33+ only**: `isTransientFailure()` does
not exist below it, so on API 24–32 every decrypt failure other than an unreachable key store is
still `Undecryptable`, as before. A failure the platform does not call transient is never remapped.

The same rule protects the key itself: a transient fault never makes the module drop its cached key
and look the alias up again, and a transient `UnrecoverableKeyException` during that lookup never
deletes the alias. The unrecoverable-alias recovery (delete and generate) runs only on the first key
resolution in a process. A key the platform rejects later is forgotten and looked up again once, but
that lookup never deletes: if it hits an unrecoverable entry — which on API 24-32 a keystore fault
also looks like, since nothing there can name a transient failure — the call fails with
`StorageError.Unavailable` and the next process start recovers a key that really died. A read that
hits a transient fault reports `Unavailable`; a write reports `OperationFailed`. The key and the
stored values are kept either way, so a momentary outage costs a retry, never the stored values.

### The key is created once per process, never once per caller

The key is generated on first use, and "look the alias up, generate if it is missing" is two
Keystore calls. Generating under an alias that already exists does not fail — it replaces the entry.
Before 3.0.0 nothing ordered those two calls, so two first uses that overlapped — two coroutines on
a fresh install, or two stores over the same configuration — could each generate a key; the later
one won the alias, and every value encrypted under the earlier one became `Undecryptable`. The same
gap existed in the recovery from a changed device lock, where a late caller could delete the
replacement key another caller had just made.

The lookup now runs under one lock for the whole process, shared by every store instance, and asks
the Keystore again once it holds the lock — so the second caller finds the first caller's key. The
lock is held only while a key is resolved, once per store instance.

**One process per store.** An in-process lock cannot order two *processes*, and the AndroidKeyStore
has no atomic create-if-absent. If your app runs components in a second process
(`android:process`), do not give that process the same secure store, or make sure the main process
has written to it at least once before the other one can start.

### Writes use `commit()`, not `apply()`

`apply()` returns before the write reaches disk, which would quietly break the durability half of
the contract — a token written immediately before the process is killed would be gone — and it has
no way to report failure, which would make `StorageError.OperationFailed` unreachable. The cost is a
blocking write of a few hundred bytes.

## iOS: the Keychain query must not be a bridged Kotlin `Map`

This is the trap that shaped `KeychainQuery.kt`, and it is worth stating precisely.

The obvious Kotlin/Native spelling of a `SecItem*` call builds an `NSMutableDictionary` (or a Kotlin
`Map`) and bridges it with `CFBridgingRetain(...) as CFDictionaryRef`. It is what most sample code
does, it is what the donor implementation this module was ported from did, and **on iOS 26 the
Security framework rejects it with `errSecParam` (`-50`)**.

What makes it expensive is the symptom rather than the cause: the rejection arrives as a status code
on a call nobody expects to fail, so an app that ignores the status sees a Keychain that is
mysteriously always empty, or — when the failure lands inside a startup path that waits on a token —
a hang on the splash screen with no error anywhere.

This module therefore builds every query with `CFDictionaryCreateMutable` and adds entries
individually with `CFDictionaryAddValue`:

```kotlin
KeychainQuery()
    .apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        putString(kSecAttrAccount, key)
    }
    .use { SecItemCopyMatching(it, result.ptr) }
```

`kSec*` globals are already `CFTypeRef`s and are passed straight through; our own strings and data
are bridged with `CFBridgingRetain` and released by `KeychainQuery.use`, which balances every
retain and releases the dictionary. `IosSecureKeyValueStorageTest` pins the behavior by asserting
that a query built this way is **not** answered with `errSecParam`.

If you ever edit that file: do not "simplify" it back to a dictionary literal.

### Other iOS specifics

- **Items outlive an app uninstall.** iOS does not remove an app's Keychain items when the app is
  deleted, so a reinstall can read a token written by the previous install. If that is wrong for
  you, clear the secure store on first run, keyed off a flag in the plain store — which *is* removed
  with the app.
- **`put` updates before it adds.** `SecItemAdd` on an existing item fails with `errSecDuplicateItem`
  rather than replacing it, so an unconditional add would make `put` non-overwriting. Every write
  carries the configured accessibility, the update of an existing item included: a changed
  `IosSecureStorageOptions.accessibility` reaches each item on its next `put`, and an item that is never
  written again keeps the class it was stored with (`clear()` moves everything at once). Another writer or
  remover (a second coroutine, a second store over the same service, an app extension in the access group) can
  change the item between the update and the add, so a miss is retried: update, then add, up to eight rounds
  (`MAX_WRITE_ATTEMPTS`), each lost round meaning someone else made progress. A `put` therefore never fails
  because it raced another write or a `remove`; it gives up with `OperationFailed(PUT, key, -25299)` only after
  eight lost rounds in a row, and never reports a value that is not stored.
- **`clear` deletes by service, in one access group.** One `SecItemDelete` matching the store's
  `kSecAttrService`, no account, `kSecAttrSynchronizable = kSecAttrSynchronizableAny` (local and iCloud
  items alike) and exactly one `kSecAttrAccessGroup`: the one in `IosSecureStorageOptions.accessGroup`, or,
  when that is `null`, **the app's default access group**. A delete that names no group matches every group
  the app belongs to, so it would also remove a same-named store's items in a shared group; the library
  therefore resolves the default group first. It does so with a probe item (service
  `<store service>.accessgroupprobe`, account `kmptoolkit.accessgroupprobe.<random UUID>`, a new account on every
  call) that it adds without a group and reads the group of from the answer, then removes inside the same
  `clear()`. The unique account is what keeps the group honest: no lookup is made, so a probe that another
  process left in its own default group (an app extension killed mid-`clear()`) is never mistaken for this app's, and
  concurrent `clear()` calls cannot collide on the probe. The removal names the probe service and the resolved
  group but no account, so it also sweeps a leftover of this app's own default group; one in another group is left
  alone. If the group cannot be resolved (for example before the first unlock, when the probe cannot be added),
  `clear()` fails and deletes nothing; when the probe was added but the answer named no (or a blank) group, the
  probe is deleted by its own unique account, with no group, before the failure is returned, so a failed `clear()`
  never leaves a probe behind. Items
  written by another store, another framework, or the same app under a different service are not matched.
  (Changed in 6.0.0: earlier versions sent no group and no synchronizable attribute, so they could also
  remove a shared-group store's items and missed the store's own iCloud items.)
- **Known limit: with `accessGroup = null`, `get`, `put` and `remove` address every access group the app can
  reach.** Only `clear()` is pinned to the default group. A query that names no group matches items in every group
  of the app, so when a store with `accessGroup = null` and a same-named store in a shared group (another
  `accessGroup`, or an app extension) exist side by side, the first one's `get` can return the second one's value,
  its `remove` can delete it, and its `put` can overwrite it instead of adding to its own group. Reads and
  writes are not narrowed to the default group on purpose: items created under an earlier default group (the
  `keychain-access-groups` order changed between releases) would then become invisible, which is a data loss.
  When several stores share a keychain group, give each an explicit `accessGroup`, or distinct store names.
- **Reads and writes name the exact `synchronizable` value**, so a store only ever sees its own kind of
  item. `synchronizable = true` needs a portable accessibility (`WHEN_UNLOCKED` or `AFTER_FIRST_UNLOCK`),
  because iOS does not sync a `THIS_DEVICE_ONLY` item; the options class rejects the combination, and the
  default accessibility is `THIS_DEVICE_ONLY`, so pass both. Synchronizable items live in iCloud Keychain
  and leave the device, and an accessibility without `THIS_DEVICE_ONLY` lets an encrypted backup carry
  an item too.
- **`ensureKey()` never changes anything and never prompts.** It is an attribute-only
  `SecItemCopyMatching` for the store's service (no data requested), with `kSecUseAuthenticationUI` set to
  fail and `kSecAttrSynchronizable = Any`. No items is `Success`: there is no key to create. For a
  `userPresence` store that already holds items, whether the query answers `Success` or `errSecInteractionNotAllowed`
  (`Unavailable`) is **not observed on a device**: the `ensure-key-user-presence` case of the harness records it, and
  the mapping follows what it shows. Failures report `StorageOperation.ENSURE_KEY` with no key.
- **User presence (`IosSecureStorageOptions.userPresence`).** Items are stored with a `SecAccessControl`
  (`.userPresence`, `.biometryAny` or `.biometryCurrentSet`) instead of `kSecAttrAccessible`, protected by
  the configured accessibility class, and the sheet's reason is the app's `prompt`, passed through a fresh
  `LAContext` for each operation. iOS keeps one item per key, so the sheet shows on **every** `get`; an
  in-memory cache in the app is the answer, as for any prompting store. Replacing an existing item may show
  it too. Cancelling the coroutine that waits for the sheet dismisses it (the `LAContext` is invalidated).
  Changing `userPresence` for keys that already exist needs a `clear()`: an item cannot swap between
  an accessibility and an access control. **Not observed on a device:** the sheet, the exact status after
  a biometry re-enrollment, and whether an overwrite prompts are recorded by the `storage-ios-harness`
  module, which the owner runs; the notes here are updated with what it observes.
- **Statuses that are translated:**

  | Status | Result |
  |---|---|
  | `errSecItemNotFound` on `get` | `Success(null)` |
  | `errSecNotAvailable` (`-25291`), `errSecInteractionNotAllowed` (`-25308`) | `Unavailable` |
  | `errSecUserCanceled` (`-128`) | `AccessDenied(-128)` |
  | `errSecAuthFailed` (`-25293`) | `AccessDenied(-25293)` |
  | `errSecMissingEntitlement` (`-34018`), an access group the app is not in | `Unsupported(MISSING_ENTITLEMENT)` |
  | `errSecDecode` (`-26275`) | `Undecryptable(key)`; `Undecryptable(null)` for `ensureKey` and `clear` |
  | an unpaired surrogate in a key or value | `InvalidValue(key)`, before any Keychain call |
  | anything else | `OperationFailed(operation, key, platformCode)` |

  A `WHEN_PASSCODE_SET_THIS_DEVICE_ONLY` item is deleted by iOS when the passcode is removed, so a later
  read answers `Success(null)`, exactly as for a key that was never written.
- **The Keychain itself is not exercised by the unit tests.** A Kotlin/Native test binary has no usable
  Keychain; the store's logic runs against an in-memory model of the rules above, and everything only a
  device decides (default group resolution, synchronizable and group behaviour, accessibility on update,
  `-34018`, user presence) is a case of the `storage-ios-harness` module.
- **The plain store's writes cannot fail.** `NSUserDefaults` reports no status, so those operations
  always return `Success`. That is not a shortcut — there is genuinely no error to surface, and the
  result type exists for the Keychain store's sake. (The secure store's writes can fail: see the table.)

## Desktop

The `jvm` target exists because `KeyValueStorage` is a type shared code takes as a parameter, and an
app sharing that code with desktop could not compile it for desktop without the interface — see
[`../01-architecture.md`](../01-architecture.md#desktop-targets).

- **Where.** `createKeyValueStorage(directory, config)` writes one properties file,
  `<name>.kmptoolkit.storage.properties`, into the directory you choose. The library does not pick a
  directory for you: where an app keeps its files on desktop is the app's decision and differs per
  operating system. The directory is created on the first write.
- **Why a file and not `java.util.prefs`.** `Preferences` caps a value at 8 KB and a key at 80
  characters, which would break this module's contract that a long value round-trips, and it lives in
  the Windows registry on one OS and a plist on another. A properties file behaves the same
  everywhere.
- **Durability.** Every write rewrites the whole file into a temporary sibling, syncs it to the device
  and atomically renames it over the old one, so a process killed — or a machine losing power —
  mid-write leaves the previous contents. A temporary file a killed write left behind is removed by
  `clear()`, which matches only this store's own temporary names (`<file name><digits>.tmp`, the shape of
  4.x too, or, for a store file name longer than 232 UTF-8 bytes, the name cut to 215 bytes plus `~` and 16
  hex characters of its SHA-256 as the prefix), never a neighbouring store's in-flight file. Every read reads the file afresh. The write is
  [`kmptoolkit-files`](../kmptoolkit-files/01-overview.md)' `writeAtomically` (`Durability.Synced`): on
  Windows a replace blocked by another process that holds the file open is retried for up to about
  210 ms, and when the file system refuses an atomic rename the write fails with `OperationFailed`
  instead of replacing the file non-atomically (earlier versions fell back to a plain replace).
- **Failures.** A corrupted or hand-edited file (a malformed `\uXXXX` escape), a name the file system
  rejects (`:` or `*` on Windows) and a denied security check arrive as `OperationFailed`, never as an
  exception. On Windows, a store whose name makes a reserved device name (`CON`, `NUL`, `COM1`, `aux`...:
  the part of the file name before its first dot) can be read but not written (`OperationFailed`); pick another
  name. A name that is a valid file name but longer than 232 bytes in all is still written: its temporary file
  uses a shortened, hashed prefix.
  The store is meant for a handful of small values; it is not a database.
- **Concurrency.** Instances over the same file in one process share a lock (a `KeyedMutex` keyed by the
  case- and Unicode-folded canonical path). Two *processes* writing
  the same file are not coordinated — keep one writer per file.
- **No secure store.** The JVM has no platform key store this module could keep a key in without
  inventing its own — and a key kept next to the data it encrypts protects nothing. Desktop apps that
  need a secret at rest should use the operating system's credential store directly.

## Threading

Every call is a `suspend` function that runs its platform work on `Dispatchers.IO`, so it is safe to
call from the main thread and from any number of coroutines at once:

- **Android.** `SharedPreferences` reads block on a file's first load, and `commit()` writes to disk;
  both run on the I/O dispatcher. Key resolution for the secure store runs under the process-wide
  lock described above. The resolved key is cached per alias for the whole process, not per store
  instance, so every store over one alias uses the same key handle. A key the platform rejects
  (`InvalidKeyException`) is dropped from that cache and looked up again once — if another instance
  replaced an unrecoverable key meanwhile, the next use picks the new one up instead of failing until
  the process restarts.
  A value that ends in a line break is stored by the plain store with one U+E000 appended, because
  the framework's XML writer indents after a trailing line break; the store removes it on read. Code
  reading the preferences file directly sees it. A value an older version stored that itself ended in `\n` followed
  by U+E000 reads one marker shorter; the two cannot be told apart.
- **iOS.** `NSUserDefaults` and Keychain calls go through `cfprefsd` and `securityd`, both IPC. Two
  writers adding the same Keychain item at once — two coroutines, or an app extension sharing the
  access group — make the loser's `SecItemAdd` fail with `errSecDuplicateItem`; the store then
  updates the item instead, so neither write reports a failure. The store holds no lock and no state, so
  a call waiting on a user-presence sheet blocks no other call, and cancelling it dismisses the sheet.
- **Desktop.** Instances over the same file share a coroutine lock (`KeyedMutex` of `kmptoolkit-files`), so their read-modify-write
  cycles over the file never interleave. The lock is keyed by the path in Unicode NFC with the full
  case mapping (`ß`, `ẞ` and `ss`, the Greek sigma forms, `Settings` and `settings` share it), so two
  spellings of one name share it on a volume that folds case (macOS, Windows); on a case-sensitive volume they are two files behind one lock. A file that
  cannot be parsed, including one that is not valid UTF-8, fails every operation on it, writes included, and is never replaced; `clear()` is
  the way out, and a `DeviceIdProvider` over such a file yields a new id per call until then.

A secure-store call is still a cipher round trip on Android and an IPC hop to `securityd` on iOS:
fine at startup, wrong in a loop or per list item.
