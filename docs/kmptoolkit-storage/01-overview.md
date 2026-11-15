# kmptoolkit-storage — Overview

One `KeyValueStorage` interface over `SharedPreferences`, `NSUserDefaults` and, on desktop, a properties
file; encrypted stores whose key is held by the Android Keystore, the iOS Keychain, the macOS keychain,
Windows DPAPI or the Linux Secret Service, or supplied by your app; and a `DeviceIdProvider` for a stable
per-install identifier.

| Minimum | Android | iOS | Desktop (`jvm`) |
|---|---|---|---|
| Platform | API 24 | iOS 15 | JDK 21 (the OS-key store needs JDK 22+, or JDK 21 with `kmptoolkit-storage-jna`) |

## The problem it solves

Every app keeps a handful of small values that have to survive a restart: a session token, "has
this user seen onboarding", a selected language, a draft the user walked away from. On Android that
is `SharedPreferences`; on iOS it is `NSUserDefaults`; on desktop it is whatever file you write. For the ones that are
secrets it is the Keystore, the Keychain, DPAPI and the Secret Service, which agree about nothing. Shared Kotlin code
cannot name any of them.

<!-- sample: overview-resume-session -->
```kotlin
// Shared code: it takes the interface, never a factory. Every call is a main-safe suspend function.
suspend fun resumeSession(secrets: SecureKeyValueStorage) {
    when (val token: StorageResult<String?> = secrets.get("refresh_token")) {
        is StorageResult.Success -> token.value?.let(::resume) ?: signIn()
        is StorageResult.Failure -> when (val error: StorageError = token.error) {
            is StorageError.Unavailable -> retryLater()                    // nothing is lost; try again
            is StorageError.AccessDenied -> askAgainOnUserAction()         // never loop, never delete
            is StorageError.Unsupported -> fallBackExplicitly(error.reason) // e.g. keep the session in memory
            is StorageError.Undecryptable -> signIn()                      // the stored session is gone for good
            is StorageError.OperationFailed -> log(error)                  // retryable I/O
            is StorageError.InvalidValue -> dropValue(error.key)           // never retry the same value
        }
    }
}
```
The platform layer builds the store with a factory (see [`02-getting-started.md`](02-getting-started.md)); the function above
is the shape of every read of an encrypted store.

Five things follow from that shape, and they are why the module exists:

- **Every failure is a value.** A key store that will not open, an entry whose key the platform
  destroyed, a user who dismissed a prompt, a write that could not be committed: each is a `StorageError` case you branch
  on, and each case has exactly one reaction (table below). Storage operations return `StorageResult` and do not throw
  for platform trouble. The exceptions are `CancellationException` for a cancelled caller, an `Error` of the JVM
  itself (an `OutOfMemoryError`, the JDK's `InternalError` from an unreadable entropy source), and the constructors, which
  reject a developer error such as a blank `StorageConfig` name with `IllegalArgumentException`.
- **Absent and unreadable are different.** `Success(null)` means nobody ever wrote that key;
  `Failure(Undecryptable)` means something was written and cannot be read back. Collapsing the two
  into `null` is how apps end up silently signing a user out and calling it a cache miss. That is why the
  `...Or` helpers and `getStringOrNull()` are deprecated on an encrypted store: they remain for a plain store, where a
  default is a normal thing to want.
- **Nothing is named for you.** The preferences file, the `NSUserDefaults` suite, the Keystore
  alias, the Keychain service and the desktop file and key item are all derived from a `StorageConfig`. On Android and
  iOS its default is your own application id; on desktop there is no application id, so the name is required. Two apps
  built on this library cannot collide, and two features of one app keep separate stores by passing different names.
- **Custody of the key is visible.** `SecureKeyValueStorage` and the desktop app-key store share the parent
  `EncryptedKeyValueStorage`, which adds `protection`, a lower bound that says who holds the key and who is kept out,
  and `ensureKey()`, which lets you choose when the one prompt happens. A plain store is not an
  `EncryptedKeyValueStorage`, so a function that takes one can never be handed plaintext storage by mistake.
- **Concurrency cannot break the store.** Every call is a `suspend` function that moves its own
  blocking work off the caller's thread, and any number of coroutines may call at once: nothing
  corrupts, and no entry becomes unreadable because two first uses overlapped. The places where
  the library itself does read-then-create (the Android encryption key, the device id, the desktop key) are
  atomic across the whole process, and on desktop across processes.

## The three kinds of store

<!-- listing -->
```
KeyValueStorage                                  plain: nothing is encrypted
 └─ EncryptedKeyValueStorage                     adds protection and ensureKey()
     ├─ SecureKeyValueStorage                    an OS key store holds the key (or, on iOS, the values)
     └─ AppKeyEncryptedKeyValueStorage           desktop: the key comes from your app (passphrase or raw key)
```

| Kind | Factory | What protects the data |
|---|---|---|
| Plain | `createKeyValueStorage` | nothing; owner-only file permissions on desktop |
| OS-key secure | `createSecureKeyValueStorage` | Android Keystore, iOS Keychain; on desktop the macOS keychain, Windows DPAPI or the Linux Secret Service holds one data key and the data is an encrypted file |
| App-key encrypted (desktop) | `createAppKeyEncryptedKeyValueStorage` | a passphrase (PBKDF2-HMAC-SHA256) or a raw 256-bit key your app provides; no operating-system call at all |

### Which factory to choose

| You need to keep | Platform | Create | You get |
|---|---|---|---|
| flags, drafts, ids, preferences | any | `createKeyValueStorage` | `KeyValueStorage` |
| a token the server can re-issue | Android, iOS | `createSecureKeyValueStorage` | `SecureKeyValueStorage` |
| a token the server can re-issue | desktop with a user session and a key store | `createSecureKeyValueStorage(directory, config)` | `SecureKeyValueStorage` |
| a secret on desktop with no key store (a server, a CI box, an SSH session), or one that a passphrase should guard | desktop | `createAppKeyEncryptedKeyValueStorage` | `AppKeyEncryptedKeyValueStorage` |
| a secret in shared code that may run on either kind | any | take `EncryptedKeyValueStorage` as the parameter type | whatever the platform layer built |

The library never chooses between an OS-key store and a passphrase store for you, and never falls back from one to a weaker one: a
desktop without a key store answers `Unsupported(NO_KEY_STORE)` and the app decides (see
[`03-guide.md`](03-guide.md#probe-driven-user-experience)).

### `protection`: a lower bound

`protection` is known without I/O. It is constant for every store except an app-key store, which reports the type of the key it is bound to, so a `changeKey` to the other key type (passphrase to raw key, or back) changes it. Each value states a guarantee that always holds; a
platform may offer more, never less.

| `SecureStorageProtection` | Who holds the key | Kept out | Not kept out |
|---|---|---|---|
| `OS_KEY_STORE_APP_ISOLATED` | Android Keystore, iOS Keychain | other OS users, other apps, offline copies | copies the app opted into (iOS: an accessibility without `THIS_DEVICE_ONLY` lets encrypted backups carry the items, `synchronizable` puts them in iCloud Keychain) |
| `OS_KEY_STORE_USER_SCOPED` | desktop OS key store | other OS users, offline copies | other programs of the same user (a prompt may stand in the way on macOS) |
| `PASSPHRASE` | derived from a passphrase | as strong as the passphrase | same-user code that captures keystrokes or reads memory |
| `APP_PROVIDED_KEY` | your app supplies 32 bytes | exactly as safe as wherever you keep that key | |
| `NONE` | nobody (test doubles only) | nothing | |

Shared code that must resist other programs of the same user (a kiosk PIN, say) checks it:

<!-- sample: overview-require-isolation -->
```kotlin
fun requireAppIsolation(storage: EncryptedKeyValueStorage) {
    require(storage.protection == SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED) {
        "this secret needs a store that other programs of the user cannot open"
    }
}
```

The full matrix of who can do what on each platform is in the threat model of
[`05-platform-notes.md`](05-platform-notes.md#threat-model). The set of values may grow in a minor version: give a `when`
over `SecureStorageProtection` an `else` that treats an unknown value as the weakest protection, never as the strongest.

## The errors, and one reaction each

`StorageError` has six cases. Match it exhaustively with no `else` (the sample above): a case is added only in a major version,
listed under `Breaking` in the changelog, so the compiler points at every `when` that needs a decision.

| Case | Means | Reaction |
|---|---|---|
| `Unavailable` | the store cannot be used right now (a locked device or keychain, a daemon that is starting, a lock wait or OS call that ran into its time limit) | retry later; nothing is lost; never delete |
| `AccessDenied` | the store refused this call: a prompt the user dismissed, or a key your app supplied that does not open the store | retry only on a user action; never in a loop; never delete |
| `Unsupported(reason)` | this store cannot work in this environment | retrying is pointless: tell the user or choose another store explicitly; `reason` names the fix |
| `Undecryptable(key)` | the entry `key`, or the whole store when `key` is `null`, cannot be decrypted with the key now available | remove the affected key (or every key) and re-acquire the value; retrying will not help |
| `OperationFailed` | the operation failed on I/O or an unexpected platform status | log it; the same call may succeed on retry |
| `InvalidValue(key)` | the value can never be accepted or parsed (a typed read of another type, a surrogate in a `put`) | never retry the same value; drop it or fix the caller |

`StorageOperation`, `UnsupportedReason` and `SecureStorageProtection` are descriptive enums and **may grow in a minor version**, and so are the platform ones, `DesktopKeyStore`, `AndroidKeyBacking` and `AndroidKeySecurityLevel`:
give a `when` over any of them an `else` that fails closed (log it, show a generic "not available here"), never an `else` that retries or deletes.

The cause of an error never carries a stored value, and `StorageResult.Success.toString()` prints a constant, so logging a
result never copies a secret into a log. See [`03-guide.md`](03-guide.md#handling-errors).

## What this is **not**

- **Not a database.** No queries, no tables, no indexes, no relations, no migrations, no
  transactions. Two writes are two writes; nothing groups them. Anything with structure, growth, or
  a schema wants SQLDelight or Room, and this module will be a poor substitute long before it is an
  obviously wrong one.
- **Not a cache.** No eviction, no size limit, no expiry, no TTL, no invalidation. What you put in
  stays until you remove it or the user clears the app's data. A cache that never evicts is a leak
  with good manners; if entries accumulate, you need something else. (Desktop encrypted stores rewrite their whole file on
  every write and refuse a file past 256 MiB.)
- **Not a secrets manager.** The encrypted stores encrypt values at rest under a key that is not in plaintext beside
  them. They do not rotate keys on a schedule, escrow them, or sync between devices (except an iOS store you made
  `synchronizable`), and they protect nothing from code running inside your process or from an administrator of the
  running machine. They are the right place to cache a token the server can re-issue and the wrong place for the only copy of
  something a user cannot get back.
- **Not structured storage.** Values are strings, with typed accessors for `Int`, `Long` and
  `Boolean` on top (see [`03-guide.md`](03-guide.md#working-with-values-that-are-not-strings)).
  Serializing an object and parsing it back is yours, and so is the versioning problem that comes
  with it. There is no atomic multi-key write: keep a logical record in one serialized value.
- **Not observable.** There is no `Flow`, no listener, no change notification. A value is read when
  you read it. If two parts of your app must react to the same value changing, hold that state in
  your own layer and use this only to persist it.
- **Not fast enough for a working set.** Calls suspend rather than block, but each one still costs
  a dispatcher hop plus the platform call: an encrypted operation is a cipher round trip on Android, an IPC hop to
  `securityd` on iOS, and a whole-file decrypt and rewrite on desktop. Store a handful of values in an encrypted store,
  not a working set, and keep calls out of per-item loops.
- **Not ordered under concurrency.** Two coroutines writing the same key race and the winner is
  undefined. Read-modify-write atomicity across two calls is yours to arrange, with a `Mutex` around
  your own logic.
- **Not a migration tool.** It opens stores of its own naming. It will not adopt a preferences file
  or a Keychain service an earlier version of your app created; copying that data across is a
  one-time job for your own code (a worked recipe is in [`03-guide.md`](03-guide.md#recipe-migrating-from-an-app-encrypted-file)).
- **Not a device fingerprint.** `DeviceIdProvider` returns a locally generated random id that is
  reset by clearing app data or reinstalling. It carries nothing about the hardware or the user, and
  it authenticates nobody.

## When to use it

Use it when shared Kotlin code needs a small number of values to outlive the process and you would
rather handle a typed failure than discover a platform quirk in a crash report. Tokens, flags,
last-selected-tab, a device id for support.

If you are single-platform and happy to call `SharedPreferences` directly, you do not need this
indirection: its value is the shared contract, the error taxonomy, and the naming discipline, and
all three only pay off across more than one platform or more than one caller.

## Read next

- [`02-getting-started.md`](02-getting-started.md): a working store in five minutes, on every platform
- [`03-guide.md`](03-guide.md): configuration, error handling, shared code, recipes
- [`04-api-reference.md`](04-api-reference.md): every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md): what is on disk, the crypto choices, the threat model, packaging
- [`06-testing.md`](06-testing.md): `InMemoryKeyValueStorage` and what the suite does and does not cover
- [`07-migrating-to-6.md`](07-migrating-to-6.md): upgrading from 5.x
