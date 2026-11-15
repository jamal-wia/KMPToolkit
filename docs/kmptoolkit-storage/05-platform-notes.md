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
`Base64(version ‖ iv ‖ ciphertext ‖ tag)` on disk.

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

- **No key rotation.** The envelope carries a version byte, so the format can evolve, but nothing
  rotates the key. Changing the algorithm without a migration means every existing entry reads back
  as `Undecryptable`. Acceptable because that is already a reachable state (see below) and every
  consumer must handle it; not acceptable for data a user cannot re-acquire, which this store is
  not for.
- **No FIPS story, no key-hierarchy features.** If you need those, you need Tink, and you should
  build on it directly rather than through this module.
- **We own the framing.** IV length, tag length and Base64 flags are ours to get right, and are
  pinned by tests in `KeystoreValueCipherTest` for exactly that reason.

Two further choices inside that implementation:

- **`setUserAuthenticationRequired` is off by default.** An auth-bound key would make every read fail
  outside a recent unlock, which breaks a cold start and a background sync. Opt in with
  `AndroidUserAuthentication` (see "User authentication" below, with the cost described there); the
  library never shows the prompt, your own `BiometricPrompt` flow opens the window.
- **Hardware backing is neither required nor checked.** On a device with a TEE or StrongBox the
  platform uses it; on one without, the same API gives a software-isolated key. Refusing to store a
  token on the weaker device would only push you into storing it somewhere worse.

### The Android failure you must handle

**The envelope.** Every secure value is `Base64(version ‖ iv ‖ ciphertext ‖ tag)`: a version byte (currently `1`), the
12-byte IV, and the AES-GCM ciphertext with its 16-byte tag. The version byte and the key name the value is stored
under are authenticated associated data, so a ciphertext copied from one key to another in the preferences file (for
example the access token over the password), or one whose version byte was changed, fails its tag and reads as
`Undecryptable`. The version byte lets the format evolve and makes a downgrade detectable.

**Entries written before the version byte** (4.0.0 and 5.0.0: `Base64(iv ‖ ciphertext ‖ tag)`, the 5.0.0 ones bound to
their key name, the 4.0.0 ones bound to nothing) are read while the store has not been migrated. The first call that
can reach the Keystore key (a `get` of an existing entry, or a `put`) runs a one-time pass that re-encrypts every
readable old entry into the versioned format and then records, in the same preferences file under a reserved name
(`.kmptoolkit.envelope-format`; a key of that exact name is refused with `InvalidValue`), that the store is migrated.
The pass writes each entry with compare-and-set: an entry that was written or removed meanwhile, such as a background
token refresh, is never overwritten with the old value or brought back. A pass that cannot finish (the Keystore is
unavailable, a fault that may pass) changes nothing it did not rewrite and is retried by the next call. **After the
pass an entry without the version byte is `Undecryptable(key)`**: the old fallback that accepted unbound entries for
ever is closed. `clear()` keeps the record; a fresh store is recorded by its first write. An entry too short to hold
its IV and tag (a truncated or hand-edited value) is skipped as malformed: it stays `Undecryptable(key)` and never
keeps the pass from finishing. Length is checked before any decryption, because the AndroidKeyStore answers a too-short
input with an exception other than a bad tag. The pass runs under a per-file migration lock taken with `tryLock` (an
operation that finds a pass running does not wait for it), not under the alias lock: the alias lock only orders key
resolution, the pass is a per-file read-modify-write.

**Downgrading** the library after upgrading to this format loses the secure values: an older version cannot read a
versioned entry and reports it `Undecryptable`.

