# kmptoolkit-storage — Platform notes

What is actually on disk, why the crypto looks the way it does, and the two platform traps that
shaped this module's implementation.

## Permissions and app configuration

**None.** No Android permission is declared or required, nothing goes into `Info.plist`, and no
entitlement is needed for the default configuration.

The one exception is opt-in and yours: passing `accessGroup` to the iOS
`createSecureKeyValueStorage` requires a matching **Keychain Sharing** entitlement in your app. The
library cannot check that for you — a group you do not have makes every operation fail with
`StorageError.OperationFailed`.

Following the repository rule in `docs/01-architecture.md`, this module declares nothing in its own
`AndroidManifest.xml`. There is, in fact, no manifest to declare it in.

## Where your data actually lives

Every identifier is derived from `StorageConfig.name`, which defaults to your application id. Given
a name `N`:

| | Android | iOS | Desktop (`jvm`) |
|---|---|---|---|
| Plain store | `SharedPreferences` file `N.kmptoolkit.storage` | `NSUserDefaults` suite `N.kmptoolkit.storage` | file `N.kmptoolkit.storage.properties` in the directory you pass |
| Secure store | `SharedPreferences` file `N.kmptoolkit.securestorage` | Keychain `kSecAttrService` = `N.kmptoolkit.securestorage` | file `N.kmptoolkit.securestorage.bin` (OS-key store) or `N.kmptoolkit.appkeystorage.bin` (app-key store) in the directory you pass, see "Desktop secure stores" |
| Encryption key | AndroidKeyStore alias `N.kmptoolkit.securestorage.key` | held by `securityd`; no alias of ours | the OS key store (macOS keychain item, Windows DPAPI blob in the file, Linux Secret Service item), or the key your app provides |

On desktop `N` has no default — there is no application id to derive it from — so
`StorageConfig.name` is required there.

These strings are an implementation detail — they are documented so you can find your own data with
`adb shell` or a Keychain dump, not so you can depend on them. They are also the reason
`StorageConfig` exists at all: nothing here is a constant baked into the library, so two apps, or
two KMPToolkit-based libraries in one process, cannot collide.

The suffix on the plain store is load-bearing on iOS. `NSUserDefaults(suiteName:)` returns the
**standard** defaults when the suite name equals the app's bundle identifier, and this module's
`clear()` is `removePersistentDomainForName` — on the standard domain that would wipe everything the
app and every embedded SDK ever wrote. Because the suffix is always appended, the resolved suite name
can never equal the bundle id, even if you pass it explicitly as `name`.

`NSUserDefaults` treats one key name specially: a string stored under `AppleLanguages` (the preferred languages, an array
the system owns) never reads back, so the plain iOS store refuses `put` for that key with
`StorageError.OperationFailed(PUT)` instead of answering `Success` and then `null`. Android and the JVM accept the name.

## Android: why not Tink, and why not `EncryptedSharedPreferences`

The code this module was ported from used **Tink** (`com.google.crypto.tink:tink-android`) with an
`AndroidKeysetManager`: a keyset stored in its own preferences file, wrapped by an Android Keystore
master key, encrypting values with AES-256-GCM. This module instead calls the Android Keystore
directly — `KeyGenParameterSpec` for an AES-256 key, `Cipher("AES/GCM/NoPadding")` for the values,
`Base64(iv ‖ ciphertext‖tag)` on disk.

The three candidates and what decided it:

| Option | Cost to a consumer | Status | Verdict |
|---|---|---|---|
| `tink-android` | roughly **1 MB** of APK, plus a protobuf runtime, in every consumer whether or not they use the secure store | healthy, actively maintained | rejected on size |
| `androidx.security:security-crypto` | similar — it depends on Tink itself | `MasterKey` / `EncryptedSharedPreferences` **deprecated upstream since 1.1.0-alpha06, with no replacement shipped** | rejected on both |
| AndroidKeyStore + `Cipher` | **zero** added dependencies | platform API, available since API 23; this module's `minSdk` is 24 | **chosen** |

The reasoning, stated plainly because a consumer deserves to know why their APK would have grown:

- **A megabyte is a lot to pay for an abstraction over an API we then call anyway.** Tink's value is
  its key management — key rotation, keysets with multiple versions, an envelope format that
  survives an algorithm migration. This module stores a handful of short-lived tokens under a single
  key, and none of that machinery is exercised. Tink would have added weight to every consumer,
  including the majority who use only the plain store, in exchange for capability nobody here uses.
- **The deprecated option is not an option.** `androidx.security-crypto`'s replacement was never
  shipped; building a library's security story on an API its own maintainers have abandoned is a
  migration scheduled for an inconvenient date.
- **The direct implementation is small enough to read.** It is one key, one transformation, one
  framing: the cipher itself is about a hundred lines (`ValueCipher.kt`, with its key source and errors, a few
  hundred) — small enough that a consumer auditing what happens to their
  tokens can finish in a sitting, which is not true of Tink.

What this trade costs, honestly:

- **No key rotation and no versioned envelope.** Changing the algorithm later means every existing
  entry reads back as `Undecryptable`. Acceptable because that is already a reachable state (see
  below) and every consumer must handle it; not acceptable for data a user cannot re-acquire, which
  this store is not for.
- **No FIPS story, no key-hierarchy features.** If you need those, you need Tink, and you should
  build on it directly rather than through this module.
