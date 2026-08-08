# kmptoolkit-storage — API reference

Every public symbol in `io.github.jamal_wia.kmptoolkit.storage`.

---

## `KeyValueStorage`

```kotlin
public interface KeyValueStorage {
    public fun get(key: String): StorageResult<String?>
    public fun put(key: String, value: String): StorageResult<Unit>
    public fun remove(key: String): StorageResult<Unit>
    public fun clear(): StorageResult<Unit>
}
```

A string-keyed, string-valued store whose contents survive a process restart.

| Member | Contract |
|---|---|
| `get` | `Success(null)` when the key was never written. `Failure` only when the store could not be read — never for a missing key |
| `put` | Overwrites. The value is readable by any instance over the same config once this returns |
| `remove` | Idempotent; succeeds for a key that is not there |
| `clear` | Removes only this store's entries. Never another store's, never the iOS standard defaults |

Nothing throws. Not ordered under concurrent access — see [`03-guide.md`](03-guide.md).

---

## `SecureKeyValueStorage`

```kotlin
public interface SecureKeyValueStorage : KeyValueStorage
```

The same contract, with values encrypted at rest by the platform's key store. It adds a guarantee,
not a method, so shared code can take either type and a test can substitute one for the other.

What the guarantee covers: values are encrypted, key names are not; the encryption key is held by
the platform, not in the app's files. What it does not cover: a value can become **permanently
unreadable** when the platform destroys the key — `StorageError.Undecryptable`. See
[`05-platform-notes.md`](05-platform-notes.md).

---

## `StorageResult<T>`

```kotlin
public sealed interface StorageResult<out T> {
    public data class Success<out T>(public val value: T) : StorageResult<T>
    public data class Failure(public val error: StorageError) : StorageResult<Nothing>
}

public fun <T> StorageResult<T>.getOrNull(): T?
public fun <T> StorageResult<T>.errorOrNull(): StorageError?
public val StorageResult<*>.isSuccess: Boolean
public fun KeyValueStorage.getStringOrNull(key: String): String?
```

`kotlin.Result` is not used because it can only carry a `Throwable`, and none of `StorageError`'s
cases are exceptional in the language's sense.

`getStringOrNull` collapses "absent" and "unreadable" into `null`. Convenient for a flag; wrong for
anything that decides whether a user stays signed in.

---

## `StorageError`

```kotlin
public sealed interface StorageError {
    public data class Unavailable(public val cause: Throwable? = null) : StorageError

    public data class Undecryptable(
        public val key: String,
        public val cause: Throwable? = null,
    ) : StorageError

    public data class OperationFailed(
        public val operation: StorageOperation,
        public val key: String? = null,
        public val platformCode: Int? = null,
        public val cause: Throwable? = null,
    ) : StorageError
}

public enum class StorageOperation { GET, PUT, REMOVE, CLEAR }
```

| Case | Means | Response |
|---|---|---|
| `Unavailable` | the store cannot be opened right now — a key store that will not load, a Keychain before first unlock | retry later; nothing is lost |
| `Undecryptable` | the entry exists and its plaintext cannot be recovered — the key that encrypted it is gone | discard and re-acquire; retrying will not help |
| `OperationFailed` | the operation itself failed. `platformCode` carries an iOS `OSStatus`, `cause` an exception where the platform threw one | log it; it is not a state to branch on |

Only a `SecureKeyValueStorage` produces `Undecryptable`.

---

## `StorageConfig`

```kotlin
public data class StorageConfig(public val name: String? = null)
```

Which store a factory opens. `null` resolves at runtime to `Context.getPackageName()` on Android and
`CFBundleIdentifier` on iOS; every platform identifier the module needs is derived from it.

Throws `IllegalArgumentException` for a blank name, or one containing `/`, `\`, a space, or a null
character — those are developer errors, not runtime conditions.

---

## `DeviceIdProvider`

```kotlin
public class DeviceIdProvider(
    storage: KeyValueStorage,
    key: String = DEFAULT_KEY,
) {
    public fun current(): String

    public companion object {
        public const val DEFAULT_KEY: String = "kmptoolkit.storage.device_id"
    }
}
```

A random v4 UUID generated on first call and persisted in `storage`. Stable across calls, instances,
and process restarts; reset by `clear()`, by clearing app data, and by a reinstall.

`current()` always returns an id and never fails. If the store cannot be written, the id is valid
for that call but a later call produces a different one — a degraded signal rather than an error to
handle. Nothing is memoized in memory, so a `clear()` takes effect on the very next call.

Not a fingerprint and not a credential — see [`01-overview.md`](01-overview.md).

---

## Android factories

```kotlin
public fun createKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
): KeyValueStorage

public fun createSecureKeyValueStorage(
    context: Context,
    config: StorageConfig = StorageConfig(),
): SecureKeyValueStorage
```

Only the application context is retained, so passing an Activity cannot leak it. Both are cheap,
infallible, and safe to call repeatedly: two stores over the same config see the same data. There is
nothing to release.

The secure factory does not touch the Keystore — the key is resolved on the first `get` or `put`, so
a key store that is unhappy produces `StorageError.Unavailable` rather than an exception during
startup.

---

## iOS factories

```kotlin
public fun createKeyValueStorage(config: StorageConfig = StorageConfig()): KeyValueStorage

public fun createSecureKeyValueStorage(
    config: StorageConfig = StorageConfig(),
    accessibility: KeychainAccessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
    accessGroup: String? = null,
): SecureKeyValueStorage
```

`accessGroup` files items under a `kSecAttrAccessGroup` for sharing with an app extension or a
sibling app. A group your entitlements do not grant makes every operation fail with
`OperationFailed` — the library cannot check entitlements for you.

The factories are per-platform rather than `expect`/`actual` because Android needs a `Context` and
iOS needs nothing; see `docs/01-architecture.md`.

---

## `KeychainAccessibility` (iOS only)

```kotlin
public enum class KeychainAccessibility {
    WHEN_UNLOCKED,
    WHEN_UNLOCKED_THIS_DEVICE_ONLY,
    AFTER_FIRST_UNLOCK,
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,   // default
    WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
}
```

The `kSecAttrAccessible` attribute an item is added with. Set on first add and not changed by a
later write, so switching it affects new keys; clear the store to apply it to everything.

`ThisDeviceOnly` variants are excluded from backups and device-to-device transfer. The default
allows a background refresh to read the value after the first unlock following a reboot, without
letting it leave the device it was issued for.