The Keystore key can be lost: an app restored to a new device from a backup carries the ciphertext without the key,
and the platform can invalidate a key permanently (`KeyPermanentlyInvalidatedException`). Reads of the affected values
return `StorageError.Undecryptable`. The module recovers as far as it can, so a store is never bricked for the life of
the install: a key the platform reports as permanently invalidated (at the lookup, the provider's
`UnrecoverableKeyException("User changed or deleted their auth credentials")` with no cause; when the key is first used, a
`KeyPermanentlyInvalidatedException`) is deleted and replaced by a new one under the alias lock (compare-and-delete: only the
dead key itself, never a newer one another caller made). *New* writes succeed again, old values are
`Undecryptable(key)`, and nothing can recover them.

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
deletes the alias. An alias is deleted only on proof, and an `UnrecoverableKeyException` alone is not proof (a keystore
fault and a locked device look the same on API 24-32). The proof is the platform's own verdict in the form it has at a
lookup: the provider's lookup (`KeyStore.getKey`, `engineGetKey`; API 30, 31, 34 and 36.1, read from the framework
bytecode) turns the daemon's `KeyPermanentlyInvalidatedException` into an `UnrecoverableKeyException` with **no cause** and
exactly the message `User changed or deleted their auth credentials`; that form, or a `KeyPermanentlyInvalidatedException` in the cause chain,
deletes the alias. Every other lookup failure, notably `UnrecoverableKeyException("Failed to obtain information about key")`
with the daemon's `KeyStoreException` as its cause, a locked device and every unknown failure, deletes nothing and answers
`Unavailable`. Such a store stays `Unavailable` until the lookup succeeds (the cipher never gets a key handle to repair
it), and a key that stays unreachable can be recovered only by clearing the app data: the deliberate price of never
destroying values over a fault that passes. API 28 never reports the invalidation at the lookup, only when the key is
first used, where the cipher's own handling replaces it.

The key is looked up with `KeyStore.getKey`, never `KeyStore.getEntry`. On API 31+ (keystore 2) `getEntry` does not reach
`engineGetKey`: the provider does not override `engineGetEntry`, and the libcore default asks `engineIsKeyEntry` and
`engineContainsAlias` first, which swallow every failure of the keystore daemon and answer "no such alias". With
`getEntry` a busy or restarting daemon at the lookup would look like an empty alias, a new key would be generated over
the live one, and every stored value would be lost. `getKey` answers `null` only for "key not found" and reports every
other failure as an exception, so the rules above (delete only on proof, otherwise `Unavailable`) apply to a real device.
(Found by reading the framework bytecode of API 31, 34 and 36.1, not observed on a device.)