- **We own the framing.** IV length, tag length and Base64 flags are ours to get right, and are
  pinned by tests in `KeystoreValueCipherTest` for exactly that reason.

Two further choices inside that implementation:

- **`setUserAuthenticationRequired` is deliberately left off.** An auth-bound key would make every
  read fail outside a recent unlock, which breaks a cold start and a background sync. Biometric
  gating is a different feature with a different key, bound to your own auth flow.
- **Hardware backing is neither required nor checked.** On a device with a TEE or StrongBox the
  platform uses it; on one without, the same API gives a software-isolated key. Refusing to store a
  token on the weaker device would only push you into storing it somewhere worse.

### The Android failure you must handle

Since 5.0.0 a secure value is also bound to the key name it is stored under: the key name is authenticated
associated data of the AES-GCM encryption, so a ciphertext copied from one key to another in the preferences file
(for example the access token over the password) fails its tag and reads as `Undecryptable`. Nothing written earlier
becomes unreadable: an entry that fails the bound check is read once more as an unbound one, which is what 4.0.0 and
earlier wrote. Such an old entry stays movable between keys until it is written again. The format on disk is unchanged
(`Base64(iv ‖ ciphertext‖tag)`), but a downgrade to 4.0.0 cannot read entries written by 5.0.0.

The Keystore entry is destroyed when the user **adds, changes, or removes the device lock**, and an
app restored to a new device from a backup carries the ciphertext without the key. In both cases the
value is gone while the entry remains, and reads return `StorageError.Undecryptable`. The module
recovers as far as it can — an alias whose key has become unrecoverable is dropped and a new key
generated, so *new* writes work again instead of the store being bricked for the life of the install
— but the old values are not recoverable by anyone.

A decrypt that fails for a *transient* keystore reason is the exception: when anything in the
failure's cause chain is an `android.security.KeyStoreException` whose `isTransientFailure()` is
`true` (a keystore daemon restarting, a provider briefly busy), the read returns
`StorageError.Unavailable` — retry later, nothing is lost — instead of `Undecryptable`. The cause
chain is walked because the platform usually delivers that exception wrapped in an
`InvalidKeyException` or a `ProviderException`. **This is API 33+ only**: `isTransientFailure()` does
not exist below it, so on API 24–32 every decrypt failure other than an unreachable key store is
still `Undecryptable`, as before. A failure the platform does not call transient is never remapped.

The same rule protects the key itself: a transient fault never makes the module drop its cached key
and look the alias up again, and a transient `UnrecoverableKeyException` during that lookup never
deletes the alias. The unrecoverable-alias recovery (delete and generate) runs only on the first key
resolution in a process. A key the platform rejects later is forgotten and looked up again once, but
that lookup never deletes: if it hits an unrecoverable entry — which on API 24-32 a keystore fault
also looks like, since nothing there can name a transient failure — the call fails with
`StorageError.Unavailable` and the next process start recovers a key that really died. A read that
hits a transient fault reports `Unavailable`; a write reports `OperationFailed`. The key and the
stored values are kept either way, so a momentary outage costs a retry, never the stored values.

### The key is created once per process, never once per caller

The key is generated on first use, and "look the alias up, generate if it is missing" is two
Keystore calls. Generating under an alias that already exists does not fail — it replaces the entry.
Before 3.0.0 nothing ordered those two calls, so two first uses that overlapped — two coroutines on
a fresh install, or two stores over the same configuration — could each generate a key; the later
one won the alias, and every value encrypted under the earlier one became `Undecryptable`. The same
gap existed in the recovery from a changed device lock, where a late caller could delete the
replacement key another caller had just made.

The lookup now runs under one lock for the whole process, shared by every store instance, and asks
the Keystore again once it holds the lock — so the second caller finds the first caller's key. The
lock is held only while a key is resolved, once per store instance.

**One process per store.** An in-process lock cannot order two *processes*, and the AndroidKeyStore
has no atomic create-if-absent. If your app runs components in a second process
(`android:process`), do not give that process the same secure store, or make sure the main process
has written to it at least once before the other one can start.

### Writes use `commit()`, not `apply()`

`apply()` returns before the write reaches disk, which would quietly break the durability half of
the contract — a token written immediately before the process is killed would be gone — and it has
no way to report failure, which would make `StorageError.OperationFailed` unreachable. The cost is a
blocking write of a few hundred bytes.

## iOS: the Keychain query must not be a bridged Kotlin `Map`

This is the trap that shaped `KeychainQuery.kt`, and it is worth stating precisely.

The obvious Kotlin/Native spelling of a `SecItem*` call builds an `NSMutableDictionary` (or a Kotlin
`Map`) and bridges it with `CFBridgingRetain(...) as CFDictionaryRef`. It is what most sample code
does, it is what the donor implementation this module was ported from did, and **on iOS 26 the
Security framework rejects it with `errSecParam` (`-50`)**.

What makes it expensive is the symptom rather than the cause: the rejection arrives as a status code
on a call nobody expects to fail, so an app that ignores the status sees a Keychain that is
mysteriously always empty, or — when the failure lands inside a startup path that waits on a token —
a hang on the splash screen with no error anywhere.

This module therefore builds every query with `CFDictionaryCreateMutable` and adds entries
individually with `CFDictionaryAddValue`:

