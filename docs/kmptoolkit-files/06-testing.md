# kmptoolkit-files — Testing

Testing the code *around* the file system — a store, a repository, a cache cleaner — with a fake that
fails and crashes on demand, and checking your own `FileAccess` decorators against the same contract
the real implementations keep.

## The fixture module

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-files:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-files-testing:<version>")
        }
    }
}
```

It ships as a separate artifact for the reason in
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts) and
depends on `kmptoolkit-files` with `api`, so one line gets you both. It is published for Android, iOS
and `jvm`.

## `FakeFileAccess`

An in-memory `FileAccess` that runs the **real engine**: the atomic replace, the no-replace move, the
cross-volume copy, recursive delete, sweep and free-space algorithms are the production code, and only
the file system underneath is simulated. It therefore cannot drift from the device behaviour the way a
hand-written fake does. POSIX path syntax on every host — build its paths as strings (`"/data/x"`), not
with `Paths` or `java.io.File`. Thread-safe.

```kotlin
val files = FakeFileAccess()
files.createFile("/data/profile/profile.json", """{"name":"Ada"}""".encodeToByteArray())

val store = ProfileStore(files, path = "/data/profile/profile.json")
assertEquals("Ada", store.loadName())
```

It models inodes (a rename carries content and sync state; hard links share them), symbolic links,
per-file synced content and per-directory synced entries — enough to simulate a power loss. Names over
255 UTF-8 bytes are `NameTooLong` in every lookup, as on ext4. `FakeFileAccess(caseSensitive = false)` models a
case-insensitive volume: `/d/A` and `/d/a` are one entry and the spelling of creation is listed. Names are
folded with one fixed rule (NFD, lowercase, uppercase, lowercase, NFC) that does not depend on the host, so a
name with a backslash stays a different name from its prefix on a Windows test host too. The rule folds slightly
more than APFS or NTFS (for example dotless `ı` and `I` are one name), never less.

### Seeding

| Call | Effect |
|---|---|
| `createFile(path, bytes)` | creates the file and every missing parent, replaces a file already there; mtime = `nowMillis`; durable |
| `createDirectory(path)` | the directory and its parents; durable |
| `createSymbolicLink(path, target)` | a link (target absolute, or relative to the link's directory; need not exist) |
| `setLastModified(path, millis)` | the entry's own mtime (a link, not its target) |
| `markAllDurable()` | makes the current state the durable baseline; call it after seeding through `FileAccess` calls |

Seeded entries "count as durable": a simulated power loss keeps them.

A seeded path segment over 255 UTF-8 bytes throws `IllegalArgumentException` (the entry would be unreachable, every
lookup of it being `NameTooLong`), and so does `setLastModified` of a path that does not exist.

### The clock, space and links

| Property | Meaning |
|---|---|
| `nowMillis` | the file system's clock, starts at `0`; mtimes and `sweep`'s "now" come from it. Move it to age files |
| `freeSpaceBytes` | `null` = unlimited (default); otherwise a write that does not fit fails with `NoSpace` and changes nothing, a write that fits uses space up, removing the last name of a file gives it back |
| `hardLinksSupported` | `false` makes every link `Unsupported` (FAT, exFAT) |
| `beforeOperation` | a hook that runs before every low-level operation, on the calling thread, before any fault applies |

```kotlin
@Test
fun `old leftovers are swept and fresh ones are kept`() {
    val files = FakeFileAccess()
    files.nowMillis = 10_000
    files.createFile("/d/.kmptoolkit-1.tmp"); files.setLastModified("/d/.kmptoolkit-1.tmp", 1_000)
    files.createFile("/d/.kmptoolkit-2.tmp") // mtime = 10_000

    val removed = files.sweep("/d", olderThanMillis = 3_000) { FileNames.isTempName(it) }

    assertEquals(FileResult.Success(1), removed)
}
```

### Fault injection

```kotlin
files.failNext(FakeOperation.Write, FileError.NoSpace("/d"))
files.failNext(FakeOperation.Rename, FileError.AccessDenied("/d"), count = 2, path = { it.endsWith(".tmp") })
```

`failNext(operation, error, count = 1, path = { true })`: the next `count` low-level `operation`s whose
path satisfies the filter (the filter runs outside the fake's lock, so it may block or call back into the fake) fail with `error` and have **no effect**. The path is the one of
`FakeOperationRecord.path`: for `Rename` the source, for `Link` the new name, for `Write`/`Sync`/`Close`
the path the file was created at. The operations are `Metadata`, `List`, `CreateDirectory`,
`CreateNew`, `OpenForRead`, `Write`, `Sync`, `Close`, `Rename`, `Link`, `Remove`, `SyncDirectory`,
`UsableSpace`. `CrossDevice` is produced only by injecting it — `failNext(Rename, CrossDevice(..))` —
which sends `move` down its copy path.

An exception thrown by `beforeOperation` is reported like any file-system failure (`FileError.Io` with
the exception as `cause`); an `Error` propagates.

`operations` is the log of every low-level operation attempted, in order, including failed ones:

```kotlin
files.writeAtomically("/d/f", bytes)
assertEquals(
    listOf(CreateNew, Write, Sync, Close, Rename),
    files.operations.map { it.operation }.filter { it != FakeOperation.Metadata },
)
```

### Crash and power-loss simulation

```kotlin
files.crashAfter(FakeOperation.Rename)
assertFailsWith<SimulatedCrash> { files.writeAtomically("/d/f", bytes) }

