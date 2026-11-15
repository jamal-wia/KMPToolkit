# kmptoolkit-storage — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.storage`. Where a symbol exists on one platform only, its heading says
so. The signatures are listings written from the published API; the checked record is the ABI dump under `kmptoolkit-storage/api/`.

---

## `KeyValueStorage`

<!-- listing -->
```kotlin
public interface KeyValueStorage {
    public suspend fun get(key: String): StorageResult<String?>
    public suspend fun put(key: String, value: String): StorageResult<Unit>
    public suspend fun remove(key: String): StorageResult<Unit>
    public suspend fun clear(): StorageResult<Unit>
}
```

A string-keyed, string-valued store whose contents survive a process restart: `SharedPreferences` on Android, an `NSUserDefaults`
suite on iOS, a properties file on desktop. The interface and every function that returns a `StorageResult` carry
`@MustUseReturnValues`; a consumer who enables Kotlin's return-value checker gets a warning for an ignored result, one who does
not is unaffected.

| Member | Contract |
|---|---|
| `get` | `Success(null)` when the key was never written. `Failure` only when the store could not be read, never for a missing key |
| `put` | Overwrites. The value is readable by any instance over the same config once this returns. A key or value containing an unpaired UTF-16 surrogate cannot be stored by any backend; `put` answers `InvalidValue(key)` and changes nothing |
| `remove` | Idempotent; succeeds for a key that is not there |
| `clear` | Removes only this store's entries. Never another store's, never the iOS standard defaults |

Every member is a main-safe `suspend` function: the store runs its blocking platform call on its own
I/O dispatcher. Failures arrive as `StorageResult.Failure`; the only exceptions are `CancellationException` for a cancelled caller and a JVM `Error`.
Safe to call from any number of coroutines at once (nothing corrupts) but not ordered under concurrent access:
see [`03-guide.md`](03-guide.md).

---

## `EncryptedKeyValueStorage`

<!-- listing -->
```kotlin
public interface EncryptedKeyValueStorage : KeyValueStorage {
    public val protection: SecureStorageProtection
    public suspend fun ensureKey(): StorageResult<Unit>
}
```

The parent of every encrypted store: values are encrypted at rest under a key that is never stored in plaintext beside them, and
every operation on existing data first resolves and verifies that key. It is the type for shared code that accepts "encrypted,
either kind": it excludes plain stores. It adds a guarantee (`protection`) and one operation (`ensureKey()`); everything else is
the plain contract.

| Member | Contract |
|---|---|
| `protection` | The lower bound of who holds the key and who is kept out. Known without I/O; constant for the instance, except an app-key store after a `changeKey` to the other key type (see `AppKeyEncryptedKeyValueStorage`). See `SecureStorageProtection` |
| `ensureKey()` | Resolves (on a store with no key yet, creates) and verifies the key now, so the app chooses when the one prompt or passphrase check happens. Never falls back to a weaker key. `Success` when the key is available and verified; otherwise the typed failure the other operations would answer, with no effect on stored values. Skipping it is always safe |

### `ensureKey()` per platform

| Store | What it does | Prompts | Typical non-success answers |
|---|---|---|---|
| Android | Resolves the Keystore key by the path a `get` takes (creating it on a first run), runs one cipher initialisation, writes no entry | never (the library has no UI) | `Unavailable` (keystore not loadable, device locked with `unlockedDeviceRequired`), `AccessDenied` (outside the `userAuthentication` window), `Unsupported(HARDWARE_UNAVAILABLE)`, `OperationFailed(ENSURE_KEY)` |
| iOS | An attribute-only Keychain query for the store's service; changes nothing. No items is `Success` (there is no key to create) | never | `Unavailable`, `Unsupported(MISSING_ENTITLEMENT)`, `OperationFailed(ENSURE_KEY)`. On a `userPresence` store that holds items the answer is not observed on a device |
| Desktop OS-key | Creates the data key on a first run (the file first, then the OS item, rolled back on failure) and verifies it; a lost key with entries is `Undecryptable(null)` | the macOS keychain dialog and a Linux collection unlock, unless `allowUserInteraction = false` | `Unsupported(...)`, `Unavailable`, `AccessDenied`, `Undecryptable(null)` |
| Desktop app-key | Calls your key provider, derives the key and verifies it; creates the file on a first run | your provider's own | `AccessDenied` (wrong key), `Unavailable` (the provider threw), `Undecryptable(null)` |
| `InMemoryKeyValueStorage` | `Success`, or the armed failure | none | whatever the test armed |