```kotlin
KeychainQuery()
    .apply {
        putConstant(kSecClass, kSecClassGenericPassword)
        putString(kSecAttrService, service)
        putString(kSecAttrAccount, key)
    }
    .use { SecItemCopyMatching(it, result.ptr) }
```

`kSec*` globals are already `CFTypeRef`s and are passed straight through; our own strings and data
are bridged with `CFBridgingRetain` and released by `KeychainQuery.use`, which balances every
retain and releases the dictionary. `IosSecureKeyValueStorageTest` pins the behavior by asserting
that a query built this way is **not** answered with `errSecParam`.

If you ever edit that file: do not "simplify" it back to a dictionary literal.

### Other iOS specifics

- **Items outlive an app uninstall.** iOS does not remove an app's Keychain items when the app is
  deleted, so a reinstall can read a token written by the previous install. If that is wrong for
  you, clear the secure store on first run, keyed off a flag in the plain store — which *is* removed
  with the app.
- **`put` updates before it adds.** `SecItemAdd` on an existing item fails with `errSecDuplicateItem`
  rather than replacing it, so an unconditional add would make `put` non-overwriting. The update
  path carries no `kSecAttrAccessible`, which is why an item keeps the accessibility it was created
  with.
- **`clear` deletes by service.** One `SecItemDelete` matching the store's `kSecAttrService` and no
  account — items written by another store, another framework, or the same app under a different
  service are not matched.
- **Statuses that are translated:** `errSecItemNotFound` → absent; `errSecNotAvailable` (`-25291`)
  and `errSecInteractionNotAllowed` (`-25308`) → `Unavailable`; `errSecDecode` (`-26275`) →
  `Undecryptable`. Anything else keeps its raw code in `OperationFailed.platformCode` rather than
  being flattened into a category it does not belong to.
- **The plain store's writes cannot fail.** `NSUserDefaults` reports no status, so those operations
  always return `Success`. That is not a shortcut — there is genuinely no error to surface, and the
  result type exists for the Keychain store's sake.

## Desktop

The `jvm` target exists because `KeyValueStorage` is a type shared code takes as a parameter, and an
app sharing that code with desktop could not compile it for desktop without the interface — see
[`../01-architecture.md`](../01-architecture.md#desktop-targets).

- **Where.** `createKeyValueStorage(directory, config)` writes one properties file,
  `<name>.kmptoolkit.storage.properties`, into the directory you choose. The library does not pick a
  directory for you: where an app keeps its files on desktop is the app's decision and differs per
  operating system. The directory is created on the first write.
- **Why a file and not `java.util.prefs`.** `Preferences` caps a value at 8 KB and a key at 80
  characters, which would break this module's contract that a long value round-trips, and it lives in
  the Windows registry on one OS and a plist on another. A properties file behaves the same
  everywhere.
- **Durability.** Every write rewrites the whole file into a temporary sibling, syncs it to the device
  and atomically renames it over the old one, so a process killed — or a machine losing power —
  mid-write leaves the previous contents. A temporary file a killed write left behind is removed by
  `clear()`, which matches only this store's own temporary names (`<file name><digits>.tmp`, the shape of
  4.x too, or, for a store file name longer than 232 UTF-8 bytes, the name cut to 215 bytes plus `~` and 16
  hex characters of its SHA-256 as the prefix), never a neighbouring store's in-flight file. Every read reads the file afresh. The write is
  [`kmptoolkit-files`](../kmptoolkit-files/01-overview.md)' `writeAtomically` (`Durability.Synced`): on
  Windows a replace blocked by another process that holds the file open is retried for up to about
  210 ms, and when the file system refuses an atomic rename the write fails with `OperationFailed`
  instead of replacing the file non-atomically (earlier versions fell back to a plain replace).
- **Failures.** A corrupted or hand-edited file (a malformed `\uXXXX` escape), a name the file system
  rejects (`:` or `*` on Windows) and a denied security check arrive as `OperationFailed`, never as an
  exception. On Windows, a store whose name makes a reserved device name (`CON`, `NUL`, `COM1`, `aux`...:
  the part of the file name before its first dot) can be read but not written (`OperationFailed`); pick another
  name. A name that is a valid file name but longer than 232 bytes in all is still written: its temporary file
  uses a shortened, hashed prefix.
  The store is meant for a handful of small values; it is not a database.
- **Permissions.** The store file, its temporary siblings and the lock file are readable and writable by their owner only,
  from the moment they exist: POSIX mode 0600, or on Windows an ACL that grants only the current user and SYSTEM. A directory
  the store creates is 0700 (Windows: the same ACL, inheritable); a directory that already exists is never changed, so
  keep the directory you choose owner-only too (anyone who can write it can roll the file back or delete it). A volume that
  stores no permissions (FAT, exFAT) ignores the request, and the write still succeeds. An external `chmod` of the data file
  is undone by the next write, which replaces the file. **This is a behaviour change:** an app that shared the file with another
  OS user stops working.
