# kmptoolkit-storage — Testing

Testing the code *around* a store — a view model, a repository, a session loader — and what this
module's own suite does and does not cover.

## The fixture module

`InMemoryKeyValueStorage` ships in a separate artifact, consumed under `testImplementation`:

<!-- build-script -->
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

**Using it as a plain store.** Because the fixture is a `SecureKeyValueStorage`, an expression whose
static type is the fixture (or any `EncryptedKeyValueStorage`) resolves `getStringOrNull`,
`getStringOr`, `getIntOr`, `getLongOr` and `getBooleanOr` to the deprecated overloads for encrypted
stores, so a test that uses the fixture as a plain store gets a deprecation warning at each such
call. The warning is intended for production code and is harmless here; to avoid it, declare the
variable as the plain type (`val storage: KeyValueStorage = InMemoryKeyValueStorage()`) or read with
`get(...).getOrNull()`.

<!-- sample: testing-resumed -->
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
| `failEveryOperationWith = error` | every operation fails with that `StorageError`, with no effect, until you set it back to `null`; for a store that stays broken (a keyring that stays locked as `Unavailable`, an `Unsupported` environment) |

`failNextOperationWith` is checked first: while both are set, the next operation gets the one-shot
error and the one after it the sticky one. A `put` of an unpaired surrogate is rejected with
`InvalidValue` before either knob and consumes neither.

and observation:

| Property | Records |
|---|---|
| `contents` | a snapshot of the current entries of the shared `Backing` |
| `writes` | every key this instance was asked to write, in order, including overwrites and keys later removed |

Each read of `contents` or `writes` returns a mutable copy that is the caller's own, so a test may sort or clear it (a Java caller can, without a cast)
without affecting the fixture.

`writes` is the one that reading `contents` cannot replace: it tells a value written once apart from
one written, removed, and written again. It belongs to the instance, like the knobs and `protection`.
Under concurrent writers its order is the order in which the records landed, and an entry can be
visible in `contents` a moment before its record is in `writes` (one `put` updates the shared entries
and then the log, as two separate atomic steps).

Results of the fixture print redacted like the real stores': `Success.toString()` is the constant `Success(value=<redacted>)`, so a test that compares two
results and wants the values in its failure message compares their `.value`.

### Modelling a restart or a second process

Two instances built with one `InMemoryKeyValueStorage.Backing` read and write the same entries:

<!-- sample: testing-restart -->
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
fun `a store that is unsupported here is reported as unsupported, not as signed out`() = runTest {
    val storage = InMemoryKeyValueStorage()
    storage.failEveryOperationWith = StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)

    assertEquals(Session.Unsupported, SessionLoader(storage).load())
}
```

### Testing the error paths

<!-- sample: testing-error-paths -->
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
- **No "key lost" state.** On an OS-key store, after `Undecryptable(null)` the `remove` and `clear()`
  calls still work and the next `put` starts a fresh key; `failEveryOperationWith = Undecryptable(null)`
  would fail `remove` and `clear()` as well. To test a recovery path (clear, then sign in again),
  script the read with `failNextOperationWith = StorageError.Undecryptable(null)` and let `remove` and
  `clear()` run normally.

It is safe from any number of threads: the contents and the knobs are immutable snapshots changed
with compare-and-set, so no write is lost and an armed one-shot failure fails exactly one of any
number of concurrent operations. Operations on different keys have no defined order.

It also behaves like the real stores for a cancelled caller: each operation checks first whether its
caller is still active, so a cancelled caller gets a `CancellationException` and nothing changes (no
entry, no `writes` record, and an armed `failNextOperationWith` stays armed). A test that asserts
"nothing is saved when the save is cancelled" therefore means the same against the fixture and
against a real store.

## Testing the module itself

The suite is run per module with targeted tasks while a change is in progress, and as a whole at the end:

```bash
./gradlew build checkKotlinAbi
./gradlew testAndroidHostTest iosSimulatorArm64Test
```

The suite is arranged around one shared contract class:

- **`commonTest`** holds `KeyValueStorageContractTest`, an abstract class asserting everything in
  [`03-guide.md`](03-guide.md)'s contract table. Every real store subclasses it: the plain and the
  encrypted one on each platform, and on desktop the OS-key store (once per operating system's fake binding) and the app-key store, so
  an encrypted store is held to exactly the same observable behavior as the plain one. `commonTest` also covers
  `DeviceIdProvider`, `StorageConfig` validation, the identifier derivation, and the `StorageResult` helpers, and runs on the JVM
  (`jvmTest`, with the desktop stores' own tests), the Android host and the iOS simulator.
- **`androidHostTest`** (Robolectric, via the `kmptoolkit.androidtest` convention plugin) runs the
  contract against real `SharedPreferences` files, plus what is specific to each store: that the
  plain one stores plaintext, that the encrypted one does not, that ciphertext differs between two
  writes of the same value, that a corrupted or tampered entry is `Undecryptable` rather than
  absent, that a ciphertext moved to another key is `Undecryptable`, and that removing and clearing still work when the key store does not.
  `AndroidKeystoreKeySourceRaceTest` drives the key lookup from eight threads at once (on one
  instance, on eight, and during recovery from an unrecoverable alias) against a fake key store
  that holds every caller between "looked" and "generated", and asserts that exactly one key was
  generated and every caller got it. The key lifecycle (invalidation, a locked device on each API level, user authentication) runs against
  scripted key sources.
- **`commonTest`'s `DeviceIdProviderConcurrencyTest`** does the same for the device id: concurrent
  first calls, through one provider and through several, against a store that suspends inside
  every call, must agree on the one id that was persisted.
- **`iosTest`** runs the contract against a real `NSUserDefaults` suite, and the secure store's logic against an in-memory model of the Keychain
  (`InMemoryKeychainPort`, in the test source set only): the query construction, every `OSStatus` mapping, the write retry loop, `clear()`
  scoping, user presence and cancellation. Real-Keychain cases live in the host app `storage-ios-harness`.
- **`jvmTest`** runs every desktop store above an internal fake binding of the operating system's key store: macOS, Windows and
  Linux fakes that model items, locks, prompts and failures. It covers the file format, the key lifecycle, the lost-key table, cross-process
  locking with forked JVMs, size limits, the native-call layer's cancellation and timeouts, and the build-time scans that keep every use of the JDK's crypto classes behind one
  boundary. The fake binding is `internal`; a consumer tests with `InMemoryKeyValueStorage`.

### Real-store suites (desktop), opt-in per operating system

The fake binding proves everything above the operating system; the real key stores are exercised by opt-in suites that do nothing unless asked, so an ordinary
build never touches a keychain. `-Pkmptoolkit.secretstore=macos|windows|linux` enables the suite of the operating system the build runs on, and
`-Pkmptoolkit.secretstore.required=true` turns a skip into a failure, so a broken key store cannot pass as a green run. Every item a suite creates has a unique
test name and is removed by exactly that name; no other item is read, changed or deleted.

| Binding | Command (from the repository root) |
|---|---|
| FFM, macOS (suite M) | `./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmMacKeychainRealStoreTest' -Pkmptoolkit.secretstore=macos -Pkmptoolkit.secretstore.required=true` |
| FFM, Windows (suite W) | `./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmDpapiRealStoreTest' -Pkmptoolkit.secretstore=windows -Pkmptoolkit.secretstore.required=true` |
| FFM, Linux (suite L) | `./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmSecretServiceRealStoreTest' -Pkmptoolkit.secretstore=linux -Pkmptoolkit.secretstore.required=true` |
| FFM, Linux with libsecret older than 0.19 | the same with `--tests '*FfmSecretServiceOldLibsecretRealStoreTest'` and `-Pkmptoolkit.secretstore.oldLibsecret=true` (every call answers the dedicated missing-symbol status; above the seam that is `Unsupported(NO_KEY_STORE)`) |
| JNA, macOS / Windows / Linux | `./gradlew :kmptoolkit-storage-jna:jvmTest --tests '*RealKeychainSuiteTest'` (macOS), `'*RealDpapiSuiteTest'` (Windows), `'*RealSecretServiceSuiteTest'` (Linux), each with the matching `-Pkmptoolkit.secretstore=` and `-Pkmptoolkit.secretstore.required=true`, on JDK 21 |

Preparation, per operating system:

- **macOS:** run in a logged-in session with an unlocked login keychain. If a dialog appears, stop the run: nothing in the suite may prompt.
- **Windows:** run in the logged-on user's interactive session (a scheduled task with `/it`), never in a key-based ssh logon: there every DPAPI call fails with 1312
  (`ERROR_NO_SUCH_LOGON_SESSION`), which the suite reports as "the environment cannot run it".
