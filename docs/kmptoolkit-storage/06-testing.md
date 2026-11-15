# kmptoolkit-storage — Testing

Testing the code *around* a store — a view model, a repository, a session loader — and what this
module's own suite does and does not cover.

## The fixture module

`InMemoryKeyValueStorage` ships in a separate artifact, consumed under `testImplementation`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-storage:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-storage-testing:<version>")
        }
    }
}
```

Separate for the reason given in
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts):
a fixture inside the production module would ship to every consumer's runtime classpath. It depends
on `kmptoolkit-storage` with `api`, so one line gets you both.

## Why you need a double

The real stores need a `Context` and a preferences file on Android, a Keychain on iOS and a
directory on desktop. The first two do not exist in a plain unit test, and none of them can be made
to fail on demand — which is the interesting
half, since the branches worth testing are the ones where the store *does not* behave.

## `InMemoryKeyValueStorage`

It implements `SecureKeyValueStorage`, and therefore `KeyValueStorage`, so it substitutes for
either. It keeps the same contract as the real stores — absent reads back as `null`, `""` reads back
as `""`, `put` overwrites (and fails with `InvalidValue` for a key or value with an unpaired surrogate, as the real stores do), `remove` of an absent key succeeds, `clear` empties the instance's `Backing` (its own, unless you share one).
Nothing is encrypted; a double that encrypted would need the very key store that is missing. What it
claims about that is its `protection`: `SecureStorageProtection.NONE`, the truth about a map, unless
a test passes another value (`InMemoryKeyValueStorage(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED)`)
to feed code that branches on it. It implements `ensureKey()` too: it succeeds, prompts for nothing
and changes nothing, unless a failure knob is set.

```kotlin
@Test
fun `a stored session is resumed`() = runTest {
    val storage = InMemoryKeyValueStorage()
    storage.put("refresh_token", "abc")

    val session = SessionLoader(storage).load()

    assertEquals(Session.Resumed("abc"), session)
}
```

What it adds is control:

| Knob | Effect |
|---|---|
| `failNextOperationWith = error` | the next operation — `get`, `put`, `remove`, `clear` or `ensureKey` — fails with that `StorageError` and has no effect, then the knob clears |
| `failEveryOperationWith = error` | every operation fails with that `StorageError`, with no effect, until you set it back to `null`; for a store that stays broken (a keyring that stays locked, `Unsupported`) |

`failNextOperationWith` is checked first: while both are set, the next operation gets the one-shot
error and the one after it the sticky one. A `put` of an unpaired surrogate is rejected with
`InvalidValue` before either knob and consumes neither.

and observation:

| Property | Records |
|---|---|
| `contents` | a snapshot of the current entries of the shared `Backing` |
| `writes` | every key this instance was asked to write, in order, including overwrites and keys later removed |

`writes` is the one that reading `contents` cannot replace: it tells a value written once apart from
one written, removed, and written again. It belongs to the instance, like the knobs and `protection`.

### Modelling a restart or a second process

Two instances built with one `InMemoryKeyValueStorage.Backing` read and write the same entries:

```kotlin
@Test
fun `the session survives a process restart`() = runTest {
    val backing = InMemoryKeyValueStorage.Backing()
    InMemoryKeyValueStorage(backing = backing).put("refresh_token", "abc")

    // "Restart": a new instance over the same backing. Knobs and write logs do not follow it.
    val restarted = InMemoryKeyValueStorage(backing = backing)

    assertEquals(Session.Resumed("abc"), SessionLoader(restarted).load())
}

@Test
fun `a keyring that stays locked is reported as unsupported, not as signed out`() = runTest {
    val storage = InMemoryKeyValueStorage()
    storage.failEveryOperationWith = StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)

    assertEquals(Session.Unsupported, SessionLoader(storage).load())
}
```

### Testing the error paths

```kotlin
@Test
fun `a destroyed key forces a fresh sign-in`() = runTest {
    val storage = InMemoryKeyValueStorage()
    storage.put("refresh_token", "abc")
    storage.failNextOperationWith = StorageError.Undecryptable("refresh_token")

    assertEquals(Session.SignedOut, SessionLoader(storage).load())
}

