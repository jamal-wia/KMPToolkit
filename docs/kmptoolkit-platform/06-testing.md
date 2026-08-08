# kmptoolkit-platform — Testing

Most of these seams are untestable by a consumer without a fixture: you cannot go offline in a unit
test, cannot make a device be a tablet, and cannot dismiss a file chooser that never opens.
`kmptoolkit-platform-testing` ships one double per seam.

## The fixtures

They live in a separate artifact so nothing test-shaped reaches your app's runtime classpath
([`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts)):

```kotlin
dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-platform")
    testImplementation("io.github.jamal-wia:kmptoolkit-platform-testing")
}
```

All of them work in `commonTest`, so one test covers both platforms.

| Fixture | Drives | Records |
| --- | --- | --- |
| `FakeConnectivityObserver` | `emit(status)` | `closeCount`, `isClosed` |
| `FakeDeviceInfo` | constructor values, `country` | — |
| `FakeReducedMotionProbe` | `enabled` | `readCount` |
| `RecordingUrlOpener` | `result` | `openedUrls`, `lastUrl` |
| `RecordingScreenWakeLock` | `result` | `requests`, `isHeld` |
| `FakeFilePicker` | `result` | `requestedMimeTypes`, `pickCount` |
| `InMemoryCrashLogStore` | `seed(record)` | `stored`, `readCount` |

## Connectivity transitions

The reason the fake exists is the sequence, not the state:

```kotlin
@Test
fun `sync pauses when the device goes offline and resumes when it returns`() = runTest {
    val connectivity = FakeConnectivityObserver(ConnectivityStatus.ONLINE)
    val sync = SyncEngine(connectivity)

    connectivity.emit(ConnectivityStatus.OFFLINE)
    assertTrue(sync.isPaused)

    connectivity.emit(ConnectivityStatus.ONLINE)
    assertFalse(sync.isPaused)
}
```

Start from `UNKNOWN` — the default — when you want to assert the cold-start behavior, which is
also what a real observer reports for the first few milliseconds and what it reports *forever* on
an Android app missing `ACCESS_NETWORK_STATE`:

```kotlin
@Test
fun `an unknown status is not treated as offline`() {
    val presenter = OfflineBannerPresenter(FakeConnectivityObserver())

    assertFalse(presenter.showsBanner)
}
```

## Proving the wake lock is released

The bug worth catching is the one where a screen leaves the user's display on:

```kotlin
@Test
fun `disposing the recorder releases the screen`() {
    val wakeLock = RecordingScreenWakeLock()
    val recorder = RecordingComponent(wakeLock)

    recorder.onStart()
    recorder.onDestroy()

    assertFalse(wakeLock.isHeld)
    assertEquals(listOf(true, false), wakeLock.requests)
}
```

## The picker outcomes nobody reproduces by hand

```kotlin
@Test
fun `a cancelled picker leaves the upload untouched`() = runTest {
    val uploader = Uploader(FakeFilePicker(PickResult.Cancelled))

    uploader.attach()

    assertEquals(UploadState.Idle, uploader.state)
}

@Test
fun `an oversized file is reported rather than uploaded`() = runTest {
    val picker = FakeFilePicker(PickResult.TooLarge(sizeBytes = 60_000_000, maxBytes = 25_000_000))
    val uploader = Uploader(picker)

    uploader.attach()

    assertIs<UploadState.TooLarge>(uploader.state)
}
```

## Crash reporting on the next launch

```kotlin
@Test
fun `a crash from the previous run is reported once and then cleared`() {
    val store = InMemoryCrashLogStore(listOf(CrashRecord(1, "main", "boom", "trace")))
    val reporter = StartupCrashReporter(store)

    reporter.reportPreviousCrashes()
    reporter.reportPreviousCrashes()

    assertEquals(1, reporter.reportedCount)  // not twice, on every launch, forever
}
```

## How this module tests itself

Worth knowing if you are changing it.

- **`commonTest`** covers the parts that are pure: the connectivity state machine
  (`NetworkStateTracker`), the crash-record codec, absolute-URL validation, region-code and
  device-model normalization, and the config `require`s. These run on both JVM and
  `iosSimulatorArm64`.
- **`androidUnitTest`** (Robolectric, via the `kmptoolkit.androidtest` convention plugin) covers
  the activity tracker, the wake lock against a real `Window`, the crash file store against a real
  filesystem, the URL opener against a real `Intent` dispatch, the file picker against a real
  `ContentResolver`, device info across screen-size qualifiers, and the reduced-motion probe
  against `Settings.Global`. It also asserts, against a real `PackageManager`, that the library
  manifest contributes **no** permission.
- **`iosTest`** covers device info, the crash file store and the connectivity observer's lifecycle
  on a simulator.
- `src/androidUnitTest/resources/robolectric.properties` pins `sdk=35`: Robolectric 4.16 ships no
  image for this repository's `compileSdk`, and without the pin every Robolectric test fails to
  initialize.

### Why the activity-retention test does not call `System.gc()`

The requirement is that a released `Activity` is not retained, and it is tested — but through the
weak reference rather than through the collector. `System.gc()` is a hint the JVM may ignore, and
Robolectric's own machinery keeps activities reachable for the length of a test, so a
GC-and-assert-null test fails at random for reasons unrelated to the code.

Instead the test clears the tracker's `WeakReference` by hand and asserts that `withActivity` stops
answering. That proves the same property — the only path from the tracker to the activity is a weak
one — and it proves it deterministically. Two further tests assert that the reference field itself
is `null` after a pause and after a destroy, so the tracker holds nothing at all once an activity
goes away.