An alias that holds a key which is **not a secret key** (for example an asymmetric key another component filed under the
library's alias, `<name>.kmptoolkit.securestorage.key`) is not taken for an empty alias: it is an
`UnrecoverableKeyException` that is no proof, so nothing is generated over it, nothing is deleted, and the store answers
`Unavailable`.

**A loss no code can prevent.** On API 24-30 (keystore 1) the provider's `engineGetKey` itself starts with
`KeyStore.contains()`, which turns a lost connection to the keystore daemon into `false`. If the daemon dies at exactly
that moment the alias looks empty with `getKey` as with `getEntry`, and the store generates a new key over the existing
one: the stored values become `Undecryptable`. The window is the length of one call and needs the daemon to die inside
it; the library cannot close it, and the same holds for the two-process case below. Not observed on a device.

The lookup-time deletion runs only on the first key resolution in a process. A key the platform rejects later is forgotten and looked up again once, but
that lookup never deletes: if it hits an unrecoverable entry — which on API 24-32 a keystore fault
also looks like, since nothing there can name a transient failure — the call fails with
`StorageError.Unavailable` and the next process start recovers a key that was proven dead. A read that
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

The lookup now runs under one lock **per alias** for the whole process, shared by every store
instance over that alias, and asks the Keystore again once it holds the lock — so the second caller
finds the first caller's key. A slow Keystore call for one alias never stalls the stores of another
alias, and a key that is already resolved is answered from a process-wide per-alias cache without
taking any lock. The lock is held only while a key is resolved, dropped or deleted.

**One process per store.** An in-process lock cannot order two *processes*, and the AndroidKeyStore
has no atomic create-if-absent. If your app runs components in a second process
(`android:process`), do not give that process the same secure store, or make sure the main process
has written to it at least once before the other one can start.

### Key options: StrongBox and an unlocked device

`createSecureKeyValueStorage(context, config, options)` takes an `AndroidSecureStorageOptions` (a plain class, not a
data class) with three properties (the third, `userAuthentication`, has its own section below). They apply **when a key is generated**; a key that already exists keeps the
properties it was generated with, and the library has no call that regenerates it (that would make every stored value
`Undecryptable`). To get a key with new properties, open the store under another `StorageConfig.name` and move the
values you want to keep, or clear the app's data.

- `keyBacking`: `DEFAULT` (the platform default, TEE or software; never fails for lack of hardware),
  `PREFER_STRONGBOX` (a StrongBox when the device has one, otherwise the default key; read what you got with
  `storage.androidKeySecurityLevel()`), `REQUIRE_STRONGBOX` (a StrongBox or nothing *when the key is generated*: on a device without one, and on
  every device below API 28, the first use answers `Unsupported(HARDWARE_UNAVAILABLE)`, no key is generated and nothing
  is cached, so a later call tries again. An alias that already holds a key keeps it, StrongBox or not, and answers
  no `Unsupported`: that happens when an app switches from `DEFAULT`, or when another store with the same
  `StorageConfig.name` was opened with other options earlier in the process. Check `androidKeySecurityLevel()`).
- `unlockedDeviceRequired` (`setUnlockedDeviceRequired`, API 28 and newer, ignored below): the key works only while the
  device is unlocked. While it is locked, calls that need the key answer `Unavailable`; the key is kept, is never
  looked up again, and the same call succeeds after the user unlocks. Do not enable it for a store that a background sync
  must read while the device is locked. The platform reports a locked device in a different form per level, and the
  library recognises each one: API 28-30 as `UserNotAuthenticatedException`; API 31 and later (keystore 2, KeyMint
  `DEVICE_LOCKED`, -72) as a plain `InvalidKeyException("Keystore operation failed")` around an
  `android.security.KeyStoreException`, with no `UserNotAuthenticatedException` in the chain. From API 33 the library asks
  the platform (`KeyStoreException.requiresUserAuthentication()`). On **API 31 and 32** nothing public reads the code, and
  the library does not read the hidden method (a non-SDK interface a consumer's StrictMode or store review would flag),
  so it recognises the exact shape (an `InvalidKeyException` that is not one of the platform's named subclasses, that
  message, a `KeyStoreException` as its cause) and only for a store opened with `unlockedDeviceRequired`. The price there:
  another refusal of the same shape for such a store (a rate limit, a system error) also answers `Unavailable` and keeps
  the key, which is a "retry later" answer, never a lost-value one. A store without `unlockedDeviceRequired` takes
  nothing for a locked device on 31 and 32. The API 31+ form comes from the platform source and the framework bytecode
  and is not observed on a device.

### User authentication

`AndroidSecureStorageOptions(userAuthentication = AndroidUserAuthentication(validitySeconds = 30))` binds the key to the
user: after the user has authenticated, the key can be used for `validitySeconds`; outside that window every call that needs
the key answers `AccessDenied` (retry only after the user has authenticated, never in a loop, never delete anything in
reaction to it). It is a plain-class option like the other two and applies **when the key is generated**: an existing key
keeps the properties it has.

- **The library never shows a prompt.** It has no UI and no `Activity`. The app authenticates the user with its own
  `BiometricPrompt` (or the confirm-credential screen); the platform then opens the window for the key. `ensureKey()` is the
  probe: `Success` inside the window, `AccessDenied` outside it.
- **Which calls need it.** Those that use the key: `put`, `get` of an existing entry, `ensureKey()`. Reading an absent key,
  `remove`, `clear()` and `androidKeySecurityLevel()` use no key operation and answer without authentication. A migration
  pass (see above) that meets a closed window stops without recording the migration and finishes in a later call.
- **`allowDeviceCredential`** (default `true`): a strong biometric or the device credential (PIN, pattern, password)
  authenticates. `false` accepts a strong biometric only, which needs API 30 (`setUserAuthenticationParameters`); below it
  the platform cannot tell the two apart for a time-bound key, so the store refuses to generate the key and answers
  `Unsupported(HARDWARE_UNAVAILABLE)` rather than silently accepting the credential too. Below API 30 the window is
  `setUserAuthenticationValidityDurationSeconds`.
- **`validitySeconds`** is at least 1 (`IllegalArgumentException` otherwise). Per-use authentication (a `BiometricPrompt`
  crypto object for every operation) is not offered: it cannot work without a UI of the library's.
- **A secure lock screen is required** to generate a key bound to authentication. Without one the call answers
  `Unavailable` and nothing is generated or cached; the same call succeeds once the user has set one.
- **Invalidation.** The platform invalidates such a key for good when the lock screen is removed
  (`KeyPermanentlyInvalidatedException`). A change of the enrolled biometrics invalidates it only for a biometric-only key
  (`allowDeviceCredential = false`, with the platform default `setInvalidatedByBiometricEnrollment(true)`): the default
  key, which also accepts the device credential, is bound to the device credential and survives an enrolment change
  (by reading the framework's `KeyStore2ParameterUtils.addSids`, API 34). A biometric-only key also needs an enrolled
  biometric to be generated: without one the platform refuses (`IllegalStateException`) and the store answers
  `Unavailable` until a biometric is enrolled, as it does without a lock screen. The store handles it as it handles any permanently invalidated
  key (see above): the dead key is deleted, a new one is generated with the same options, writes succeed again and the old
  values are `Undecryptable(key)`. Generating the new key needs a lock screen (`Unavailable` until there is one).
- **With `unlockedDeviceRequired` as well**, the library cannot tell a locked device from an unauthenticated user (on every
  level both are recognised as one state, see "Key options"), so such a store answers `AccessDenied` for either. Without `userAuthentication` that refusal is a locked device and
  answers `Unavailable`.

Not observed on a device: the validity window, the invalidation by a lock-screen removal or an enrolment change, the
missing-lock-screen and missing-biometric failures, the exact exception `KeyStore.getKey` throws for an invalidated key (also on API 31+, where it may instead answer `null` if the keystore already deleted the key when the lock screen was removed) and the prompt flow need a real Keystore and a lock screen. The host tests cover the key parameters per API level, the
mapping, the lifecycle (cancellation, a dismissed prompt, a seeded storm over a window that opens and closes) and the
replacement of an invalidated key through a fake Keystore whose key handle refuses as the platform does.

`androidKeySecurityLevel()` resolves the key if needed and returns `SOFTWARE`, `TRUSTED_ENVIRONMENT`, `STRONGBOX` or
`UNKNOWN`. From API 31 the platform names the level; below it only "inside secure hardware" is known, which cannot tell a
trusted environment from a StrongBox, so a hardware key there is `UNKNOWN` and a software key `SOFTWARE`. A store this
factory did not create (a test double, a decorator) answers `Success(UNKNOWN)`.

`ensureKey()` resolves the key through the same path a `get` takes, creating it on a first run, runs one cipher
initialisation, and writes no entry. It answers what the other calls answer, with no effect on stored values:

| Situation | Result |
|---|---|
| `REQUIRE_STRONGBOX` on a device without a StrongBox | `Unsupported(HARDWARE_UNAVAILABLE)` |
| `PREFER_STRONGBOX` without a StrongBox | key generated with the default backing; `androidKeySecurityLevel()` reports it |
| `unlockedDeviceRequired` while the device is locked (every level, see "Key options") | `Unavailable` |
| `userAuthentication` outside the validity window (`UserNotAuthenticatedException`) | `AccessDenied(cause)`; the key is kept |
| `userAuthentication` on a device without a secure lock screen, or while the dead key cannot be replaced | `Unavailable` |
| `userAuthentication` with `allowDeviceCredential = false` below API 30 | `Unsupported(HARDWARE_UNAVAILABLE)` |
| the platform reports the key permanently invalidated | the dead key is deleted and a new one generated under the alias lock; old entries `Undecryptable(key)`; writes and `ensureKey()` succeed |
| a tag that fails on an entry | `Undecryptable(key)` |
| the Keystore will not open, or a fault the platform calls transient (API 33+) | `Unavailable` (a transient fault on a write is `OperationFailed`, as before) |
| any other failure of `ensureKey()` | `OperationFailed(ENSURE_KEY)` with no key |

Not observed on a device: a real StrongBox, the `Unsupported` answer on an emulator without one, and a locked
emulator (the exception chain of a locked device on API 31 and later is read from the platform source and bytecode). The StrongBox fallback and every mapping above are covered on the host through seams.

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
  `clear()` fails and deletes nothing; when the probe was added but the answer named no (or a blank) group, or the
  delete of the probe by group failed, the probe is deleted by its own unique account, with no group, before the
  failure is returned (the cleanup's status is ignored, the original failure is kept), so a failed `clear()`
  leaves no probe behind, unless the cleanup delete fails as well; that one probe is swept by the next successful
  `clear()`. Items
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
