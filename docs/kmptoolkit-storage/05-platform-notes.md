# kmptoolkit-storage — Platform notes

What is actually on disk, why the crypto looks the way it does, and the two platform traps that
shaped this module's implementation.

## Permissions and app configuration

**None.** No Android permission is declared or required, nothing goes into `Info.plist`, and no
entitlement is needed for the default configuration.

The one exception is opt-in and yours: passing `accessGroup` to the iOS
`createSecureKeyValueStorage` requires a matching **Keychain Sharing** entitlement in your app. The
library cannot check that for you — a group you do not have makes every operation fail with
`StorageError.OperationFailed`.

Following the repository rule in `docs/01-architecture.md`, this module declares nothing in its own
`AndroidManifest.xml`. There is, in fact, no manifest to declare it in.

## Where your data actually lives

Every identifier is derived from `StorageConfig.name`, which defaults to your application id. Given
a name `N`:

| | Android | iOS |
|---|---|---|
| Plain store | `SharedPreferences` file `N.kmptoolkit.storage` | `NSUserDefaults` suite `N.kmptoolkit.storage` |
| Secure store | `SharedPreferences` file `N.kmptoolkit.securestorage` | Keychain `kSecAttrService` = `N.kmptoolkit.securestorage` |
| Encryption key | AndroidKeyStore alias `N.kmptoolkit.securestorage.key` | held by `securityd`; no alias of ours |

These strings are an implementation detail — they are documented so you can find your own data with
`adb shell` or a Keychain dump, not so you can depend on them. They are also the reason
`StorageConfig` exists at all: nothing here is a constant baked into the library, so two apps, or
two KMPToolkit-based libraries in one process, cannot collide.

The suffix on the plain store is load-bearing on iOS. `NSUserDefaults(suiteName:)` returns the
**standard** defaults when the suite name equals the app's bundle identifier, and this module's
`clear()` is `removePersistentDomainForName` — on the standard domain that would wipe everything the
app and every embedded SDK ever wrote. Because the suffix is always appended, the resolved suite name
can never equal the bundle id, even if you pass it explicitly as `name`.

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
  framing, in about a hundred lines — small enough that a consumer auditing what happens to their
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

The Keystore entry is destroyed when the user **adds, changes, or removes the device lock**, and an
app restored to a new device from a backup carries the ciphertext without the key. In both cases the
value is gone while the entry remains, and reads return `StorageError.Undecryptable`. The module
recovers as far as it can — an alias whose key has become unrecoverable is dropped and a new key
generated, so *new* writes work again instead of the store being bricked for the life of the install
— but the old values are not recoverable by anyone.

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
  rather than replacing it, so an unconditional add would make `put` non-overwriting. The update
  path carries no `kSecAttrAccessible`, which is why an item keeps the accessibility it was created
  with.
- **`clear` deletes by service.** One `SecItemDelete` matching the store's `kSecAttrService` and no
  account — items written by another store, another framework, or the same app under a different
  service are not matched.
- **Statuses that are translated:** `errSecItemNotFound` → absent; `errSecNotAvailable` (`-25291`)
  and `errSecInteractionNotAllowed` (`-25308`) → `Unavailable`; `errSecDecode` (`-26275`) →
  `Undecryptable`. Anything else keeps its raw code in `OperationFailed.platformCode` rather than
  being flattened into a category it does not belong to.
- **The plain store's writes cannot fail.** `NSUserDefaults` reports no status, so those operations
  always return `Success`. That is not a shortcut — there is genuinely no error to surface, and the
  result type exists for the Keychain store's sake.

## Threading

Every call is synchronous. The plain stores are fast enough to call from anywhere. A secure-store
call is a cipher round trip on Android and an IPC hop to `securityd` on iOS — fine on the main
thread once at startup, wrong in a loop or per list item.