A failure of `ensureKey()` that is none of the typed cases is `OperationFailed(ENSURE_KEY, key = null)` on every platform.

---

## `SecureKeyValueStorage`

<!-- listing -->
```kotlin
public interface SecureKeyValueStorage : EncryptedKeyValueStorage
```

An `EncryptedKeyValueStorage` whose key (on iOS, whose values) an OS key store holds: the Android Keystore, the iOS Keychain, or the
macOS keychain, Windows DPAPI or Linux Secret Service. It adds nothing to its parent: the difference is a guarantee, not a shape.
`protection` is one of the `OS_KEY_STORE_*` values (a test double may report `NONE`).

What the guarantee covers: values are encrypted, key names are not; the encryption key is held by the platform, not in the app's files. What it does
not cover: a value can become **permanently unreadable** when the platform destroys the key (`StorageError.Undecryptable`). See
[`05-platform-notes.md`](05-platform-notes.md).

A plain `KeyValueStorage` can no longer stand in for a `SecureKeyValueStorage`. A custom implementation (a decorator, a hand-written
fake) provides `protection` and `ensureKey()`: Kotlin's `by` delegation provides both.

---

## `SecureStorageProtection`

<!-- listing -->
```kotlin
public enum class SecureStorageProtection {
    OS_KEY_STORE_APP_ISOLATED,   // Android Keystore, iOS Keychain
    OS_KEY_STORE_USER_SCOPED,    // desktop OS key store
    PASSPHRASE,
    APP_PROVIDED_KEY,
    NONE,                        // test doubles only
}
```

