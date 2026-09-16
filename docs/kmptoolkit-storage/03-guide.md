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

The store is string-valued on every platform. For the three types apps store most, the module ships
extensions that fix one encoding, so every caller writes a value the same way and reads back what
another caller wrote:

| Type | Stored as | Read | Read with a default |
|---|---|---|---|
| `Int` | decimal string, `"-42"` | `getInt(key): StorageResult<Int?>` | `getIntOr(key, default): Int` |
| `Long` | decimal string | `getLong(key): StorageResult<Long?>` | `getLongOr(key, default): Long` |
| `Boolean` | exactly `"true"` / `"false"` | `getBoolean(key): StorageResult<Boolean?>` | `getBooleanOr(key, default): Boolean` |
| `String` | as is | `get(key)` | `getStringOr(key, default): String` |

Writes are `putInt`, `putLong` and `putBoolean`, returning `StorageResult<Unit>` like `put`.

```kotlin
val launches: Int = storage.getIntOr("launch_count", 0)
storage.putInt("launch_count", launches + 1)
```

The two read styles differ in what they do with a value that is there but is not the requested type
— `"forty-two"` read with `getInt`:

- **`getInt` reports it** as `StorageError.OperationFailed` for `StorageOperation.GET`, with the
  parse exception as the cause. It is not read as absent: two callers disagreeing about a key's type
  is a bug worth seeing.
- **`getIntOr` folds it into the default**, exactly like an absent key or an unreadable store. Use it
  where a sensible default exists and a wrong value is not worth handling on its own — a counter, a
  preference, a "hint already shown" flag.

Anything richer — JSON, an enum, a timestamp type — is yours to encode on top of `put` and `get`.
Serialize with whatever you already use, and remember that a stored payload has a schema you will
have to version.

A value written by other code with the platform's typed setters (`SharedPreferences.putInt`,
`NSUserDefaults.setInteger`) is not something these accessors read: this module opens stores of its
own naming, and a store another component wrote is not one of them. Moving such data across is a
one-time copy in your own code — read the old store with the platform API, `putInt` the value into
the new one, and record that the copy is done.
