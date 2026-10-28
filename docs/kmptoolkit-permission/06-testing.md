# kmptoolkit-permission — Testing

Neither real controller can run in a unit test: one needs an activity with a system dialog on screen,
the other needs a device. Every interesting path through your own code — permanently denied, denied
once, limited, revoked while you were backgrounded — is therefore unreachable without a double. That
double is `FakePermissionController`, in `kmptoolkit-permission-testing`.

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-permission-testing")
        }
    }
}
```

It ships as a separate artifact so it never reaches your app's runtime classpath — see
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate--testing-artifacts).

## `FakePermissionController`

```kotlin
public class FakePermissionController(
    defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionController
```

| Member | What it does |
|---|---|
| `defaultStatus` | what `check` answers for an unscripted permission — a fresh install by default |
| `setStatus(permission, status)` | pins the status for one permission — also how you model a change made in settings |
| `scriptRequest(permission, outcome)` | what a `request` resolves to, standing in for the user's answer; unscripted, a request that shows UI resolves to `Granted` |
| `checks` / `requests` | every permission passed to `check` / the single `request`, oldest first, as a snapshot |
| `batchRequests` | every collection passed to the batch `request`, as a list |
| `openedSettings` | every permission passed to `openSettings` |
| `settingsAvailable` | what `openSettings` reports; set `false` for the device that cannot open it |
| `holdRequests` | while `true`, requests suspend as if the system UI were on screen |
| `clearRecordings()` | drops the recordings, keeps the scripted statuses and outcomes |

It works with every `Permission` — `CommonPermission` in shared tests, `AndroidPermission` and
`IosPermission` in platform tests — and models the OS in the ways that matter, each asserted by its
own tests:

- **A request changes the status.** The `check` that follows a `request` agrees with what the request
  returned.
- **A request the OS would not show is not shown here either.** From `Granted`, `PermanentlyDenied`,
  `Restricted` or `Unavailable`, `request` returns that status and ignores the script. From `Limited`,
  a scripted outcome applies (the platforms that do show something there) and an unscripted request
  stays `Limited`.
- **Requests are serialized**, as the real controllers' are. With `holdRequests`, a test can look at
  your code while the system UI is "up", cancel the request then, or check that a second request
  waits.
- **`observe` follows every change**: `setStatus`, `request` and a changed `defaultStatus` re-emit,
  and only a *different* status is emitted. Collect with an `UnconfinedTestDispatcher` so each change
  is delivered before your next line runs.

**Not thread-safe**, deliberately. Drive it from one test dispatcher, as `runTest` does.

## The scenarios worth covering

### Granted — the happy path

```kotlin
@Test
fun `tapping record starts recording when the microphone is granted`() = runTest {
    val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    val presenter = RecorderPresenter(controller)

    presenter.onRecordTapped()

    assertTrue(presenter.isRecording)
}
```

### Denied once — your rationale must appear

```kotlin
@Test
fun `a first refusal asks the screen for a rationale`() = runTest {
    val controller = FakePermissionController()
    controller.setStatus(CommonPermission.Microphone, PermissionStatus.Denied(explainFirst = true))
    val presenter = RecorderPresenter(controller)

    presenter.onRecordTapped()

    assertEquals(PermissionFlowState.AwaitingRationale, presenter.permissionState.value)
    assertTrue(controller.requests.isEmpty(), "no dialog before the user has seen the rationale")
}
```

### Permanently denied — your settings prompt must appear, and the button must not be dead

```kotlin
@Test
fun `a permanently denied microphone offers system settings instead of another prompt`() = runTest {
    val controller = FakePermissionController()
    controller.setStatus(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)
    val presenter = RecorderPresenter(controller)

    presenter.onRecordTapped()
    presenter.onOpenSettingsTapped()

    assertEquals(listOf<Permission>(CommonPermission.Microphone), controller.openedSettings)
    assertTrue(controller.requests.isEmpty())
}
```

### Limited and unavailable — neither is a refusal

```kotlin
@Test
fun `approximate location still starts navigation`() = runTest {
    val controller = FakePermissionController()
    controller.setStatus(CommonPermission.Location, PermissionStatus.Limited)

    assertTrue(NavigationPresenter(controller).onStartTapped())
}

@Test
fun `an unavailable permission hides the feature instead of offering settings`() = runTest {
    val controller = FakePermissionController(defaultStatus = PermissionStatus.Unavailable)
    val presenter = RecorderPresenter(controller)

    presenter.onRecordTapped()

    assertFalse(presenter.isSettingsPromptVisible)
    assertTrue(controller.openedSettings.isEmpty())
}
```

### Revoked while backgrounded — the one everybody forgets

```kotlin
@Test
fun `a permission revoked while the app was away disables the feature`() = runTest(UnconfinedTestDispatcher()) {
    val controller = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    val presenter = RecorderPresenter(controller)   // observes the microphone

    controller.setStatus(CommonPermission.Microphone, PermissionStatus.NotDetermined)

    assertFalse(presenter.isRecordButtonEnabled.value)
}
```

The same shape covers the settings trip: set the status to `Granted` after `openSettings()` and
assert your screen enables itself.

### A batch

```kotlin
@Test
fun `the video call asks for camera and microphone together`() = runTest {
    val controller = FakePermissionController()
    controller.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)
    controller.scriptRequest(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)

    val started: Boolean = CallPresenter(controller).onCallTapped()

    assertFalse(started)
    assertEquals(listOf(listOf<Permission>(CommonPermission.Camera, CommonPermission.Microphone)), controller.batchRequests)
}
```

## Assert states, not strings

Assert on `PermissionFlowState`, `PermissionStatus` and what your presenter exposes, never on copy.
The module ships no text.

## Testing an Android controller directly

You almost never need to. The module's own `androidUnitTest` suite shows the shape if you wrap one:
Robolectric, a `PermissionRequestHost` stub whose answer — and whose ability to answer at all — the
test dictates, and the internal factory seams for the API level and the manifest. The failure modes
worth copying are the ones a real device produces and a happy-path test never reaches: a host that
cannot launch, a host that throws, a dialog interrupted without an answer, and a dismissal (denied,
no rationale, no refusal remembered) — none of which may be recorded as a refusal.

## What the module tests itself

- **`commonTest`**, run on both Android and iOS: every row of the flow's transition table, the
  no-op behaviour of every method outside its state, the `Requesting` lock, the revoked-while-away
  paths, the status properties, and the stability and uniqueness of every common id.
- **`androidUnitTest`** under Robolectric: the status logic against a real `PackageManager`, the
  stored flags' lifecycle and the 2.x keys, every common and Android-only entry on both sides of the
  API levels where it changes, the manifest filter and the policy restriction, a foreign type,
  serialization of concurrent requests, cancellation mid-request, the shared batch dialog, every
  special access on its API levels with the request that waits for the user to come back, roles
  (unavailable, held, refused once and twice, granted from settings), device admins, accessibility
  services, `Runtime(name)` by protection level, the settings intents, the default host against a
  real `ComponentActivity` (including an activity recreated while the dialog is up), and the
  assertion that the merged library manifest contributes no permission.
- **`iosTest`**: the mapping from every authorization status each entry reads to a
  `PermissionStatus`, the missing-purpose-string path for every entry, the notification options, and
  a foreign type.
- **`kmptoolkit-permission-testing`**: the fixture's own contract, including each place it claims to
  model the OS.