Who holds the key and who is kept out, as a lower bound that is true on every platform that reports it. The table of what each value keeps out is
in [`01-overview.md`](01-overview.md#protection-a-lower-bound). May grow in a minor version: give a `when` an `else` that treats an unknown value
as the weakest protection.

---

## `StorageResult<T>`

<!-- listing -->
```kotlin
public sealed interface StorageResult<out T> {
    public data class Success<out T>(public val value: T) : StorageResult<T>
    public data class Failure(public val error: StorageError) : StorageResult<Nothing>
}

public fun <T> StorageResult<T>.getOrNull(): T?
public fun <T> StorageResult<T>.errorOrNull(): StorageError?
public val StorageResult<*>.isSuccess: Boolean
public suspend fun KeyValueStorage.getStringOrNull(key: String): String?
```

`kotlin.Result` is not used because it can only carry a `Throwable`, and none of `StorageError`'s
cases are exceptional in the language's sense.

`Success.toString()` never contains the value: it prints `Success(value=<redacted>)`, and nothing computed from the value (its type or
length) either, so a result read from an encrypted store is safe to log, to put in a collection that is logged, or to print in a failed assertion.
`equals`, `hashCode`, `component1` and `copy` still use the value, so two successes that print alike can differ: compare `.value` to see the
values in a test's failure message.

`getStringOrNull` collapses "absent" and "unreadable" into `null`: convenient for a flag on a plain store, **deprecated on an
`EncryptedKeyValueStorage`** (a second overload with a warning) for exactly that reason.

---

## `StorageError`

<!-- listing -->
```kotlin
public sealed interface StorageError {
    public data class Unavailable(public val cause: Throwable? = null) : StorageError

    public data class AccessDenied(
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : StorageError

    public data class Unsupported(
        public val reason: UnsupportedReason,
        public val cause: Throwable? = null,
    ) : StorageError

    public data class Undecryptable(
        public val key: String?,
        public val cause: Throwable? = null,
    ) : StorageError

    public data class OperationFailed(
        public val operation: StorageOperation,
        public val key: String? = null,
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : StorageError

    public data class InvalidValue(
        public val key: String,
        public val cause: Throwable? = null,
    ) : StorageError
}

public enum class StorageOperation { GET, PUT, REMOVE, CLEAR, ENSURE_KEY, CHANGE_KEY }
```

| Case | Means | Response |
|---|---|---|
| `Unavailable` | the store cannot be used right now and retrying later can succeed: a key store that will not load, a keychain that cannot show UI, a device that is locked, a lock wait or an OS call that ran into its time limit; on Android 13+ (API 33) also a decrypt that failed with a keystore fault the platform calls transient | retry later; nothing is lost; never delete |
| `AccessDenied` | the store refused this call now: a prompt the user denied or dismissed, an access-control rule, or a key your app supplied that does not open the store. `platformCode` is the raw status where the platform reports one (an iOS `-128`) | retry only on a user action; never in a loop; never delete |
| `Unsupported` | this store cannot work in this environment, for `reason`; retrying will not help | tell the user or choose another store explicitly; never fall back silently to something weaker |
| `Undecryptable` | data cannot be decrypted with the key available now: the entry `key`, or the whole store when `key` is `null` (its key is gone or has been replaced). Only an `EncryptedKeyValueStorage` produces it | remove the affected key (or every key) and re-acquire; on an OS-key store `clear()` always works, even when `key` is `null` (it empties the file or moves an unparsable one aside), then write again; `remove` works when only the key is lost; see the table in 05-platform-notes |
| `OperationFailed` | the store was usable but `operation` itself failed on I/O or an unexpected platform status. `key` is `null` for `CLEAR`, `ENSURE_KEY` and `CHANGE_KEY`, which target the whole store; `platformCode` carries an iOS `OSStatus`, `cause` an exception where the platform threw one | log it; the same call may succeed on retry |
| `InvalidValue` | the value under `key` can never be accepted or parsed: a typed read of a stored value that is not of that type, or a `put` of a key or value with an unpaired UTF-16 surrogate, or a value the store can never hold (a desktop value that cannot fit the 256 MiB file, a name over 65,535 UTF-8 bytes, the reserved Android record name). `cause` names the rule and **never contains the value** | never retry the same value; drop it or fix the caller |

Match `StorageError` exhaustively with no `else`: a case is added only in a major version, listed under `Breaking`. `StorageOperation` and
`UnsupportedReason` may grow in a minor version, so a `when` over either takes an `else` that treats an unknown value as a plain failure to log, never
as a reason to retry or to delete.

`StorageOperation.ENSURE_KEY` is also what `probeDesktopKeyStore()` and `androidKeySecurityLevel()` report (`key = null`): they ask the same
question, whether the key store is usable. `CHANGE_KEY` is `AppKeyEncryptedKeyValueStorage.changeKey`.

### `UnsupportedReason`

<!-- listing -->
```kotlin
public enum class UnsupportedReason {
    NO_USER_SESSION,
    NO_KEY_STORE,
    UNSUPPORTED_RUNTIME,
    NATIVE_ACCESS_UNAVAILABLE,
    MISSING_ENTITLEMENT,
    UNSUPPORTED_OS,
    HARDWARE_UNAVAILABLE,
}
```

| Reason | The single fix |
|---|---|
| `NO_USER_SESSION` | log in or start a session: no D-Bus session bus, or a Windows logon without user credentials (a service, a key-based ssh session) |
| `NO_KEY_STORE` | a session exists but no key store serves it: install or start a Secret Service provider, `libsecret`, or a default keychain |
| `UNSUPPORTED_RUNTIME` | JDK 21 without `kmptoolkit-storage-jna`: run on JDK 22 or newer, or add that artifact |
| `NATIVE_ACCESS_UNAVAILABLE` | native code cannot load or is not permitted (`--illegal-native-access=deny`, a `noexec` temporary directory for JNA) |
| `MISSING_ENTITLEMENT` | the app lacks an entitlement or signature the store needs (Apple `-34018`, an access group not granted) |
| `UNSUPPORTED_OS` | a desktop OS other than macOS, Windows or Linux |
| `HARDWARE_UNAVAILABLE` | required secure hardware is absent (`AndroidKeyBacking.REQUIRE_STRONGBOX` without a StrongBox; `allowDeviceCredential = false` below API 30) |

---

## `StorageConfig`

<!-- listing -->
```kotlin
public data class StorageConfig(public val name: String? = null)
```

Which store a factory opens. `null` resolves at runtime to `Context.getPackageName()` on Android and `CFBundleIdentifier` on iOS; every platform identifier the module
needs is derived from it. **On desktop `name` is required** (there is no application id to derive it from); a desktop factory throws
`IllegalArgumentException` for a config without one.

Throws `IllegalArgumentException` for a blank name, or one containing `/`, `\`, a space, or a null character: those are developer
errors, not runtime conditions.

---

## `DeviceIdProvider`

<!-- listing -->
```kotlin
public class DeviceIdProvider(
    storage: KeyValueStorage,
    key: String = DEFAULT_KEY,
) {
    public suspend fun current(): String

    public companion object {
        public const val DEFAULT_KEY: String = "kmptoolkit.storage.device_id"
    }
}
```

A random v4 UUID generated on first call and persisted in `storage`. Stable across calls, instances,
and process restarts; reset by `clear()`, by clearing app data, and by a reinstall.

`current()` always returns an id and never fails. If the store cannot be written, the id is valid
for that call but a later call produces a different one: a degraded signal rather than an error to
handle. If the entry cannot be read right now (`StorageError.Unavailable`, for example a keystore that
is not answering), the id is fresh for that call and the stored one is left untouched; only an absent,
blank, `Undecryptable` or otherwise permanently unreadable entry is overwritten. Nothing is memoized
in memory, so a `clear()` takes effect on the very next call.

Concurrent first calls agree: the read-generate-write runs under one lock shared by every provider in
the process, so callers racing on a fresh store (through one provider or several over the same
store) all get the id that was persisted.

Not a fingerprint and not a credential: see [`01-overview.md`](01-overview.md).

---

## Typed accessors

<!-- listing -->
```kotlin
public suspend fun KeyValueStorage.getInt(key: String): StorageResult<Int?>
public suspend fun KeyValueStorage.getLong(key: String): StorageResult<Long?>
public suspend fun KeyValueStorage.getBoolean(key: String): StorageResult<Boolean?>

public suspend fun KeyValueStorage.putInt(key: String, value: Int): StorageResult<Unit>
public suspend fun KeyValueStorage.putLong(key: String, value: Long): StorageResult<Unit>
public suspend fun KeyValueStorage.putBoolean(key: String, value: Boolean): StorageResult<Unit>

public suspend fun KeyValueStorage.getStringOr(key: String, default: String): String
public suspend fun KeyValueStorage.getIntOr(key: String, default: Int): Int
public suspend fun KeyValueStorage.getLongOr(key: String, default: Long): Long
public suspend fun KeyValueStorage.getBooleanOr(key: String, default: Boolean): Boolean
```

The four `...Or` functions have a second overload on an `EncryptedKeyValueStorage` receiver, **deprecated with a warning**: a default turns an
unreadable encrypted store into "absent". Use `get`/`getInt`/... and handle the failure. (`kmptoolkit-storage-testing`'s
`InMemoryKeyValueStorage` is an encrypted-store type, so a test that uses it as a plain store and calls these helpers on a variable of the fixture's type
gets the warning: declare the variable as `KeyValueStorage`, or read with `get(...).getOrNull()`.)

Encoding: `Int` and `Long` as decimal strings (an optional `-` and ASCII digits, nothing else; `"+42"` or `" 7"` reads as a failure) and
`Boolean` as exactly `"true"` or `"false"`.

| Stored value | `getInt` / `getLong` / `getBoolean` | `...Or(key, default)` |
|---|---|---|
| absent | `Success(null)` | `default` |
| parses as the type | `Success(value)` | the value |
| does not parse | `Failure(InvalidValue(key, cause))`, the cause names the requested type and never the value | `default` |
| store unreadable | the store's `Failure`, unchanged | `default` |

---

## Android

### Factories

<!-- listing -->
```kotlin
public fun createKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
): KeyValueStorage