@Test
fun `a Keychain that is not ready is retried rather than treated as signed out`() = runTest {
    val storage = InMemoryKeyValueStorage()
    storage.failNextOperationWith = StorageError.Unavailable()

    assertEquals(Session.RetryLater, SessionLoader(storage).load())
}
```

Those two are the tests worth writing for any code that reads a token. Distinguishing them is the
whole reason the API returns a `StorageResult` instead of a `String?`, and code that gets it wrong
signs users out for a reason they will never be able to describe to support.

The one-shot knob applies to whichever call comes next, whatever it is — scripting a failure and then
writing means the *write* fails. Set it again to fail more than one call, or use
`failEveryOperationWith` for a failure that does not clear.

### What the fake does not do

- **No encryption.** `Undecryptable` never occurs on its own; script it.
- **No persistence beyond the `Backing`.** A second instance is an empty, independent store unless you
  hand it the first one's `Backing`.
- **No platform failures.** `Unavailable`, `AccessDenied`, `Unsupported` and `OperationFailed`
  likewise only happen when scripted.
- **No key and no prompt.** `ensureKey()` never creates or verifies anything; use the knobs to script
  what the real call can answer.

It is safe from any number of threads: the contents and the knobs are immutable snapshots changed
with compare-and-set, so no write is lost and an armed one-shot failure fails exactly one of any
number of concurrent operations. Operations on different keys have no defined order, and no operation
suspends, so a cancelled caller never leaves one half done.

## Testing the module itself

```bash
./gradlew :kmptoolkit-storage:build :kmptoolkit-storage-testing:build checkKotlinAbi
./gradlew :kmptoolkit-storage:jvmTest :kmptoolkit-storage:testAndroidHostTest :kmptoolkit-storage:iosSimulatorArm64Test
./gradlew :kmptoolkit-storage-testing:jvmTest :kmptoolkit-storage-testing:testAndroidHostTest :kmptoolkit-storage-testing:iosSimulatorArm64Test
```

The suite is arranged around one shared contract class:

- **`commonTest`** holds `KeyValueStorageContractTest`, an abstract class asserting everything in
  [`03-guide.md`](03-guide.md)'s contract table. Every real store subclasses it — the plain and the
  encrypted one on each platform — so the encrypted store is held to exactly the same observable
  behavior as the plain one. `commonTest` also covers `DeviceIdProvider`, `StorageConfig`
  validation, the identifier derivation, and the `StorageResult` helpers, and runs on the JVM
  (`jvmTest`, with the desktop store's own tests), the Android host and the iOS simulator.
- **`androidHostTest`** (Robolectric, via the `kmptoolkit.androidtest` convention plugin) runs the
  contract against real `SharedPreferences` files, plus what is specific to each store: that the
  plain one stores plaintext, that the encrypted one does not, that ciphertext differs between two
  writes of the same value, that a corrupted or tampered entry is `Undecryptable` rather than
  absent, that a ciphertext moved to another key is `Undecryptable`, and that removing and clearing still work when the key store does not.
  `AndroidKeystoreKeySourceRaceTest` drives the key lookup from eight threads at once — on one
  instance, on eight, and during recovery from an unrecoverable alias — against a fake key store
  that holds every caller between "looked" and "generated", and asserts that exactly one key was
  generated and every caller got it.
- **`commonTest`'s `DeviceIdProviderConcurrencyTest`** does the same for the device id: concurrent
  first calls, through one provider and through several, against a store that suspends inside
  every call, must agree on the one id that was persisted.
- **`iosTest`** runs the contract against a real `NSUserDefaults` suite and pins the two iOS
  specifics that can be checked without a device: that a `KeychainQuery` is not answered with
  `errSecParam`, and that every `OSStatus` maps to the error a caller should branch on.

### The two gaps, stated plainly

- **The AndroidKeyStore is not exercised on the JVM.** Robolectric registers no `AndroidKeyStore`
  provider, so `KeyStore.getInstance` fails there. The key *source* is therefore a seam: tests
  substitute a locally generated AES-256 key, and everything downstream — IV handling, GCM
  parameters, Base64 framing, and every behavior of the store built on them — runs exactly as it
  does in production. What is untested off-device is the key-store lookup itself, and the one thing
  that can be asserted about it here is covered: its absence degrades to `StorageError.Unavailable`
  rather than to a crash.
- **The Keychain is not exercised at all.** A Kotlin/Native test executable is not an app: it has no
  bundle and no keychain entitlement, so every `SecItem*` call returns `errSecNotAvailable`
  (`-25291`). A round trip can only be verified in an app on a simulator or device. What the suite
  does assert is the same code path an app on a locked-out Keychain takes — every operation returns
  a typed failure, none throws, none hangs — plus the query-construction guard described in
  [`05-platform-notes.md`](05-platform-notes.md). The same limit means the `errSecDuplicateItem`
  retry that settles two concurrent first writes of one item is verified by reading, not by a test.

Both gaps are properties of the test environment rather than of the design, and both are worth
knowing before reading a green suite as proof that encryption works end to end — it is not quite
that. Confirming a real round trip on a device or simulator app is a five-minute manual check, and
it is worth repeating whenever this module's platform code changes.

### Why the fixture restates the contract

`InMemoryKeyValueStorage`'s own suite in `kmptoolkit-storage-testing` repeats the contract cases
rather than extending `KeyValueStorageContractTest`: Kotlin's `internal` does not cross a module
boundary and a test source set cannot be published for another module to subclass. The cost is that
the two could drift, so both are derived from the same documented contract, and a change to it has
to be made in both places. The fixture's suite also carries what only a fixture has: the shared
`Backing`, the two failure knobs and their order, `ensureKey`, and seeded storms from 64 coroutines on
`Dispatchers.Default`.
