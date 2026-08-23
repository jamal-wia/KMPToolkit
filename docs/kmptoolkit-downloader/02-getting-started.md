# Getting started

Ten minutes to a download that survives being backgrounded.

## 1. Add the dependency

```kotlin
// build.gradle.kts (shared module)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-downloader")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-downloader-testing")
        }
    }
}
```

With the BOM applied, no version is needed — see the root [`README`](../../README.md).

## 2. Declare your first unit

The library ships no units — that is the host's catalogue. The simplest real one, four overrides
and nothing else:

```kotlin
data object LanguageModel : DownloadUnit {
    override val id: String = "LANGUAGE_MODEL"
    override val apiPath: String = "models/language-model-v1"
    override val relativePath: String = "models/language-model-v1.bin"
    override val group: ResourceGroup = MyResourceGroup.LANGUAGE_MODEL
}
```

- **`id` is durable.** It is written to disk (the stall counter, the temp file name) and read back
  across launches — renaming it orphans an in-flight transfer.
- **`apiPath`** is opaque to the library; your `DownloadUrlResolver` turns it into a URL.
- **`relativePath`** is relative to the storage's own base directory — you never construct an
  absolute path yourself.

## 3. Declare a group

A group is what one notification is titled after and what aggregate progress is scaled across, even
across several units:

```kotlin
enum class MyResourceGroup(override val units: List<DownloadUnit>) : ResourceGroup {
    LANGUAGE_MODEL(units = listOf(LanguageModel)),
}
```

An enum implementing `ResourceGroup` is the natural shape: a new group cannot compile without
deciding what units it owns.

## 4. Implement `BackgroundResourceDownloader`

**This is the one thing the module cannot do for you** — the platform machinery that moves bytes
and keeps moving them after the app is backgrounded or killed. See
[`07-background-downloader.md`](07-background-downloader.md) for a full walkthrough; the shape is
four methods (`enqueueDownload`, `observeProgress`, `isDownloadInProgress`, `cancelDownload`).

## 5. Build the downloader

```kotlin
val storage: DownloaderStorage = createDownloaderStorage(context) // androidMain
// val storage: DownloaderStorage = createDownloaderStorage()     // iosMain

val downloader: Downloader = createDownloader(
    storage = storage,
    backgroundDownloader = myBackgroundResourceDownloader,
    groups = MyResourceGroup.entries,
    stateStore = myDownloadStateStore, // see spi/DownloadStateStore, and 05-platform-notes.md
)
```

`groups` is every `ResourceGroup` you will ever ask about — a group missing from this list fails
loudly the first time it is used rather than half-working. There is no separate `start()`: the
returned `Downloader` is ready immediately.

## 6. Ask for a resource

```kotlin
downloader.ensureAvailable(MyResourceGroup.LANGUAGE_MODEL)
val modelPath: String = downloader.pathOf(LanguageModel)
```

- Returns at once if the group is already available.
- Concurrent callers for the same group share one download under a per-group mutex.
- Throws `DownloadCancelledException` on cancel, `DownloadFailedException` — carrying a classified
  `DownloadError` — once the engine stops retrying. The same outcome is always also published to
  the group's state flow, so a caller may equally ignore the exception and render from state.

## 7. Show progress

```kotlin
downloader.downloadState(MyResourceGroup.LANGUAGE_MODEL)
    .collect { state: GroupDownloadState ->
        when (state) {
            is GroupDownloadState.Downloading -> showProgress(state.progress)
            is GroupDownloadState.Error -> showError(state.error)
            GroupDownloadState.Completed, GroupDownloadState.Idle -> Unit
        }
    }
```

Progress never goes backwards, and `Completed` is preceded by a settle delay at `1f` so the UI
renders a full bar before transitioning.

## 8. Optional — a runtime catalogue

For a catalogue whose members are only known at runtime (a manifest fetched from a server), use the
per-unit surface instead — several can be in flight at once, each with its own progress and cancel:

```kotlin
val unit = remember(entryId) { MyRuntimeUnit(entryId) }
val state: UnitDownloadState by downloader.unitDownloadStateFlow(unit)
    .collectAsStateWithLifecycle(initialValue = UnitDownloadState.Idle)

scope.launch {
    try {
        downloader.ensureAvailable(unit)
    } catch (_: DownloadCancelledException) { /* the row's state already shows the reset */ }
    catch (_: DownloadFailedException) { /* the row renders Error from the flow */ }
}
```

See [`03-guide.md`](03-guide.md) for how the per-unit path differs from the group path, why they
are not one generalized over the other, and what `ResourceFormat` buys you.