- **Concurrency.** Instances over the same file in one process share a lock (a `KeyedMutex` keyed by the
  case- and Unicode-folded canonical path). Writes are also coordinated **across processes**: after the in-process
  lock, a `put`, `remove` or `clear()` takes `FileChannel.tryLock` on a separate sibling,
  `<file name>.lock`, so two app instances or two parallel tools never lose each other's updates. One budget of 10
  seconds covers both waits of a write, the in-process one and the file lock (polled every 25 ms): a write that cannot get
  both within it answers `StorageError.Unavailable` and changes nothing, however many writes queue in front of it, and the
  file is never touched without the lock. Only the waiting is timed, never the write itself. A lock dies with its process,
  so a killed holder never wedges the store.
  - The lock file is empty, is created the first time a write needs it (a read never creates it, and neither does a
    `clear()` of a store whose directory does not exist) through an atomic create-if-absent, owner-only from the moment
    it exists (POSIX 0600; the Windows owner-and-SYSTEM ACL), and is never renamed and never deleted by the library.
    On a volume without hard links (FAT, exFAT, or a mount that refuses links) it is created with `CREATE_NEW` and the
    same owner-only attributes (mode 0600 where the provider has POSIX modes; on Windows the owner-and-SYSTEM ACL); a file
    another creator made first is accepted, and if the ACL cannot be built the write answers `OperationFailed` and no lock
    file is created. Delete it by hand only while no
    process uses the store. When `<file name>.lock` would exceed 255 bytes, the file name is cut at a character
    boundary and `~`, 16 hex characters of its SHA-256 and `.lock` follow.
  - The lock is on the sibling and never on the data file because on Windows a mandatory lock on the data file would block
    the atomic rename.
  - Inside one JVM only one thread at a time ever opens a lock file: a process-wide claim per lock file (its folded canonical
    path) is taken first, and a contender of the same JVM is simply busy without opening anything. This is not
    cosmetic: POSIX file locks belong to the process, so closing any other channel of the lock file in the same JVM would
    silently release a lock a thread holds, and the JDK's own table of overlapping locks can lose an entry under that churn.
    Two names for one lock file that the folded canonical path does not join (a hard link) are outside the claim. The claim
    is per class loader, so for a **second copy of the library in one JVM** (a plug-in host, an application server) a
    contender that meets the JDK's JVM-wide overlapping-lock check keeps its lock-file channel open instead of closing it
    (one descriptor per such lock file for the life of the process, and only in that setup). That setup is **best effort
    against other processes**, not a guarantee: POSIX drops a process's lock when any descriptor of the file is closed, and
    the lock can be lost in three ways that no per-class-loader state can close. (1) A contender that found the lock held by
    another process closes its channel, and if the other copy takes the lock in the nanoseconds before that close, the close
    drops it. (2) At the hand-over between the copies, the holder releases its lock and only then closes its channel; a
    parked channel of the other copy polls every 25 ms, and if it locks between those two calls the holder's close drops
    its lock. (3) When a copy is unloaded, its parked channel is closed by the JDK's cleaner at the next garbage collection,
    which drops a lock the other copy holds at that moment. In each case two processes can hold the lock at once and an
    update can be lost. **Load the library once per JVM, in a shared parent class loader, wherever several processes
    write one store.** With one copy of the library none of this applies.
  - A lock file the process cannot create or open (a read-only directory, a lock path that is a directory) fails the write
    with `OperationFailed`. Reads are unaffected.
  - **A file system that refuses file locks** (the lock file opens but `FileChannel.tryLock` itself throws an
    `IOException`: NFS without a reachable lock daemon, some FUSE mounts) does not stop the plain store: such a write is
    coordinated within this process only, as before 6.0.0, and is still atomic, so a second process can lose an update there.
    The encrypted stores never degrade this way: they answer `OperationFailed` with the `IOException` as the cause and touch
    nothing. There is no signal to the caller for the plain store's degrade; keep a store on a local volume.
  - **Plain reads never wait.** A read takes no lock at all: every write publishes by atomic rename and `clear()` by one
    unlink, and a read opens the file once, so it sees a whole old or a whole new file, and a read racing a `clear()` sees the
    file or no file. The read opens the file with share-delete semantics, so on Windows an open reader never makes another
    process's rename fail. Every write answers within the 10 s budget above.
  - Network file systems (NFS, SMB) may grant or refuse `tryLock` unreliably; keep a store on a local volume. Writes still
    never produce a torn file there, because every write is an atomic replace.
