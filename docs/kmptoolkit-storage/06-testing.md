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

The real stores need a `Context` and a preferences file on Android, and a Keychain on iOS. Neither
exists in a plain unit test, and neither can be made to fail on demand — which is the interesting
half, since the branches worth testing are the ones where the store *does not* behave.

## `InMemoryKeyValueStorage`

It implements `SecureKeyValueStorage`, and therefore `KeyValueStorage`, so it substitutes for
either. It keeps the same contract as the real stores — absent reads back as `null`, `""` reads back
as `""`, `put` overwrites, `remove` of an absent key succeeds, `clear` empties only this instance.
Nothing is encrypted; a double that encrypted would need the very key store that is missing.

```kotlin
@Test
fun `a stored session is resumed`() {
    val storage = InMemoryKeyValueStorage()
    storage.put("refresh_token", "abc")

    val session = SessionLoader(storage).load()

    assertEquals(Session.Resumed("abc"), session)
}
```

What it adds is control:

| Knob | Effect |
|---|---|
| `failNextOperationWith = error` | the next operation — any of the four — fails with that `StorageError` and has no effect, then the knob clears |

and observation:

| Property | Records |
|---|---|
| `contents` | a snapshot of the current entries |
| `writes` | every key ever written, in order, including overwrites and keys later removed |

`writes` is the one that reading `contents` cannot replace: it tells a value written once apart from
one written, removed, and written again.

### Testing the error paths

```kotlin
@Test
fun `a destroyed key forces a fresh sign-in`() {
    val storage = InMemoryKeyValueStorage()
    storage.put("refresh_token", "abc")
    storage.failNextOperationWith = StorageError.Undecryptable("refresh_token")

    assertEquals(Session.SignedOut, SessionLoader(storage).load())
}

@Test
fun `a Keychain that is not ready is retried rather than treated as signed out`() {
    val storage = InMemoryKeyValueStorage()
    storage.failNextOperationWith = StorageError.Unavailable()

    assertEquals(Session.RetryLater, SessionLoader(storage).load())
}
```

Those two are the tests worth writing for any code that reads a token. Distinguishing them is the
whole reason the API returns a `StorageResult` instead of a `String?`, and code that gets it wrong
signs users out for a reason they will never be able to describe to support.

The knob applies to whichever call comes next, whatever it is — scripting a failure and then writing
means the *write* fails. Set it again to fail more than one call.

### What the fake does not do

- **No encryption.** `Undecryptable` never occurs on its own; script it.
- **No persistence.** A second instance is an empty, independent store.
- **No platform failures.** `Unavailable` and `OperationFailed` likewise only happen when scripted.
- **Not thread-safe**, exactly like the stores it replaces.

## Testing the module itself

```bash
./gradlew :kmptoolkit-storage:build :kmptoolkit-storage-testing:build checkKotlinAbi
./gradlew :kmptoolkit-storage:testDebugUnitTest :kmptoolkit-storage:iosSimulatorArm64Test
./gradlew :kmptoolkit-storage-testing:testDebugUnitTest :kmptoolkit-storage-testing:iosSimulatorArm64Test
```

The suite is arranged around one shared contract class:

- **`commonTest`** holds `KeyValueStorageContractTest`, an abstract class asserting everything in
  [`03-guide.md`](03-guide.md)'s contract table. Every real store subclasses it — the plain and the
  encrypted one on each platform — so the encrypted store is held to exactly the same observable
  behavior as the plain one. `commonTest` also covers `DeviceIdProvider`, `StorageConfig`
  validation, the identifier derivation, and the `StorageResult` helpers, and runs on both the JVM
  and the iOS simulator.
- **`androidUnitTest`** (Robolectric, via the `kmptoolkit.androidtest` convention plugin) runs the
  contract against real `SharedPreferences` files, plus what is specific to each store: that the
  plain one stores plaintext, that the encrypted one does not, that ciphertext differs between two
  writes of the same value, that a corrupted or tampered entry is `Undecryptable` rather than
  absent, and that removing and clearing still work when the key store does not.
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
  [`05-platform-notes.md`](05-platform-notes.md).

Both gaps are properties of the test environment rather than of the design, and both are worth
knowing before reading a green suite as proof that encryption works end to end — it is not quite
that. Confirming a real round trip on a device or simulator app is a five-minute manual check, and
it is worth repeating whenever this module's platform code changes.

### Why the fixture restates the contract

`InMemoryKeyValueStorage`'s own suite in `kmptoolkit-storage-testing` repeats the contract cases
rather than extending `KeyValueStorageContractTest`: Kotlin's `internal` does not cross a module
boundary and a test source set cannot be published for another module to subclass. The cost is that
the two could drift, so both are derived from the same documented contract, and a change to it has
to be made in both places.
