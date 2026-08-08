# kmptoolkit-storage — Guide

## The contract, in one table

Everything both stores promise, on both platforms:

| You do | You get |
|---|---|
| `get` a key nobody wrote | `Success(null)` |
| `get` a key written with `""` | `Success("")` — absent and empty are different |
| `put` over an existing key | the new value wins; no error, no insert-only variant |
| `remove` a key that is not there | `Success` — removal is idempotent |
| `clear` | only this store's entries; another store's are untouched |
| `put` then read from a second instance | the value, including after a process restart |
| anything, ever | a `StorageResult` — never an exception |

The one thing not promised is ordering under concurrency. The backends are internally synchronized,
so nothing corrupts, but two threads writing the same key race and the winner is undefined. If you
need read-modify-write atomicity, put a lock around your own logic — this module cannot give you
one that spans two calls.

## Choosing between the two stores

| | `KeyValueStorage` | `SecureKeyValueStorage` |
|---|---|---|
| Backed by | `SharedPreferences` / `NSUserDefaults` suite | Keystore-encrypted prefs / Keychain |
| Cost per call | microseconds | a cipher round trip / an IPC hop |
| Readable from a copied data directory | yes | no |
| Can a value become permanently unreadable | no | **yes** — plan for it |
| Survives app uninstall | no | no on Android, **yes on iOS** |
| Good for | flags, drafts, ids, preferences | tokens, secrets your server can re-issue |

The asymmetries in the last three rows are the ones that catch people:

- **A secure value can be lost while the app is untouched.** Changing the device lock destroys the
  Android Keystore entry; restoring a backup to a new phone brings the ciphertext without the key.
  Both surface as `StorageError.Undecryptable`, and the only correct response is to discard the
  value and obtain a new one. Never store something in here that the user cannot get back.
- **iOS Keychain items outlive an uninstall.** Reinstalling the app can hand you a token from the
  previous install. If that is wrong for you, clear the secure store on first run, keyed off a flag
  in the *plain* store, which is removed with the app.
- **Both stores are readable by the whole app.** They are not sandboxes between features. Two
  features get separate stores by using separate names, not separate privileges.

## Configuration and naming

`StorageConfig` has one field:

```kotlin
StorageConfig(name = null)                        // name derived from your app's own identifier
StorageConfig(name = "com.example.session")       // an independent store
```

`null` resolves at runtime to `Context.getPackageName()` on Android and `CFBundleIdentifier` on iOS.
Everything else — the preferences file name, the `NSUserDefaults` suite, the Keystore alias, the
Keychain service — is derived from that one name. The exact strings are in
[`05-platform-notes.md`](05-platform-notes.md); what matters here is the guarantee:

- **Two different names share nothing**, on either platform, in either store.
- **The same name opens the same store**, from any number of instances, in any order.
- **The plain and the secure store of one name are still two stores.** Writing `"token"` to one does
  not make it readable from the other.

A name is an identifier, not a path: `/`, `\`, spaces and a null character are rejected by
`StorageConfig`'s constructor, because a `SharedPreferences` file name containing a separator
silently writes outside the preferences directory.

There is no way to point the module at a preferences file or a Keychain service some earlier version
of your app created. That is deliberate — see "Not a migration tool" in
[`01-overview.md`](01-overview.md) — and copying old data across is a one-time job for your own code,
best done once and recorded with a flag.

## Handling errors

Three cases, and they call for three different responses:

```kotlin
when (val error: StorageError = result.error) {
    // The store cannot be opened at all right now. Nothing is lost. Retry later — after the first
    // unlock following a reboot, typically — and do not destroy state on the strength of it.
    is StorageError.Unavailable -> scheduleRetry(error.cause)

    // This entry's plaintext is gone for good. Remove it, re-acquire whatever it held, and do not
    // retry: the next read will fail the same way.
    is StorageError.Undecryptable -> { storage.remove(error.key); reacquire() }

    // The operation itself failed. `platformCode` carries an iOS OSStatus where there is one and
    // `cause` the exception where the platform threw; both are for your logs, not for a user.
    is StorageError.OperationFailed -> report(error)
}
```

Two habits are worth forming:

- **Do not treat a failed read as an absent value** for anything that decides whether a user stays
  signed in. `getStringOrNull()` exists for flags and caches, where the difference genuinely does
  not matter; reach for `get()` everywhere else.
- **Check the result of a write** for anything you will later assume is there. A `put` that returned
  `Failure` and was ignored turns into a read that looks like a fresh install.

## Ownership and lifecycle

There is nothing to release. A store holds a `SharedPreferences` handle or an `NSUserDefaults` suite
— both process-wide and both owned by the platform — plus, on Android, a lazily resolved key-store
handle. No native buffers, no listeners, no threads. Creating a second store over the same config is
free and gives you a view of the same data, so passing the interface around beats caching it in a
singleton.

Both factories take only an application context on Android, so a store cannot leak an Activity.

## Working with values that are not strings

Strings in, strings out. Encode and decode at your own boundary:

```kotlin
fun KeyValueStorage.putBoolean(key: String, value: Boolean) = put(key, value.toString())
fun KeyValueStorage.getBoolean(key: String): Boolean? = getStringOrNull(key)?.toBooleanStrictOrNull()
```

The module deliberately does not ship these. A typed accessor has to decide what a malformed value
means — `null`, a default, an error — and that decision belongs to the code that wrote the value,
not to a library that never sees it. The same goes for JSON: serialize with whatever you already
use, and remember that a stored payload has a schema you will have to version.