public fun createSecureKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
    options: AndroidSecureStorageOptions = AndroidSecureStorageOptions(),
): SecureKeyValueStorage

public suspend fun SecureKeyValueStorage.androidKeySecurityLevel(): StorageResult<AndroidKeySecurityLevel>
```

Only the application context is retained, so passing an Activity cannot leak it. Both are cheap,
infallible, and safe to call repeatedly: two stores over the same config see the same data. There is
nothing to release. The `options` parameter is new in 6.0.0 (source-compatible; precompiled callers must be recompiled).

The secure factory does not touch the Keystore: the key is resolved on the first `get` or `put` (or `ensureKey()`), so
a key store that is unhappy produces `StorageError.Unavailable` rather than an exception during startup.
`protection` is `OS_KEY_STORE_APP_ISOLATED`.

`androidKeySecurityLevel()` resolves (on a first run, creates) the key and reports where the platform put it; it answers `Success(UNKNOWN)` for a store this
factory did not create (a test double, a decorator), and the typed failure `ensureKey()` would give when the key cannot be resolved.

### `AndroidSecureStorageOptions`

<!-- listing -->
```kotlin
public class AndroidSecureStorageOptions(
    public val keyBacking: AndroidKeyBacking = AndroidKeyBacking.DEFAULT,
    public val unlockedDeviceRequired: Boolean = false,
    public val userAuthentication: AndroidUserAuthentication? = null,
)