- **Native key-store calls (internal, for the desktop secure store).** The OS key stores are reached through one
  internal seam of 11 raw calls (macOS Security.framework: copy, add, delete, probe; Windows DPAPI: protect, unprotect;
  Linux libsecret: lookup, store, clear, default-collection-locked, backend-is-portal). It is `public` and
  `@ToolkitInternalApi` only because the optional `kmptoolkit-storage-jna` artifact implements it from another module;
  it can change in any release, and no FFM or JNA type appears in public API. There is deliberately no "update" call:
  a key item is deleted and created again, never updated.
  - **Binding selection** happens once per process, at the first secure call or probe, never when the plain store loads:
    `os.name` that is not macOS, Windows or Linux is `Unsupported(UNSUPPORTED_OS)`; a runtime of JDK 22 or newer uses the
    FFM binding (a JNA artifact on the classpath is ignored); JDK 21 uses the JNA binding if
    `io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding` loads and is otherwise
    `Unsupported(UNSUPPORTED_RUNTIME)`. The class is found by that one fixed name; there is no `ServiceLoader`, no system
    property and no environment variable that selects or replaces a binding. A binding that cannot load (an
    `IllegalCallerException` under `--illegal-native-access=deny`, an `UnsatisfiedLinkError`, a `SecurityException`, any
    other `LinkageError`) is `Unsupported(NATIVE_ACCESS_UNAVAILABLE)`; so is a `LinkageError` thrown around a later call
    (for example a `NoSuchMethodError` on a JDK 22-24 runtime); the same holds for a `LinkageError` or any exception thrown by the
    binding's per-OS accessor itself (`macOs()`, `windows()`, `linux()`), in a secure store and in the probe. The decision,
    including a failure, is kept for the process.
  - **One native thread, time limits.** Native calls run on one dedicated daemon thread, started by the first call. A call
    that cannot show a dialog has a 10 s limit and then answers `Unavailable`; a hung OS call keeps the thread busy, so later
    calls time out instead of starting more threads. A call that had not started when its limit passed, or whose caller was
    cancelled, is withdrawn and never runs, so a timed-out write is never applied later. A call that was already running cannot
    be stopped: its late result is discarded and the key bytes in it are wiped. A call that may show a dialog has no limit but
    stays cancellable. No store lock is held while a native call that can prompt is awaited. The native thread outlives what a
    call does to it: the interrupt status a call leaves behind is cleared after every call and does not end the loop, and a
    thread that cannot be created (a thread limit) answers that one call as failed and is created again by the next call.

- **Encrypted file format v1 (internal, for the desktop encrypted stores).** The encrypted desktop stores keep one binary
  file that is rewritten as a whole: magic `KTSS`, format version `1`, a kind byte (1 = an OS item holds the data key,
  2 = DPAPI-wrapped, 3 = passphrase, 4 = raw app key), a 2-byte header length and the header body, a 28-byte key-check
  (AES-256-GCM over an empty plaintext, authenticating every header byte before it), a 4-byte entry count, and the entries
  (name length, the name in UTF-8, a 12-byte nonce, a 4-byte length, the ciphertext with its 16-byte tag). **Entry names and
  the entry count are plaintext; only values are encrypted.** Every entry is authenticated together with the magic, the
  version, the kind and its own name, so an entry moved to another name, a downgraded version or a swapped kind fails its
  tag instead of reading as another value. Changing any header byte breaks the key-check (kinds 1 and 2) or the unwrapping of
  the data key (kinds 3 and 4, where it is indistinguishable from a wrong key). Whole-file rollback by someone who can write the
  file is possible and accepted; keep the directory owner-only.
  - Frozen by v1: the data key is 256 random bits from `SecureRandom`; every AES-GCM encryption (entry, key-check, key
    wrapping) uses a fresh 96-bit random nonce, never a counter or a derived value; a passphrase is Unicode NFC-normalised and then
    UTF-8 encoded before PBKDF2-HMAC-SHA256, so the same text typed composed or decomposed opens the same file; the
    iteration count accepted on open is 600,000 to 10,000,000 and the lower bound never rises within v1 (a raised default
    applies to newly written or re-keyed files only). A count outside the range is refused before any derivation, because
    `javax.crypto` PBKDF2 cannot be cancelled.
  - The parser checks every length against the bytes that remain before it allocates, rejects duplicate or non-UTF-8 entry
    names and trailing bytes, and never throws for any input. A file that fails to parse is not a store: it is reported as
    unreadable (`Undecryptable`), never as a wrong value.
### Desktop secure stores

Two encrypted stores exist on desktop, and both use the file format above. Neither is ever chosen for you.

| | `createSecureKeyValueStorage` | `createAppKeyEncryptedKeyValueStorage` |
|---|---|---|
| File | `<name>.kmptoolkit.securestorage.bin` | `<name>.kmptoolkit.appkeystorage.bin` |
| Lock file | the file name plus `.lock` | the file name plus `.lock` |
| Data key kept by | the OS key store (below) | wrapped by a key your app provides (passphrase or raw 256-bit key) |
| `protection` | `OS_KEY_STORE_USER_SCOPED`, known without I/O | `PASSPHRASE` or `APP_PROVIDED_KEY` |
| Native binding needed | yes (JDK 22+ built in, JDK 21 with `kmptoolkit-storage-jna`) | no, any OS and any JDK 21+ |

`StorageConfig.name` is required for both. A quarantined file is `<file>.undecryptable-<yyyyMMdd'T'HHmmss'Z'>` (UTC; `-1`, `-2`
after it when two quarantines land in one second); the library never deletes one. To start over after a forgotten passphrase
or a destroyed key, delete the store file (and, for an OS-key store, the key item).

#### The OS-key store

One 256-bit data key per store, kept in the running OS's key store; every value is AES-256-GCM under it. Nothing touches the
OS before the first call, and a problem is never answered with a weaker store.

| OS | Where the key is | Who else can read it | Prompts |
|---|---|---|---|
| macOS | a generic password in the **login (file) keychain**: service `<name>.kmptoolkit.securestorage`, account `dir-` plus 32 hex characters (the first 128 bits of SHA-256 over the canonical directory path as the volume spells it, never case-folded), label your `itemLabel` or the service | same-user code the user lets through the keychain's access prompt; every `java -jar` tool on one JDK shares one code identity | an access prompt when the code identity differs from the item's creator, the unlock prompt for a locked keychain |
| Windows | a DPAPI blob (current user, no UI) in the file header; entropy `kmptoolkit-storage:<service>`; there is no OS item, so nothing is ever orphaned | any process of the same user | never |
| Linux | a libsecret item with attributes `service` and `directory` (both are always passed, so only the own identity is touched), the key as base64, in the default collection (inside Flatpak with libsecret 0.20+ the portal backend, per app) | any same-user process while the collection is unlocked | the collection unlock, once per login session; KWallet may ask once |