- **Linux:** the suite needs a session bus and an unlocked default collection, for example
  `dbus-run-session -- sh -c 'echo "" | gnome-keyring-daemon --unlock --components=secrets; <gradle command>'`. The JNA suite takes
  `-Pkmptoolkit.secretstore.scenario=unlocked|nobus|noservice` for the three environments it can be prepared as. Two JNA cases prove the Flatpak portal detection with a negative and a positive
  control in a fresh JVM each: `SECRET_BACKEND=file` with libsecret's own test hooks `SECRET_FILE_TEST_PATH` and `SECRET_FILE_TEST_PASSWORD` answers "the portal" without a Secret Service (libsecret 0.20 or newer
  with its file backend).
- **Every OS:** the suites loop their operations and assert that the open-descriptor count and the native-object ledger come back to where they started.

The Linux cases that manipulate the daemon (a locked collection, a killed daemon, a dismissed prompt) and the real-store behaviour of the macOS verification items in
[`05-platform-notes.md`](05-platform-notes.md#desktop-secure-stores) are manual runs that need a scripted environment.

### Docs samples

Every Kotlin snippet in this documentation set is checked, in two parts, so that a snippet that does not compile or does not run is a doc defect found by a build:

- **Compiled as a consumer.** The snippets live in the non-published module `docs-samples` (not named `kmptoolkit-*`, so the BOM does not list it), which depends on `kmptoolkit-storage` as an ordinary
  project dependency. Kotlin's cross-module rules therefore apply to them: a smart cast on a public property of another module (`error.key`) does not compile, and the compiler's return-value checker
  is on, so a snippet that drops a result fails the build. `:docs-samples:compileKotlinJvm` is the check.
- **Run, with the fake binding.** The same files are compiled into `kmptoolkit-storage`'s own `jvmTest`, where the internal fake binding is visible, and `DocsSamplesDesktopRunTest` runs the desktop and shared snippets against the OS-key store over that fake and
  against the app-key store. The Android snippets run in `AndroidDocsSamplesRound11Test` (the `AndroidDocsSamplesRound9Test` pattern) and the iOS ones are compiled in `iosTest`.
- **Kept equal to the docs.** `DocsSamplesDriftTest` extracts every `kotlin` fence of `01` to `07`: a fence marked `<!-- sample: id -->` must equal, line for line, the code between the `// sample:begin id` and `// sample:end id` lines of exactly
  one source file; the other fences must be marked `<!-- listing -->` (an API signature listing) or `<!-- build-script -->`. An unmarked fence fails the test.

### The gaps, stated plainly

- **The AndroidKeyStore is not exercised on the JVM.** Robolectric registers no `AndroidKeyStore`
  provider, so `KeyStore.getInstance` fails there. The key *source* is therefore a seam: tests
  substitute a locally generated AES-256 key, and everything downstream (IV handling, GCM
  parameters, Base64 framing, and every behavior of the store built on them) runs exactly as it
  does in production. What is untested off-device is the key-store lookup itself, the StrongBox outcomes, the time-bound authentication window, and the platform's real
  locked-device and invalidation exceptions; the one thing that can be asserted about the lookup here is covered: its absence degrades to `StorageError.Unavailable`
  rather than to a crash.
- **The iOS Keychain is exercised on a device or simulator only by the harness.** A Kotlin/Native test executable is not an app: it has no
  bundle and no keychain entitlement, so every `SecItem*` call returns `errSecNotAvailable`
  (`-25291`). The suite asserts the store's logic against the in-memory Keychain model, and the real round trips, the access-group, synchronizable
  and user-presence cases run in `storage-ios-harness` (a host app that a person launches in the simulator; its verdict lines and the Face ID enrolment are in
  `storage-ios-harness/ios-host/README.md` and in [`../../RELEASING.md`](../../RELEASING.md)).
- **The real desktop key stores are tested only when a suite is asked for.** Above the seam everything is covered by the fake binding on every machine; the OS itself is covered by the opt-in suites above.

Both mobile gaps are properties of the test environment rather than of the design, and both are worth
knowing before reading a green suite as proof that encryption works end to end: it is not quite
that. Confirming a real round trip on a device or simulator app is a manual check, and
it is worth repeating whenever this module's platform code changes.

### Why the fixture restates the contract

`InMemoryKeyValueStorage`'s own suite in `kmptoolkit-storage-testing` repeats the contract cases
rather than extending `KeyValueStorageContractTest`: Kotlin's `internal` does not cross a module
boundary and a test source set cannot be published for another module to subclass. The cost is that
the two could drift, so both are derived from the same documented contract, and a change to it has
to be made in both places; the drift rule is extended to the docs by the samples check above. The fixture's suite also carries what only a fixture has: the shared
`Backing`, the two failure knobs and their order, `ensureKey`, and seeded storms from 64 coroutines on
`Dispatchers.Default`.