public class AndroidUserAuthentication(
    public val validitySeconds: Int,
    public val allowDeviceCredential: Boolean = true,
)

public enum class AndroidKeyBacking { DEFAULT, PREFER_STRONGBOX, REQUIRE_STRONGBOX }

public enum class AndroidKeySecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, UNKNOWN }
```

`AndroidKeyBacking` and `AndroidKeySecurityLevel` may grow in a minor version: give a `when` over either an `else`.

The options apply **when a key is generated**, which is the first use of a store on a device. An existing key keeps the properties it was generated with; the
library has no call that regenerates it, because that would make every stored value `Undecryptable`. To get a key with new properties, open the store under
another `StorageConfig.name` and move the values you want to keep, or clear the app's data. The classes are plain classes, not data classes, so an option can be added in a
minor version.

| Option | Meaning |
|---|---|
| `keyBacking` | `DEFAULT`: the platform default, never fails for lack of hardware. `PREFER_STRONGBOX`: a StrongBox when the device has one, else the default (read the outcome with `androidKeySecurityLevel()`). `REQUIRE_STRONGBOX`: a StrongBox or nothing, *when the key is generated*: without one (and below API 28) the first use answers `Unsupported(HARDWARE_UNAVAILABLE)`; an alias that already holds a key keeps it |
| `unlockedDeviceRequired` | the key can be used only while the device is unlocked (API 28 and newer; ignored below). While it is locked every call that needs the key answers `Unavailable` (on API 31 and 32 only for a store opened with the option; see 05): retry after the user unlocks it. Do not enable it for a store a background sync must read |
| `userAuthentication` | time-bound user authentication: after the user authenticated (a biometric, or the device credential unless `allowDeviceCredential = false`) the key can be used for `validitySeconds` (at least 1); outside the window a call that needs the key answers `AccessDenied`. **The library never shows a prompt**: your app uses `BiometricPrompt`. Needs a secure lock screen (otherwise `Unavailable`). `allowDeviceCredential = false` needs API 30 (otherwise `Unsupported(HARDWARE_UNAVAILABLE)`) |

Reading an absent key, `remove` and `clear` use no key and answer without authentication. A `userAuthentication` and `unlockedDeviceRequired` store answers `AccessDenied` for
either, because the platform reports both the same way. How a locked device reaches the store on each API level, including the API 31-32 limits, is in
[`05-platform-notes.md`](05-platform-notes.md#key-options-strongbox-and-an-unlocked-device).

---

## iOS

### Factories

<!-- listing -->
```kotlin
public fun createKeyValueStorage(config: StorageConfig = StorageConfig()): KeyValueStorage

