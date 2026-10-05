# Testing

`kmptoolkit-downloader-testing` ships the doubles. Add it as a test dependency:

```kotlin
commonTest.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-downloader-testing")
}
```

| Fixture | Use it when |
|---|---|
| `FakeDownloader` | You are testing code that only *asks* for a resource or observes its state |
| `FakeDownloaderStorage` | You are testing storage-facing code without real files; `tempFileStates` sets what a transfer left behind; deliberately stricter than the real storages about `beginTempFile`, requires a `Complete` file to commit, records `beganWith`, and has a `failIntegrityFor` knob |
| `TestUnit` / `TestGroup` | You need a catalogue that exists only for the test |
| `RecordingNotifier` | You want to assert what would have been shown, and in what order |
| `InMemoryStateStore` | You need a `DownloadStateStore` that does not touch real storage |
| `TestDownloadDispatchers` | You want the real engine on virtual time, so a stall timeout costs no real time |

There is no contract-style suite here, unlike `kmptoolkit-uploader-testing`'s `UploaderStoreContract`.
The donor code this module was ported from has no runnable check of `DownloaderStorage`'s or
`BackgroundResourceDownloader`'s invariants either — only ad-hoc fakes — so none was invented for
this port. If you write a custom `DownloaderStorage`, two invariants are worth a deliberate test of
your own: `03-guide.md`'s identity rule (key everything by `unit.id` / `unit.relativePath`, never by
object identity), and the temp-file rule — a `Partial` file is never committed, only
`markTempFileComplete` makes one `Complete`, and a check that fails at commit deletes the file and
throws `ResourceIntegrityException`. The hash-expectation rules (`beginTempFile` replaces the
transfer, the record survives a new instance over the same directory, an unreadable record is an
integrity failure, the record never outlives the temp file) are written down in
`DownloaderStorage`'s KDoc and exercised by this module's own `DownloaderStorageContractTest`,
which is a good template.

## `FakeDownloaderStorage` and the hash protocol

The fake is deliberately **stricter** than the real storages, so a downloader test that forgets the
hash step fails instead of passing. The real storages accept a `markTempFileComplete` with no
`beginTempFile` (a partial from before records existed, or a resume of a transfer begun earlier)
and then check only `DownloadUnit.sha256`; the fake does not.

- **Strict.** `markTempFileComplete(unit)` throws `IllegalStateException` — its message names the
  forgotten `beginTempFile` — unless the unit's id is in `begunIds`. `beginTempFile` adds it;
  `deleteTempFile` and `commitResource` remove it. To model a transfer begun earlier, such as a
  correct `206` resume of a partial from before the fake existed, seed `begunIds` together with
  `tempFileStates` instead of calling `beginTempFile`; `beganWith` then stays empty.
- **`commitResource` mirrors the real contract.** It throws `IllegalStateException` unless the
  unit's state is `Complete`, and on success the temp file is consumed: the state goes back to
  `None`. Seed `tempFileStates[unit.id] = TempFileState.Complete` before committing.
- **`beganWith: MutableList<Pair<String, Sha256?>>`** records every `beginTempFile` as (unit id,
  expected hash), in order. Assert on it to check that your downloader passes the hash of the
  response that began the transfer, passes `null` when the backend stated none, and does not call it
  on a `206` resume.
- **Contradiction rule.** `beginTempFile` throws `IllegalArgumentException` when `unit.sha256` and
  the expected hash are both set and differ, recording nothing.
- **`failIntegrityFor: MutableSet<String>`.** Add a unit id and `commitResource` throws
  `ResourceIntegrityException` for it, which drives the engine's retry and `Corrupted` paths without
  hashing real bytes. The failed check consumes the temp file, so a new `beginTempFile` is needed
  before the next mark. The knob fails every commit of that id until the id is removed from the set.

The fake does not hash anything, so it cannot tell whether a downloader passed the right hash —
only that it began the transfer before marking it complete, which `beganWith` lets you assert on.
Whether bytes match a hash is the real storages' job, covered by the contract test they share
(`DownloaderStorageContractTest`, run on both platforms).

## Testing code that only asks

Most view-model or repository tests do not need a real engine at all:

```kotlin
@Test
fun `tapping download asks the downloader for the model`() = runTest {
    val downloader = FakeDownloader()
    val viewModel = ModelDownloadViewModel(downloader)

    viewModel.onDownloadTapped()

    assertEquals(listOf(LanguageModel), downloader.ensuredUnits)
}
```

`FakeDownloader.emit(unit, state)` and `.setGroupState(group, state)` drive it the way a real
engine would, so a UI test can assert against `Downloading(0.4f)` without a byte ever moving:

```kotlin
@Test
fun `progress renders as a percentage`() = runTest {
    val downloader = FakeDownloader()
    val viewModel = ModelDownloadViewModel(downloader)

    downloader.emit(LanguageModel, UnitDownloadState.Downloading(0.4f))

    assertEquals("40%", viewModel.state.value.progressLabel)
}
```

## Testing with the real engine

For anything about retry, stall handling, or notification ordering, run the real engine directly —
it is `internal`, so this only works from `kmptoolkit-downloader`'s own module; a consumer testing
against the public API uses `FakeDownloader` instead, or its own `BackgroundResourceDownloader`
fake over the real `createDownloader(...)`:

```kotlin
@Test
fun `a failed download is retried before giving up`() = runTest {
    val storage = FakeDownloaderStorage()
    val notifier = RecordingNotifier()
    val group = TestGroup("bundle").apply { units = listOf(TestUnit("asset", this)) }

    val downloader: Downloader = createDownloader(
        storage = storage,
        backgroundDownloader = myFlakyThenSucceedingDownloader,
        groups = listOf(group),
        stateStore = InMemoryStateStore(),
        notifier = notifier,
        dispatchers = TestDownloadDispatchers(this),
    )

    downloader.ensureAvailable(group)

    assertEquals(GroupDownloadState.Completed, downloader.downloadState(group).value)
}
```

`TestDownloadDispatchers` is what makes the five-minute stall timeout a non-issue in a test — it
runs the engine's coroutines on the test scheduler, so `runTest` fast-forwards through the `delay`
instead of waiting for it.

## Testing a custom `BackgroundResourceDownloader`

There is no shipped fake for this port because there is no shipped implementation of it either —
see [`07-background-downloader.md`](07-background-downloader.md). Write a small in-memory one for
your tests the way the engine's own test suite does: a class that emits `BackgroundDownloadEvent`s
from a `Flow` you control, so a test can drive `Progress` → `FileReady` / `Error` / `Cancelled`
without a real transfer.
