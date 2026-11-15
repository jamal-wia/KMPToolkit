# kmptoolkit-storage — Guide

## The contract, in one table

Everything every store promises, on every platform:

| You do | You get |
|---|---|
| `get` a key nobody wrote | `Success(null)` |
| `get` a key written with `""` | `Success("")`: absent and empty are different |
| `put` over an existing key | the new value wins; no error, no insert-only variant |
| `remove` a key that is not there | `Success`: removal is idempotent |
| `clear` | only this store's entries; another store's are untouched |
| `put` then read from a second instance | the value, including after a process restart |
| a platform problem (a locked keychain, a full disk, a dead key) | a `StorageResult.Failure` with a typed `StorageError`, never an exception |
| call from the main thread | fine: every call is a main-safe `suspend` function |
| call from many coroutines at once | no corruption, no entry made unreadable by the overlap |

Failures are values, but not every throw is gone: a cancelled caller gets `CancellationException`, a JVM `Error` (an
`OutOfMemoryError`; the JDK's `InternalError` when the entropy source of its DRBG random generator cannot be read, on Windows
and wherever `securerandom.source` is changed) is never turned into a result, and the constructors of the option and
config classes throw `IllegalArgumentException` for a value that is a bug at the call site.

The one thing not promised is ordering under concurrency: two coroutines writing the same key race
and the winner is undefined. A read-modify-write (a counter, a list kept as one value) spans two
calls, and this module cannot make two of your calls atomic. Put a `Mutex` around your own logic:

<!-- sample: guide-launch-counter -->
```kotlin
class LaunchCounter(private val storage: KeyValueStorage) {
    private val mutex = Mutex()

    suspend fun increment(): Int = mutex.withLock {
        val next: Int = storage.getIntOr("launch_count", 0) + 1
        storage.putInt("launch_count", next).also(::logIfFailure)
        next
    }
}
```

What the module does make atomic is its own read-then-create: the Android encryption key behind
`SecureKeyValueStorage`, the desktop key and file, and the id behind `DeviceIdProvider`. Concurrent first uses of any of
them, from one instance or several over the same configuration, agree on a single key, a single id. A store
that cannot be written at all (a desktop file that no longer parses) gives `DeviceIdProvider` no place
to keep the id: it returns a new one per call until the host repairs or clears the store, and never
clears it on its own. `DeviceIdProvider` reads a stored id without taking any lock; only creating an id is
serialized (one process-wide lock, re-reading under it), so a store whose read never answers holds up
only its own caller.

A call whose coroutine is cancelled while it runs throws `CancellationException`, and may or may not
have taken effect: the platform call underneath is not interruptible. (A desktop native call that had not started when its
caller was cancelled never runs.)

## Choosing between the stores

| | `KeyValueStorage` | `SecureKeyValueStorage` | `AppKeyEncryptedKeyValueStorage` (desktop) |
|---|---|---|---|
| Backed by | `SharedPreferences`, an `NSUserDefaults` suite, a properties file | Keystore-encrypted prefs, Keychain, or an encrypted file whose key an OS key store holds | an encrypted file whose key your passphrase or raw key wraps |
| Cost per call | microseconds | a cipher round trip, an IPC hop, or a whole-file rewrite | a whole-file rewrite, plus a key derivation on first use |
| Readable from a copied data directory | yes | no | no |
| Can a value become permanently unreadable | no | **yes**: plan for it | the wrong key is `AccessDenied` and changes nothing |
| Survives app uninstall | no | no on Android, **yes on iOS** | the files are yours to delete |
| Good for | flags, drafts, ids, preferences | tokens, secrets your server can re-issue | secrets with no OS key store, or a passphrase gate |

The asymmetries in the last rows are the ones that catch people:

- **A secure value can be lost while the app is untouched.** Restoring a backup to a new phone brings the ciphertext
  without the key; the platform can invalidate a key for good (on Android, a key bound to `userAuthentication` when the lock
  screen is removed); on desktop a deleted or replaced key item does the same. All surface as `StorageError.Undecryptable`, and the only correct response is to discard
  the value and obtain a new one. Never store something in here that the user cannot get back.
- **iOS Keychain items outlive an uninstall.** Reinstalling the app can hand you a token from the
  previous install. If that is wrong for you, clear the secure store on first run, keyed off a flag
  in the *plain* store, which is removed with the app.
- **The stores are readable by the whole app.** They are not sandboxes between features. Two
  features get separate stores by using separate names, not separate privileges. On desktop an OS-key store is scoped to the
  user, not to the app: other programs of the same user can ask the key store for the key (see the
  [threat model](05-platform-notes.md#threat-model)).

## Configuration and naming

`StorageConfig` has one field:

<!-- sample: guide-config -->
```kotlin
val appStore: StorageConfig = StorageConfig(name = null)                       // Android, iOS: derived from your application id
val sessionStore: StorageConfig = StorageConfig(name = "com.example.session")  // an independent store; required on desktop
```

`null` resolves at runtime to `Context.getPackageName()` on Android and `CFBundleIdentifier` on iOS. On desktop there is no
application id, so every desktop factory requires a name (`IllegalArgumentException` otherwise). Everything else (the
preferences file name, the `NSUserDefaults` suite, the Keystore alias, the Keychain service, the desktop file and key item) is
derived from that one name. The exact strings are in [`05-platform-notes.md`](05-platform-notes.md); what matters here is the
guarantee:

- **Two different names share nothing**, on any platform, in any store.
- **The same name opens the same store**, from any number of instances, in any order.
- **The plain and the secure store of one name are still two stores.** Writing `"token"` to one does
  not make it readable from the other.

A name is an identifier, not a path: `/`, `\`, spaces and a null character are rejected by
`StorageConfig`'s constructor, because a `SharedPreferences` file name containing a separator
silently writes outside the preferences directory.

There is no way to point the module at a preferences file or a Keychain service some earlier version
of your app created. That is deliberate (see "Not a migration tool" in [`01-overview.md`](01-overview.md)); copying old data
across is a one-time job for your own code, and a worked recipe is below.

## Handling errors

Six cases, six reactions (the table is in [`01-overview.md`](01-overview.md#the-errors-and-one-reaction-each)). In code:

<!-- sample: guide-handle-errors -->
```kotlin
suspend fun handle(storage: EncryptedKeyValueStorage, result: StorageResult<String?>) {
    if (result is StorageResult.Failure) {
        when (val error: StorageError = result.error) {
            // Nothing is lost. Retry later (after the first unlock following a reboot, typically), and do not
            // destroy state on the strength of it.
            is StorageError.Unavailable -> scheduleRetry(error.cause)

            // A dismissed prompt, or a key you supplied that does not open the store. Retry only when the user acts.
            is StorageError.AccessDenied -> askAgainOnUserAction()

            // This environment cannot run the store. `error.reason` names the one fix.
            is StorageError.Unsupported -> fallBackExplicitly(error.reason)

            // Plaintext is gone for good. A named key is removed; a null key is the whole store.
            is StorageError.Undecryptable -> {
                when (val key: String? = error.key) {
                    null -> storeLost()
                    else -> storage.remove(key).also(::logIfFailure)
                }
                reacquire()
            }

            // `platformCode` carries an iOS OSStatus where there is one and `cause` the exception where the platform
            // threw; both are for your logs, not for a user.
            is StorageError.OperationFailed -> report(error)

            // The same value fails the same way: drop it or fix the caller.
            is StorageError.InvalidValue -> dropValue(error.key)
        }
    }
}
```

`when (val key: String? = error.key)` is not decoration: Kotlin refuses to smart-cast a public property declared in another
module, so `storage.remove(error.key)` does not compile for the nullable key. The `.also(::logIfFailure)` keeps the compiler's
return-value checker satisfied.

**`Undecryptable(key)` against `Undecryptable(null)`.** A named key means one entry cannot be read: remove it and ask again for
what it held. A `null` key means the whole store cannot be read: its key is lost or replaced (a deleted key item), or the file itself
does not parse or was written by a newer library version. The way out is `clear()`, then writing again: `clear()` always works on an
OS-key store (it empties a file whose key is lost and moves aside a file it cannot parse). When only the key is lost, `remove` also
works and the next `put` starts a fresh key; on a file that does not parse, `remove` and `ensureKey()` answer `Undecryptable(null)`
until `clear()` or a `put` moves the file aside, and a file of a newer version is touched only by `clear()`.

A few habits are worth forming:

- **Never reach for a `...Or` helper on an encrypted store.** `getStringOrNull`, `getStringOr`, `getIntOr`, `getLongOr` and
  `getBooleanOr` are deprecated on an `EncryptedKeyValueStorage`: a default turns "locked", "dismissed" and "key lost" into "absent",
  which a "signed out" path then acts on. Use `get()` and handle the failure.
- **Check the result of a write** for anything you will later assume is there. A `put` that returned
  `Failure` and was ignored turns into a read that looks like a fresh install. The stores' functions are annotated for the compiler's
  return-value checker, so a dropped result is a warning when you enable it.
- **Logging a result is safe.** `StorageResult.Success.toString()` prints a constant and never the value, and an error's cause
  names the rule that was broken and never a stored value, so a log line or a crash report cannot carry a secret.
  The keys of entries may appear; values never do. In a test compare `.value` to see the values in a failure message.

## Shared code: which type to take

- Take `KeyValueStorage` for anything that is not a secret: flags, drafts, ids.
- Take `SecureKeyValueStorage` when the secret needs OS custody of the key.
- Take `EncryptedKeyValueStorage` when either kind of encrypted store will do. It still excludes plain stores, and `protection` tells
  which kind you got.
- Never take `KeyValueStorage` for a secret: a plain store satisfies it.
- A secret that must resist other programs of the same user (a kiosk PIN) checks the lower bound once:
  `require(storage.protection == SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED)` (the sample is in
  [`01-overview.md`](01-overview.md#protection-a-lower-bound)).

## Writing several related keys

There is no atomic multi-key write: five `put` calls are five writes, and a crash between them leaves a mix of old and new
values. Keep one logical record in **one serialized value** under one key, so the record is replaced as a whole:

<!-- sample: guide-one-record -->
```kotlin
data class Session(val accessToken: String, val refreshToken: String, val userId: String)

// Use whatever serialization you already have (kotlinx.serialization, say); this one only has to round-trip.
fun Session.encode(): String = listOf(accessToken, refreshToken, userId).joinToString("\t")

fun decodeSession(text: String): Session? {
    val parts: List<String> = text.split("\t")
    return if (parts.size == 3) Session(parts[0], parts[1], parts[2]) else null
}

suspend fun saveSession(secrets: EncryptedKeyValueStorage, session: Session): StorageResult<Unit> =
    secrets.put("session", session.encode())
```

## Placing the prompt

On the first use of an encrypted store the platform may ask the user for something: macOS shows a keychain dialog, Linux may unlock
the collection, and a passphrase store asks your provider. Without help it arrives at an arbitrary later write. `ensureKey()` lets
you choose the moment: explain the prompt, then call it. It resolves (on a first run, creates) and verifies the key, and nothing
else. Every other operation still verifies the key itself, so skipping `ensureKey()` is always safe; it only moves the prompt. On iOS
it never prompts and never mutates anything (there is no key to create: one Keychain item holds each value). The per-platform
contract is in [`04-api-reference.md`](04-api-reference.md#ensurekey-per-platform).

## Cross-process behaviour

On desktop two processes (two instances of the app, a CLI tool beside the app) may open the same store.

- **Writes are serialized across processes** by a lock file next to the store file (`<file>.lock`, kept, empty, never deleted). One 10 s
  budget covers the in-process and the file-lock wait of a call; a call that cannot get the lock within it answers `Unavailable` and
  changes nothing.
- **Plain reads take no lock and never wait.** Encrypted reads take the lock too, so a header is never read in the middle of a
  creation.
- **A key another process replaced is noticed.** An OS-key store verifies its cached key against the file on every operation and
  fetches the current key when they disagree, so the second process does not have to restart.
- **Network file systems** may grant or refuse locks unreliably; keep a store on a local volume. A file system that refuses locks
  altogether makes the plain store coordinate within the process only, and the encrypted stores answer `OperationFailed`.
- Android and iOS stores are per process by the platform's own rules.

The details and the limits (two copies of the library in one JVM, a stalled Linux keyring) are in
[`05-platform-notes.md`](05-platform-notes.md#desktop).

## Ownership and lifecycle

There is nothing to release. A store holds a `SharedPreferences` handle or an `NSUserDefaults` suite
(both process-wide and both owned by the platform), a lazily resolved key-store handle on Android, a file path on desktop, plus
(desktop OS-key stores) one shared native thread that the library starts on the first call. No native buffers of yours, no listeners,
no threads of its own for the plain and the app-key store: calls run on the shared `Dispatchers.IO` pool. Creating a second store
over the same config is free and gives you a view of the same data, so passing the interface around beats caching it in a singleton.

Both factories take only an application context on Android, so a store cannot leak an Activity. After a process restart, or a
re-created store over the same name, everything reads back; there is no `close`.

## Working with values that are not strings

The store is string-valued on every platform. For the three types apps store most, the module ships
extensions that fix one encoding, so every caller writes a value the same way and reads back what
another caller wrote:

| Type | Stored as | Read | Read with a default (plain stores) |
|---|---|---|---|
| `Int` | decimal string, `"-42"` | `getInt(key): StorageResult<Int?>` | `getIntOr(key, default): Int` |
| `Long` | decimal string | `getLong(key): StorageResult<Long?>` | `getLongOr(key, default): Long` |
| `Boolean` | exactly `"true"` / `"false"` | `getBoolean(key): StorageResult<Boolean?>` | `getBooleanOr(key, default): Boolean` |
| `String` | as is | `get(key)` | `getStringOr(key, default): String` |

Writes are `putInt`, `putLong` and `putBoolean`, returning `StorageResult<Unit>` like `put`.

The two read styles differ in what they do with a value that is there but is not the requested type
(`"forty-two"` read with `getInt`):

- **`getInt` reports it** as `StorageError.InvalidValue(key)`. Its cause names the requested type and never the stored value, so it is
  safe to log, even for an encrypted store. It is not read as absent: two callers disagreeing about a key's type is a bug worth
  seeing, and retrying the same value cannot help.
- **`getIntOr` folds it into the default**, exactly like an absent key or an unreadable store. Use it
  where a sensible default exists and a wrong value is not worth handling on its own (a counter, a
  preference, a "hint already shown" flag), on a plain store.

Anything richer (JSON, an enum, a timestamp type) is yours to encode on top of `put` and `get`.
Serialize with whatever you already use, and remember that a stored payload has a schema you will
have to version.

A value written by other code with the platform's typed setters (`SharedPreferences.putInt`,
`NSUserDefaults.setInteger`) is not something these accessors read: this module opens stores of its
own naming, and a store another component wrote is not one of them.

## Recipes

### Recipe: migrating from an app-encrypted file

The usual case: an app that kept its own encrypted file, with the key beside the ciphertext, moves to an encrypted store of this
library. The rule is that **the new store is the truth** and old copies are erased last, so a crash or a rerun at any point can
only repeat work, never lose a value or copy an old one over a newer one:

1. For each record, read the new store first. A value there is the truth, so erase the old copy without reading it.
   `Undecryptable` on the new store counts as absent here.
2. Otherwise read the old value (with the app's own code), `put` it, and read it back equal before going on. If it does not read
   back equal, remove the new value and stop, so the old copy stays the only truth.
3. Erase the old copy last, one key at a time. Never `clear()` the old store: it may be shared with something else. A failed erase is
   retried by step 1 on the next start.
4. Erase the app's own old key material (the key that sat next to the ciphertext) only after every ciphertext it protected is gone.
5. Then rotate the tokens server-side, because the old key sat next to the ciphertext.

<!-- sample: recipe-migrate -->
```kotlin
interface LegacySecretFile {
    suspend fun read(key: String): String?       // the app's own decryption
    suspend fun erase(key: String): Boolean      // one key at a time, never the whole file
    suspend fun eraseKeyMaterial(): Boolean      // the old key that sat next to the ciphertext
}

/** Returns true when every record is in [target] and the old file and its key are gone; a rerun is always safe. */
suspend fun migrateSecrets(old: LegacySecretFile, target: EncryptedKeyValueStorage, keys: List<String>): Boolean {
    var complete = true
    for (key in keys) {
        // 1. The new store is the truth.
        when (val existing: StorageResult<String?> = target.get(key)) {
            is StorageResult.Success -> if (existing.value != null) {
                if (!old.erase(key)) complete = false
                continue
            }
            is StorageResult.Failure -> if (existing.error !is StorageError.Undecryptable) {
                complete = false
                continue
            }
        }
        // 2. Copy, then read it back equal.
        val value: String = old.read(key) ?: continue
        if (target.put(key, value) !is StorageResult.Success) {
            complete = false
            continue
        }
        val readBack: StorageResult<String?> = target.get(key)
        if (readBack !is StorageResult.Success || readBack.value != value) {
            target.remove(key).also(::logIfFailure)
            complete = false
            continue
        }
        // 3. Erase the old copy last.
        if (!old.erase(key)) complete = false
    }
    // 4. The old key material goes only when nothing it protected is left.
    if (complete && !old.eraseKeyMaterial()) complete = false
    return complete
}
```

### Recipe: a passphrase store

`createAppKeyEncryptedKeyValueStorage` with `AppEncryptionKey.Passphrase` keeps the data key wrapped by a passphrase the user types.
The library never keeps the passphrase, never derives one from machine facts (do not either: a key that is a function of the
machine protects nothing from the machine), and calls your provider on first use and at most once per operation.

<!-- sample: recipe-passphrase -->
```kotlin
fun createVault(directory: File, unlockDialog: PassphraseDialog): AppKeyEncryptedKeyValueStorage =
    createAppKeyEncryptedKeyValueStorage(
        directory,
        StorageConfig("com.example.myapp.vault"),
        AppEncryptionKey.Passphrase(provider = { unlockDialog.await() }),
    )

/** The unlock screen: true when the vault is open. */
suspend fun unlock(vault: AppKeyEncryptedKeyValueStorage): Boolean =
    when (val opened: StorageResult<Unit> = vault.ensureKey()) {
        is StorageResult.Success -> true
        is StorageResult.Failure -> {
            when (opened.error) {
                is StorageError.AccessDenied -> showUnlockDialogAgain()   // a wrong passphrase: ask again, on a user action
                is StorageError.Unavailable -> retryLater()               // the provider threw: try later
                else -> report(opened.error)
            }
            false
        }
    }

/** "Change passphrase": re-encrypts under a fresh data key. Copies taken earlier stay readable with the old passphrase. */
suspend fun changePassphrase(vault: AppKeyEncryptedKeyValueStorage, newPassphraseDialog: PassphraseDialog): StorageResult<Unit> =
    vault.changeKey(AppEncryptionKey.Passphrase(provider = { newPassphraseDialog.await() }))
```

- A wrong passphrase is `AccessDenied` on every operation, `get` of an absent key and `clear()` included, and changes nothing. It is never
  `Undecryptable`, so the "remove on `Undecryptable`" recipe cannot fire on a typo. An empty passphrase, or one with an unpaired UTF-16
  surrogate, is also `AccessDenied`.
- A provider that throws is `Unavailable(cause)`. `CancellationException` propagates.
- Return a fresh `CharArray` on every call: the library wipes every array it receives as soon as it has used it.
- The default PBKDF2 cost is 600,000 iterations; `Passphrase(provider, iterations)` accepts `600_000..10_000_000` and the constructor
  throws outside it.
- **A forgotten passphrase has no reset in the API.** The app deletes the store's files (their names are in
  [`05-platform-notes.md`](05-platform-notes.md#desktop-secure-stores)) and starts over.
- Never derive the passphrase from machine facts. A raw key (below) is the right shape for a key that is not typed.

### Probe-driven user experience

On desktop, ask `probeDesktopKeyStore()` before offering anything that needs an OS key store, and give every answer a reaction:

<!-- sample: recipe-probe -->
```kotlin
enum class SecretsPlan { OsKeyStore, PassphraseStore, InMemoryOnly, TryLater, FixRuntime }

fun planFor(probe: StorageResult<DesktopKeyStore>): SecretsPlan = when (probe) {
    // Offer "Remember me", explain the prompt, then call ensureKey().
    is StorageResult.Success -> SecretsPlan.OsKeyStore
    is StorageResult.Failure -> when (val error: StorageError = probe.error) {
        is StorageError.Unsupported -> when (error.reason) {
            // A developer message: add kmptoolkit-storage-jna or run on JDK 22+.
            UnsupportedReason.UNSUPPORTED_RUNTIME -> SecretsPlan.FixRuntime
            // No keyring or no user session: keep the session in memory only, or offer the passphrase store.
            UnsupportedReason.NO_KEY_STORE, UnsupportedReason.NO_USER_SESSION -> SecretsPlan.PassphraseStore
            else -> SecretsPlan.InMemoryOnly
        }
        // The keychain is locked and no dialog is allowed, or a service did not answer: keep in memory, retry later.
        is StorageError.Unavailable -> SecretsPlan.TryLater
        is StorageError.AccessDenied, is StorageError.OperationFailed,
        is StorageError.Undecryptable, is StorageError.InvalidValue -> SecretsPlan.InMemoryOnly
    }
}
```

The probe says which key store a store would use, not that a store will work (it makes no cipher call); the first `ensureKey()` is
the real test. `UnsupportedReason` may grow in a minor version, hence the `else`.

### Recipe: headless and CI

A service, a CLI tool or a CI job has no user to prompt. Use `allowUserInteraction = false` so a call that would need an OS dialog
answers `Unavailable` at once instead of waiting for one, or take the key from outside the store's directory with a raw key (a
systemd credential, a container secret, a KMS):

<!-- sample: recipe-headless -->
```kotlin
fun createHeadlessStores(directory: File, keyFile: File): Pair<SecureKeyValueStorage, AppKeyEncryptedKeyValueStorage> = Pair(
    createSecureKeyValueStorage(
        directory,
        StorageConfig("com.example.agent.os"),
        DesktopSecureStorageOptions(allowUserInteraction = false),
    ),
    // The provider returns exactly 32 bytes, fresh on every call; any other length is AccessDenied.
    createAppKeyEncryptedKeyValueStorage(
        directory,
        StorageConfig("com.example.agent.raw"),
        AppEncryptionKey.Raw(provider = { keyFile.readBytes() }),
    ),
)
```

In tests use `InMemoryKeyValueStorage` from `kmptoolkit-storage-testing` ([`06-testing.md`](06-testing.md)).