public fun createSecureKeyValueStorage(
    config: StorageConfig = StorageConfig(),
    options: IosSecureStorageOptions = IosSecureStorageOptions(),
): SecureKeyValueStorage
```

`createSecureKeyValueStorage(config, accessibility, accessGroup)` of 5.x is replaced by the `options` form
([`07-migrating-to-6.md`](07-migrating-to-6.md)). The factories are per-platform rather than `expect`/`actual` because Android needs a
`Context` and iOS needs nothing; see `docs/01-architecture.md`. `protection` is `OS_KEY_STORE_APP_ISOLATED`.

### `IosSecureStorageOptions`

<!-- listing -->
```kotlin
public class IosSecureStorageOptions(
    public val accessibility: KeychainAccessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
    public val accessGroup: String? = null,
    public val synchronizable: Boolean = false,
    public val userPresence: IosUserPresence? = null,
)

public class IosUserPresence(public val policy: IosUserPresencePolicy, public val prompt: String)

public enum class IosUserPresencePolicy { USER_PRESENCE, BIOMETRY_ANY, BIOMETRY_CURRENT_SET }
```

| Option | Meaning |
|---|---|
| `accessibility` | `kSecAttrAccessible`: when iOS may decrypt the items. Applied on **every** write, an update included, so an item takes a changed value on its next `put`; a key never written again keeps its class; `clear()` moves everything |
| `accessGroup` | `kSecAttrAccessGroup`: the group the items are filed under, for sharing with an extension or sibling app. Needs the Keychain Sharing entitlement; a group your entitlements do not grant makes every operation answer `Unsupported(MISSING_ENTITLEMENT)`. `null` uses the app's default group, and `clear()` then touches only that group. **Known limit:** with `null`, `get`, `put` and `remove` name no group, so the Keychain matches every group the app can reach and a same-named store in a shared group can be read, deleted or overwritten. When several stores share a keychain group, give each an explicit `accessGroup` or a distinct store name |
| `synchronizable` | `kSecAttrSynchronizable`: puts the items in iCloud Keychain, so they leave the device. Needs a portable `accessibility` (`WHEN_UNLOCKED` or `AFTER_FIRST_UNLOCK`) and cannot be combined with `userPresence` |
| `userPresence` | a `SecAccessControl` gate on reading: the system sheet shows on **every** `get` (one Keychain item per key; a `put` that replaces an item may show it too). Cancelling answers `AccessDenied(-128)`; a failed authentication `AccessDenied(-25293)`. Neither deletes anything. `BIOMETRY_CURRENT_SET` makes the items unreadable (`Undecryptable`) when the enrolled biometry changes: `remove` or `clear()`, then write again. `prompt` is app-supplied and must not be blank |

`clear()` removes the store's items, local and synchronizable, in exactly one access group: `accessGroup` when set, otherwise the app's default group (it fails
and deletes nothing when that group cannot be resolved).

### `KeychainAccessibility`

<!-- listing -->
```kotlin
public enum class KeychainAccessibility {
    WHEN_UNLOCKED,
    WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    AFTER_FIRST_UNLOCK,
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,   // default
    WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
}
```

The `kSecAttrAccessible` class an item is written with. `ThisDeviceOnly` variants are excluded from encrypted backups and device-to-device transfer. The default
allows a background refresh to read the value after the first unlock following a reboot, without letting it leave the device it was issued for.
`WHEN_PASSCODE_SET_THIS_DEVICE_ONLY` items are deleted by the system when the passcode is removed (reads then answer `Success(null)`).

---

## Desktop (`jvm`)

<!-- listing -->
```kotlin
public fun createKeyValueStorage(directory: File, config: StorageConfig): KeyValueStorage

