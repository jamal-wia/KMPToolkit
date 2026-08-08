# kmptoolkit-storage — Overview

One `KeyValueStorage` interface over `SharedPreferences` and `NSUserDefaults`, a
`SecureKeyValueStorage` over the Android Keystore and the iOS Keychain, and a `DeviceIdProvider`
for a stable per-install identifier.

## The problem it solves

Every app keeps a handful of small values that have to survive a restart: a session token, "has
this user seen onboarding", a selected language, a draft the user walked away from. On Android that
is `SharedPreferences`; on iOS it is `NSUserDefaults`; for the ones that are secrets it is the
Keystore and the Keychain, which agree about nothing. Shared Kotlin code cannot name any of them.

```kotlin
// Platform layer — the only place a factory is named.
val storage: KeyValueStorage = createKeyValueStorage(context)   // Android; no argument on iOS
val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(context)

// Shared code — takes the interface, never the factory.
when (val token = secrets.get("refresh_token")) {
    is StorageResult.Success -> token.value?.let(::resume) ?: signIn()
    is StorageResult.Failure -> when (token.error) {
        is StorageError.Undecryptable -> signIn()          // the stored session is gone for good
        is StorageError.Unavailable -> retryAfterUnlock()  // the store may come back
        is StorageError.OperationFailed -> report(token.error)
    }
}
```

Four things follow from that shape, and they are why the module exists:

- **Every failure is a value.** A key store that will not open, an entry whose key the platform
  destroyed, a write that could not be committed — each is a `StorageError` case you branch on.
  Nothing in the public API throws.
- **Absent and unreadable are different.** `Success(null)` means nobody ever wrote that key;
  `Failure(Undecryptable)` means something was written and cannot be read back. Collapsing the two
  into `null` is how apps end up silently signing a user out and calling it a cache miss. The
  shorthand `getStringOrNull()` is there for the cases where you genuinely do not care.
- **Nothing is named for you.** The preferences file, the `NSUserDefaults` suite, the Keystore
  alias and the Keychain service are all derived from a `StorageConfig` whose default is your own
  application id. Two apps built on this library cannot collide, and two features of one app can
  keep separate stores by passing different names.
- **The secure store has the same shape as the plain one.** `SecureKeyValueStorage` extends
  `KeyValueStorage` and adds a guarantee, not a method, so shared code can take either and a test
  can substitute one for the other.

## What this is **not**

- **Not a database.** No queries, no tables, no indexes, no relations, no migrations, no
  transactions. Two writes are two writes; nothing groups them. Anything with structure, growth, or
  a schema wants SQLDelight or Room, and this module will be a poor substitute long before it is an
  obviously wrong one.
- **Not a cache.** No eviction, no size limit, no expiry, no TTL, no invalidation. What you put in
  stays until you remove it or the user clears the app's data. A cache that never evicts is a leak
  with good manners — if entries accumulate, you need something else.
- **Not a secrets manager.** `SecureKeyValueStorage` encrypts values at rest with the platform's own
  key store. It does not rotate keys, escrow them, sync between devices, gate access behind
  biometrics, or protect anything from a user who controls the device and is determined. It is the
  right place to cache a token the server can re-issue. It is the wrong place for the only copy of
  something a user cannot get back.
- **Not typed or structured storage.** Strings in, strings out. Serializing an object and parsing it
  back is yours, and so is the versioning problem that comes with it.
- **Not observable.** There is no `Flow`, no listener, no change notification. A value is read when
  you read it. If two parts of your app must react to the same value changing, hold that state in
  your own layer and use this only to persist it.
- **Not asynchronous.** Every call is blocking and completes in microseconds to a couple of
  milliseconds — except a `SecureKeyValueStorage` operation, which is a cipher round trip on Android
  and an IPC hop to `securityd` on iOS. Neither belongs in a tight loop on the main thread. Store a
  handful of values in the secure store, not a working set.
- **Not ordered under concurrency.** The platform backends will not corrupt themselves, but two
  threads writing the same key race and the winner is undefined. Read-modify-write atomicity is
  yours to arrange.
- **Not a migration tool.** It opens stores of its own naming. It will not adopt a preferences file
  or a Keychain service an earlier version of your app created; copying that data across is a
  one-time job for your own code.
- **Not a device fingerprint.** `DeviceIdProvider` returns a locally generated random id that is
  reset by clearing app data or reinstalling. It carries nothing about the hardware or the user, and
  it authenticates nobody.

## When to use it

Use it when shared Kotlin code needs a small number of values to outlive the process and you would
rather handle a typed failure than discover a platform quirk in a crash report. Tokens, flags,
last-selected-tab, a device id for support.

If you are single-platform and happy to call `SharedPreferences` directly, you do not need this
indirection — its value is the shared contract, the error taxonomy, and the naming discipline, and
all three only pay off across more than one platform or more than one caller.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working store in five minutes
- [`03-guide.md`](03-guide.md) — configuration, error handling, choosing between the two stores
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — what is on disk, the crypto choices, the iOS 26 Keychain trap
- [`06-testing.md`](06-testing.md) — `InMemoryKeyValueStorage` and what the suite does and does not cover