The identity of a store is the **real path of its file as the volume spells it**: the canonical directory and the stored file
name (the service is that name without `.bin`), with no case folding (case folding is only for the in-process lock key, where
joining two names is harmless). Two directories that a volume keeps apart (`App` and `app` on Linux, `t_ı` and `t_i` on APFS)
are two stores with two keys, and two spellings of one name on a volume that folds case (macOS, Windows) are one file and
one identity: the stored spelling is used for the item, the header, the key cache, every read and every write, so a
`put` through the other spelling neither quarantines the file nor flips its name. On macOS the Unicode normalization form
(NFC or NFD) of the path and name is ignored (APFS and HFS+ ignore it too); elsewhere the stored form is kept. A file moved or
copied into another directory is a store whose key is lost (below), never one that opens another store's key. The identity
is derived again under the lock in every round and is never remembered: a file system that cannot be asked where the file
really is (an `IOException` or `SecurityException` while resolving the path) answers `OperationFailed` for that one call,
touches nothing and makes no OS call, and the next call asks again; there is no fallback path and so never a second identity
for the same store. **Residual:** on a Linux directory that is case-insensitive (ext4 casefold, vfat) the library lists the
directory to find the stored spelling, but a volume that hides it from the JDK would still give two spellings two identities.

**The data key is cached** in process memory per identity, shared by every instance over the same file. A cached key is never
trusted alone: every operation reads the file header under the lock and verifies its key-check with the cached key; a mismatch
drops (and wipes) the entry and fetches the key again.