public fun createSecureKeyValueStorage(
    directory: File,
    config: StorageConfig,
    options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(),
): SecureKeyValueStorage

public fun createAppKeyEncryptedKeyValueStorage(
    directory: File,
    config: StorageConfig,
    key: AppEncryptionKey,
): AppKeyEncryptedKeyValueStorage

public suspend fun probeDesktopKeyStore(
    options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(),
): StorageResult<DesktopKeyStore>
```

All three factories require `config.name` (`IllegalArgumentException` otherwise); `directory` is created on the first write, owner-only.

| Factory | File in `directory` | `protection` | Native binding |
|---|---|---|---|
| `createKeyValueStorage` | `<name>.kmptoolkit.storage.properties` (plain text; owner-only) | none, not an encrypted store | none |
| `createSecureKeyValueStorage` | `<name>.kmptoolkit.securestorage.bin` | `OS_KEY_STORE_USER_SCOPED` | yes: JDK 22+ built in, JDK 21 with `kmptoolkit-storage-jna`; otherwise every call answers `Unsupported` |
| `createAppKeyEncryptedKeyValueStorage` | `<name>.kmptoolkit.appkeystorage.bin` | `PASSPHRASE` or `APP_PROVIDED_KEY` | none |

Every store file has a sibling `<file>.lock` that serializes writes across processes (a write that cannot get it within 10 s answers `Unavailable`). Files are written
as a whole, atomically, owner-only. Nothing touches the operating system until the first call, and no store ever falls back to a weaker one. The layout, the key
lifecycle and every limit are in [`05-platform-notes.md`](05-platform-notes.md#desktop).

### `AppKeyEncryptedKeyValueStorage`

<!-- listing -->
```kotlin
public interface AppKeyEncryptedKeyValueStorage : EncryptedKeyValueStorage {
    public suspend fun changeKey(newKey: AppEncryptionKey): StorageResult<Unit>
}
```

An encrypted desktop store whose data key is wrapped by a key your app provides. It is **not** a `SecureKeyValueStorage`: no OS key store is involved, so it never claims OS custody. A wrong key
answers `AccessDenied` on every operation, `get` of an absent key and `clear()` included, with nothing read or changed; it is never `Undecryptable` and never reads as "absent". A forgotten
passphrase has no reset in the API: delete the store's files and start over. `changeKey` is annotated with its own `@MustUseReturnValues`.

`changeKey(newKey)` verifies the current key first (`AccessDenied` otherwise), then re-encrypts every value under a fresh data key wrapped by `newKey` (a new salt and key-check) and replaces
the file atomically: a crash leaves the old file or the new one, both complete. A `newKey` that is unusable (an empty or unpaired-surrogate passphrase, a raw key that is not 32 bytes) is `AccessDenied` and changes nothing;
a value that cannot be decrypted with the current key is `Undecryptable(key)` and changes nothing. Copies of the file taken before the change stay readable with the old key. Another instance over the
same file that still holds the old key answers `AccessDenied` on its next operation; this instance is bound to `newKey`. Failures carry `StorageOperation.CHANGE_KEY` and `key = null`.

### `AppEncryptionKey`

<!-- listing -->
```kotlin
public sealed interface AppEncryptionKey {
    public class Passphrase(
        public val provider: suspend () -> CharArray,
        public val iterations: Int = 600_000,
    ) : AppEncryptionKey

