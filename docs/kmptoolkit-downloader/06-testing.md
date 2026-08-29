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
| `FakeDownloaderStorage` | You are testing storage-facing code without real files |
| `TestUnit` / `TestGroup` | You need a catalogue that exists only for the test |
| `RecordingNotifier` | You want to assert what would have been shown, and in what order |
| `InMemoryStateStore` | You need a `DownloadStateStore` that does not touch real storage |
| `TestDownloadDispatchers` | You want the real engine on virtual time, so a stall timeout costs no real time |

There is no contract-style suite here, unlike `kmptoolkit-uploader-testing`'s `UploaderStoreContract`.
The donor code this module was ported from has no runnable check of `DownloaderStorage`'s or
`BackgroundResourceDownloader`'s invariants either — only ad-hoc fakes — so none was invented for
this port. If you write a custom `DownloaderStorage`, `03-guide.md`'s identity rule (key everything
by `unit.id` / `unit.relativePath`, never by object identity) is the one invariant worth a
deliberate test of your own.

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