val after = files.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
assertEquals("old", after.readBytes("/d/f", 1024).getOrNull()?.decodeToString())
```

`crashAfter(operation, occurrence = 1)`: after the `occurrence`-th **successful** `operation` counted
from this call, the fake crashes — its effect is kept, the state is frozen, `SimulatedCrash` (an
`Error`, so the engine cannot swallow it) is thrown from that call and from every later one, so the
interrupted operation's cleanup never runs, as in a dead process. `restartAfterCrash` returns a **new**
fake holding what survived:

| `CrashKind` | What survives |
|---|---|
| `ProcessDeath` | everything, synced or not |
| `PowerLoss` | every file truncated to its content at the last `Sync` (a file never synced is **empty**); with `revertUnsyncedDirectoryEntries = true` also every directory restored to its entries at the last `SyncDirectory` (or seeding), so a new or renamed name that was never made durable is gone |

`restartAfterCrash` without a crash throws `IllegalStateException`. `nowMillis`, `freeSpaceBytes` and
`hardLinksSupported` carry over; faults, the crash and the log do not. `entries()` and `operations`
keep working after a crash:

```kotlin
files.entries() // Map<String, FakeEntry>: "/d/f" -> FakeEntry.File(bytes), "/d" -> Directory, links -> SymbolicLink(target)
```

What the engine guarantees at each crash point, and which `Durability` it takes, is pinned by this
module's own suite: a crash after `CreateNew`, `Write`, `Sync` or `Close` leaves the old content and
exactly one temporary file; after `Rename` the new content; a `Buffered` write followed by power loss
leaves an **empty** target (the reason `Synced` is the default); a `Synced` write followed by power loss
and reverted entries leaves the old content whole; `SyncedWithDirectory` survives even that, **including the first record in a
directory the same call created** (`createParentDirectories`): the engine then syncs the parent of every
level it created, deepest first, before the final sync of the target's parent (one extra directory sync per
created level, none when the directory already existed, and none for `Synced` or `Buffered`). An existing directory is assumed durable; see `createDirectories(durable = true)`, which the fake models the same way: a directory made without `durable` is lost by power loss with reverted entries, and the record written into it with it, while `durable = true` and `AppDirectories` keep both.

### What the fake does not model

- Permissions and ownership (`AccessDenied` only when injected), file locks, and mtime granularity.
- Windows semantics: the fake is POSIX in syntax and behaviour on every host, so the Windows retry
  and gate are not exercised by it (they are tested against the real JVM implementation on Windows).
- Another process: there is one in-memory file system per fake.
- A real disk's size: free space is a number you set.

## `FileAccessContract` — for decorators and custom implementations

Every observable contract of `FileAccess` as a plain check. Use it when you wrap `FileAccess` (logging,
retries, a quota, a read-only view) or implement it:

```kotlin
class MyDecoratorContractTest {
    @Test
    fun `the decorator keeps the FileAccess contract`() {
        val contract = FileAccessContract {
            val root = newEmptyTemporaryDirectory()      // absolute, empty
            FileAccessFixture(
                files = LoggingFileAccess(createFileAccess()),
                root = root,
                separator = '/',                          // the character that joins a directory and a name
                createSymbolicLink = { path, target -> createLinkSomehow(path, target) }, // null: link checks skipped
                setLastModified = { path, millis -> setMtime(path, millis) },             // null: sweep age checks skipped
                nowMillis = { System.currentTimeMillis() },                               // the clock of the file system under test
                cleanup = { deleteTree(root) },
            )
        }
        contract.verifyAll()
    }
}
```

`verifyAll()` runs every check, each with its own fixture, and stops at the first violation:
`FileAccessContractViolation` (an `AssertionError`) with a message that starts with the name of the
check. Each check is also a public function (`writeAtomicallyReplacesTheWholeContent()`,
`deleteRecursivelyDoesNotFollowSymbolicLinks()`, …) so a runner can report them one by one. The
checks cover metadata kinds and links, `list`, `createDirectories` (including concurrent creators, and `durable = true` giving the same results),
`readBytes` limits, `InvalidPath` for a relative, empty or NUL-containing path on every function (so a decorator
that resolves relative paths against a base fails), every `writeAtomically` outcome (replace, empty content, missing parent, directory
target, `Fail`, links, temp directory, no leftover, concurrent writers), `move`, `delete`,
`deleteRecursively` (including concurrent deleters and cancellation), `treeSize`, `sweep` (age, cancellation, old symbolic links),
`freeSpaceBytes`, `exists` and `withIo`. The two `withIo` checks verify the extension, which no implementation can
override, not the implementation under test.

Two optional fixture members drive the age rule of `sweep`:

- `setLastModified: ((path, millis) -> Boolean)?` sets the modification time of an entry. When it is
  `null` the two age checks (`sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest`,
  `sweepWithZeroAgeDeletesAnOldEntry`) are **skipped** — they return without asserting, so a green run
  without the hook says nothing about the age rule. When the hook returns `false` the check fails with
  a violation saying the fixture could not set the time. Every real implementation can provide it
  (`File.setLastModified`, `NSFileManager.setAttributes`, `FakeFileAccess.setLastModified`).
- `nowMillis: () -> Long` is the clock of the file system under test in epoch milliseconds (default:
  the wall clock). A `FakeFileAccess` fixture passes `{ fake.nowMillis }`.

The age checks date entries with margins of ten minutes around the age (a real clock moves), not at the
exact boundary: with an age of one hour an entry ten minutes beyond it is deleted and counted, one ten
minutes inside it, one dated in the future, a name the matcher refuses and a directory are kept. The
exact boundary (strict `>`) is pinned on the fake's own clock and by this module's scripted tests.

Not in the contract, because the interface cannot observe it: crash and power-loss behaviour (use
`FakeFileAccess`), exact modification times and the exact free space (a real clock and a real volume
cannot be pinned).

The contract uses plain Kotlin only (no test framework), so the module has no test-library
dependency; run it from whichever framework you use.

## Testing the module itself

```bash
./gradlew :kmptoolkit-files:build :kmptoolkit-files-testing:build checkKotlinAbi
./gradlew :kmptoolkit-files:testAndroidHostTest :kmptoolkit-files:iosSimulatorArm64Test :kmptoolkit-files:jvmTest
./gradlew :kmptoolkit-files-testing:testAndroidHostTest :kmptoolkit-files-testing:iosSimulatorArm64Test :kmptoolkit-files-testing:jvmTest
```

`kmptoolkit-files-testing`'s own suite runs `FileAccessContract.verifyAll()` against:

- `FakeFileAccess()`, `FakeFileAccess(caseSensitive = false)` and a fake with a custom temp prefix
  (`commonTest`, every host);
- the real implementation on the desktop JVM (`jvmTest`, also on the Windows PC), the real Android
  factory under Robolectric on the default SDK, on API 26 and on SDK 24 (`androidHostTest`; the SDK 24 run is "API 24 host runtime (NIO selected)"), and the real iOS
  implementation on the simulator (`iosTest`);

and pins every crash, power-loss and fault-injection case above (`FakeCrashAndFaultTest`) plus the
contract's ability to fail (a decorator that breaks the sort order, one that leaks a temporary file).
`kmptoolkit-files`' own suite holds the pure rules (paths, names, keys, `KeyedMutex` storms), the
real-file behaviour on every platform, the engine's crash behaviour over real files, and the Windows
gate. The `android.system.Os` ops of API 24–25 devices are not covered by any host test (a host runtime at
SDK 24 selects the NIO ops and runs the contract as "API 24 host runtime (NIO selected)"); see
[`05-platform-notes.md`](05-platform-notes.md#android-api-2425-the-legacy-path).