    public class Raw(public val provider: suspend () -> ByteArray) : AppEncryptionKey
}
```

Where an app-keyed store's key comes from. The app owns the secret; the library never chooses a key source and never keeps a key beyond the derived data key in memory.

| Type | Key | Provider rules |
|---|---|---|
| `Passphrase` | PBKDF2-HMAC-SHA256 over the Unicode-NFC UTF-8 passphrase and a 16-byte random salt; the same text typed composed or decomposed opens the same store | `AccessDenied` for an empty passphrase or one with an unpaired UTF-16 surrogate. `iterations` is for new or re-keyed files and must be `600_000..10_000_000` (`IllegalArgumentException` at construction); an existing file's own count wins within that range, and a header outside it is `Undecryptable(null)` with no derivation run |
| `Raw` | exactly 32 bytes that come from outside the store's directory (a systemd credential, a container secret, a KMS, a token) | `AccessDenied` for any other length |

Providers are called on first use and at most once per operation after a refusal; every array a provider returns is wiped as soon as it has been used, so return a fresh array on every
call. A provider that throws is `Unavailable(cause)`, except `CancellationException`, which propagates. Neither type reveals its secret in `toString`.

### `DesktopSecureStorageOptions`

<!-- listing -->
```kotlin
public class DesktopSecureStorageOptions(
    public val itemLabel: String? = null,
    public val allowUserInteraction: Boolean = true,
)
```

Options of an OS-key store for every operating system in one value; only the running OS's part applies. A plain class so a field can be added in a minor version.

| Option | Meaning |
|---|---|
| `itemLabel` | the label the OS keychain UI shows for the key item (Keychain Access, Seahorse); `null` uses the store's identifier; ignored on Windows, which has no OS item; must not be blank or contain a null character |
| `allowUserInteraction` | `false`: a call that would need an OS dialog (a keychain unlock, an access prompt) answers `Unavailable` at once instead of showing it: for CLI tools, ssh sessions and services. `true` (default) lets the OS ask |

### `DesktopKeyStore` and `probeDesktopKeyStore`

<!-- listing -->
```kotlin
public enum class DesktopKeyStore {
    MACOS_FILE_KEYCHAIN,
    WINDOWS_DPAPI,
    LINUX_SECRET_SERVICE,
    LINUX_SECRET_PORTAL,
}
```

`DesktopKeyStore` may grow in a minor version: give a `when` over it an `else` that fails closed.

`probeDesktopKeyStore(options)` reports which key store a secure store would use now, or why none can: `Success(DesktopKeyStore)`, `Unsupported` (no key store, no user session, an unusable runtime or OS),
`Unavailable` (a keychain locked while `allowUserInteraction = false`, a service that did not answer in time), or `OperationFailed(ENSURE_KEY, key = null)` for anything else. It never prompts, never reads,
creates or deletes a key, and answers within a bound even when the OS hangs, so it is safe at startup. It says which key store would serve, not that a store will work (it makes no cipher call). Only
`allowUserInteraction` of the options matters; `itemLabel` is ignored. `LINUX_SECRET_PORTAL` is reported only when libsecret actually selected its Flatpak portal backend. The set may grow in a
minor version: give a `when` an `else`.

---

## Options and validation rules

A wrong value is a bug at the call site, so every rule below throws `IllegalArgumentException` at construction and never arrives later as a `StorageError`:

| Class | Rule |
|---|---|
| `StorageConfig` | `name` is `null` or non-blank, with no `/`, `\`, space or null character |
| `DesktopSecureStorageOptions` | `itemLabel` is `null` or non-blank, with no null character |
| `AppEncryptionKey.Passphrase` | `iterations` in `600_000..10_000_000` |
| `AndroidUserAuthentication` | `validitySeconds >= 1` |
| `IosSecureStorageOptions` | `accessGroup` is `null` or non-blank; `synchronizable` needs an `accessibility` that does not end in `THIS_DEVICE_ONLY`; `synchronizable` excludes `userPresence` |
| `IosUserPresence` | `prompt` is not blank |

Runtime refusals that are typed instead: a desktop factory without a `config.name` throws `IllegalArgumentException`; the Android secure store reserves the record name
`.kmptoolkit.envelope-format` (a `put` of that key is `InvalidValue`); the plain iOS store refuses the key `AppleLanguages` with `OperationFailed(PUT)`; the desktop encrypted stores
refuse an entry name over 65,535 UTF-8 bytes and a file past 256 MiB with `InvalidValue`.