**"The key is lost"** means exactly one of: a definitive not-found at the store's own identity (macOS -25300 from an interactive
call; Linux: no matching item in any collection, locked or not), a key-check that fails with the fetched key, a header whose
fingerprint or OS tag names another identity, or (Windows) DPAPI failing to unwrap while a one-byte round trip works. A locked,
dismissed, denied, hung or missing key store is **never** "lost": it answers `AccessDenied`, `Unavailable` or `Unsupported`, never
touches the file and never leads to quarantine or key creation. On macOS the verification of what `kSecUseAuthenticationUIFail`
returns on the file keychain has not been run, so the conservative rule applies: a -25300 from a call made with
`allowUserInteraction = false` is `Unavailable`, not "lost", **for a file with entries**; for a file with **zero entries** the
same answer is read as "lost, zero entries", because nothing can be lost there (see the crash state below and "Open
verification items").

What each call does (decided under the locks; every answer after the key has been verified first):

| Situation | `get` | `remove` | `put` | `clear()` | `ensureKey()` |
|---|---|---|---|---|---|
| no file | `Success(null)` | `Success` | create, then write | `Success`, nothing created | create |
| key fetched and verified | normal | normal | normal | empties the file, header kept | `Success` |
| key lost, entries present | an entry that exists: `Undecryptable(null)`; one that does not: `Success(null)` | drops that entry, copying the header and the other entries byte for byte | quarantines the file, creates a new key | empties the file, header kept | `Undecryptable(null)` |
| key lost, zero entries | `Success(null)` | `Success` | replaces the empty file with a new key | `Success` | replaces the empty file with a new key |
| one entry's tag fails | `Undecryptable(key)` for that key | drops it | overwrites it | as healthy | `Success` |
| OS locked, denied, hung, missing | the mapped error; the file is never touched | same | same | `Success` (no OS call) | same as `get` |
| file does not parse (bad magic, truncated, wrong kind, absurd lengths, ...) | `Undecryptable(null)` | `Undecryptable(null)`, untouched | quarantines it, creates a new key | quarantines it, creates nothing | `Undecryptable(null)` |
| file of a **newer** format version | `Undecryptable(null)` | `Undecryptable(null)` | `Undecryptable(null)`, untouched | quarantines it | `Undecryptable(null)` |

`clear()` never calls the OS: it works while the keychain is locked, which is what makes `remove` and `clear()` the way out of
an `Undecryptable` store.

**Creating a key** happens inside both locks and in this order: the OS part that can fail without side effects first (Linux:
the session check, the collection-locked check when no dialog is allowed; Windows: the DPAPI call), then the empty sealed file
(`ExistingFile.Fail`, so another process that wins is detected and read), then the OS item (delete the item at the own
identity, then add: never an update), then the cache. A failure at any step undoes the previous ones, a quarantined file is
renamed back, and the store is byte-identical to before the call; cancellation does the same. A crash between the file and the
item leaves an empty sealed file with no item, which reads as `Success(null)` and is repaired by the next `put` or `ensureKey()`,
including a store with `allowUserInteraction = false` on macOS (the delete and add of the repair carry no-UI, so a locked
keychain or a refused delete still fails closed with the file restored). If a rollback that follows a quarantine cannot
move the quarantined file back, the call answers `OperationFailed` whose cause names the quarantine file (the user's bytes are
intact there) and the store reads as empty, a crash state, until the next `put`; it is never reported as the original error.
**A creation
can hold the store's locks while an OS dialog is on screen** (the unlock dialog of macOS with `allowUserInteraction = true`):
other callers answer `Unavailable` after their 10 s wait. With `allowUserInteraction = false` the macOS add and delete carry
`kSecUseAuthenticationUIFail`, and Linux first reads the collection's `Locked` flag and answers `Unavailable` without a prompt.
Reading and verifying an existing key never holds a lock while a call that can prompt is awaited.

**Per-OS errors.**

| Situation | Answer |
|---|---|
| macOS -25308 interaction not allowed, -25299 duplicate on add, -25291 not available | `Unavailable` |
| macOS -128 user cancelled, -25293 auth failed (also a refused delete of a planted item: the file stays byte-identical) | `AccessDenied(platformCode)` |
| macOS -25307 no default keychain, -25294 no such keychain | `Unsupported(NO_KEY_STORE)` |
| macOS -34018 missing entitlement | `Unsupported(MISSING_ENTITLEMENT)` |
| Windows: unprotect fails and the one-byte round trip fails with 1312 | `Unsupported(NO_USER_SESSION)` |
| Windows: unprotect fails and the round trip fails otherwise | `Unavailable` |
| Windows: unprotect fails and the round trip works | key lost (`Undecryptable(null)` for an entry) |
| Linux: neither `DBUS_SESSION_BUS_ADDRESS` nor `$XDG_RUNTIME_DIR/bus` | `Unsupported(NO_USER_SESSION)`, before any native call |
| Linux: no libsecret, libsecret older than 0.19 (no `secret_password_search_sync`), service unknown, D-Bus access denied | `Unsupported(NO_KEY_STORE)` |
| Linux: activation failed, no reply, timed out, collection locked without UI | `Unavailable` |
| Linux: prompt dismissed | `AccessDenied`; a match that stays locked is `AccessDenied` after a dismissed prompt and `Unavailable` without UI |
| a native call past its limit (10 s without UI) | `Unavailable` |
| the binding cannot run (`LinkageError`, `IllegalCallerException`, `SecurityException`) | `Unsupported(NATIVE_ACCESS_UNAVAILABLE)` |
| anything else | `OperationFailed(operation, key, platformCode)`; failures of `ensureKey()` carry `ENSURE_KEY` and `key = null` |

**Locks.** Reads and writes of an encrypted store both take the in-process lock and the cross-process lock (a read must not see
a key being created), under one 10 s budget; a call that cannot get them answers `Unavailable`. A file system that refuses file
locks (see Concurrency above) makes every call answer `OperationFailed` and touch nothing: unlike the plain store, an encrypted
store never degrades. A store file is at most 256 MiB: a `put` whose encoded file would be larger is refused with
`InvalidValue(key)` before anything is written or created (a larger file would be read back as unparsable and quarantined by the
next `put`); `changeKey` on a file that the new header would push over the limit answers `OperationFailed(CHANGE_KEY)`. The one thing a call does not wait for under the locks is anything slow: the native fetch of an existing
key runs with no lock held, and the next round re-reads the file and uses the key only if the header and key-check are the
ones it was fetched for (at most four such rounds, then `Unavailable`).

**Open verification items (macOS, not yet run on a real keychain).** Whether `kSecUseAuthenticationUIFail` applies to
`SecItemAdd` and `SecItemDelete` on the file keychain; whether a locked keychain and an item of another code identity both
return -25308 (never -25300); whether deleting the previous unsigned build's item prompts. Until then a non-interactive -25300 is
`Unavailable` (for a zero-entry file the repair of the crash state goes ahead without a dialog, as above), and the documented behaviour of `allowUserInteraction = false` is "a needed dialog answers `Unavailable`" for the
unlock dialog and, if the verification fails, only for it.

#### `probeDesktopKeyStore`

`probeDesktopKeyStore(options)` answers which key store a secure store would use now: `MACOS_FILE_KEYCHAIN`, `WINDOWS_DPAPI`,
`LINUX_SECRET_SERVICE` or `LINUX_SECRET_PORTAL` (only when libsecret actually selected the portal), or the typed reason there is
none. It never prompts and never reads, creates or deletes a key (macOS: an attribute-only query with the UI suppressed; Windows:
one throwaway byte protected and unprotected; Linux: session check, then the default collection's `Locked` flag and the backend
check), and every native call is limited to 10 s, so it answers even when the OS hangs. A locked macOS keychain or Linux
collection is a success when `allowUserInteraction` is `true` and `Unavailable` when it is `false`. A failure outside the typed
answers is `OperationFailed(ENSURE_KEY, key = null)`.

#### The app-key store

No OS call, no binding, no native thread: it works on every OS and on JDK 21 without the JNA artifact. The data key is wrapped by
a key derived from what your app provides.

- **Open order.** The header's iteration count is checked first (outside 600,000 to 10,000,000, including 2^32-1, is
  `Undecryptable(null)` at once: no derivation, no provider call). Then the provider is called, the key-encryption key is derived
  (a passphrase: PBKDF2-HMAC-SHA256 over the NFC text with the header's salt and iteration count; a raw key: the key itself) and the
  data key is unwrapped. An unwrap failure is `AccessDenied` (the wrong key, never `Undecryptable`); an unwrap that works with a
  failing key-check is `Undecryptable(null)` (corruption).
- **Provider outcomes.** A returned wrong key, an empty passphrase, a passphrase with an **unpaired UTF-16 surrogate** (it has no
  UTF-8 form, and the JDK would silently turn it into `?`, so two different passphrases would open one store) or a raw key that is
  not 32 bytes is `AccessDenied`; the cause never contains the secret. A provider that throws is `Unavailable(cause)`;
  `CancellationException` propagates. The provider is called at most once per operation, and every array it returned is wiped
  as soon as it has been used, so it must return a fresh array on every call. A passphrase in which every character is below U+0300
  (ASCII, Latin-1 and Latin Extended: the usual typed text, which is already NFC) is only copied into an array that is wiped; a
  passphrase with any character from U+0300 up (Cyrillic, Arabic, CJK, a combining mark, an emoji) passes through immutable
  `String`s made by the JDK's normalizer (a copy of the input and the result), which cannot be wiped and stay on the heap
  until they are collected (the JDK has no normalizer that works on a caller-owned buffer). A re-key with the key object the instance already uses asks its provider once.
- **Every operation verifies the key first**, a `get` of an absent key and `clear()` included, on an existing file: a wrong
  passphrase never reads as "absent", and a wrong key never changes a byte. With no file there is nothing to verify: `get`,
  `remove` and `clear()` answer `Success` without calling the provider.
- **The provider and PBKDF2 run with no lock held.** A slow provider (a password dialog) or a 10 000 000-iteration derivation
  never makes another caller wait out its 10 s budget; the round that needs the key says so, the locks are released, and the
  next round re-reads the file and uses the derived key only if the header and key-check are the ones it was derived for.
- **The key cache is per instance and bound to the exact header bytes** (kind, salt, iterations, wrapped key). Another instance
  over the same file never shares it: an instance built with the wrong key gets `AccessDenied` while another instance is open and
  unlocked, and an instance that holds the old key gets `AccessDenied` after another instance's `changeKey`.
- **`changeKey(newKey)`** verifies the current key, asks the new provider (validated like any provider; the new key being unusable is
  `AccessDenied` and changes nothing), then re-encrypts every value under a fresh data key wrapped by the new key with a new salt and
  key-check, and replaces the file atomically; a crash leaves the old file or the new one, both complete. The instance is bound to the
  new key afterwards (its `protection` follows the new key's type). A value that cannot be decrypted with the current key makes
  `changeKey` answer `Undecryptable(key)` and change nothing. Copies of the file taken before the change stay readable with the old
  key (they are not re-encrypted). On an absent store `changeKey` only binds the instance to the new key. A store of the other key type
  than the instance's key (a passphrase file opened with a raw key, or the other way round) is a key that cannot open it:
  `AccessDenied` on every operation, nothing moved or changed.
- **A forgotten passphrase** has no reset in the API: delete the store's files and start over.

## Threading

Every call is a `suspend` function that runs its platform work on `Dispatchers.IO`, so it is safe to
call from the main thread and from any number of coroutines at once:

- **Android.** `SharedPreferences` reads block on a file's first load, and `commit()` writes to disk;
  both run on the I/O dispatcher. Key resolution for the secure store runs under the process-wide
  lock described above. The resolved key is cached per alias for the whole process, not per store
  instance, so every store over one alias uses the same key handle. A key the platform rejects
  (`InvalidKeyException`) is dropped from that cache and looked up again once — if another instance
  replaced an unrecoverable key meanwhile, the next use picks the new one up instead of failing until
  the process restarts.
  A value that ends in a line break is stored by the plain store with one U+E000 appended, because
  the framework's XML writer indents after a trailing line break; the store removes it on read. Code
  reading the preferences file directly sees it. A value an older version stored that itself ended in `\n` followed
  by U+E000 reads one marker shorter; the two cannot be told apart.
- **iOS.** `NSUserDefaults` and Keychain calls go through `cfprefsd` and `securityd`, both IPC. Two
  writers adding the same Keychain item at once — two coroutines, or an app extension sharing the
  access group — make the loser's `SecItemAdd` fail with `errSecDuplicateItem`; the store then
  updates the item instead, so neither write reports a failure.
- **Desktop.** Instances over the same file share a coroutine lock (`KeyedMutex` of `kmptoolkit-files`), so their read-modify-write
  cycles over the file never interleave (a read does not take it); a write then takes the cross-process file lock (see "Concurrency" above), the only
  thing it ever waits for while holding the in-process lock, within the same 10 s budget as the wait for the in-process lock itself, and cancellable. Reads take neither lock. The lock is keyed by the path in Unicode NFC with the full
  case mapping (`ß`, `ẞ` and `ss`, the Greek sigma forms, `Settings` and `settings` share it), so two
  spellings of one name share it on a volume that folds case (macOS, Windows); on a case-sensitive volume they are two files behind one lock. A file that
  cannot be parsed, including one that is not valid UTF-8, fails every operation on it, writes included, and is never replaced; `clear()` is
  the way out, and a `DeviceIdProvider` over such a file yields a new id per call until then.

A secure-store call is still a cipher round trip on Android and an IPC hop to `securityd` on iOS:
fine at startup, wrong in a loop or per list item.
