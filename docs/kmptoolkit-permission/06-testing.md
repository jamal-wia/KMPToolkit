# kmptoolkit-permission — Testing

Neither real handler can run in a unit test: one needs an activity with a system dialog on screen,
the other needs a device. Every interesting path through your own code — permanently denied, denied
once, revoked while you were backgrounded — is therefore unreachable without a double. That double
is `RecordingPermissionHandler`, in `kmptoolkit-permission-testing`.

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
[`../01-architecture.md`](../01-architecture.md#test-fixtures-ship-as-separate-testing-artifacts).

## `RecordingPermissionHandler`

```kotlin
public class RecordingPermissionHandler(
    public var defaultStatus: PermissionStatus = PermissionStatus.NotDetermined,
) : PermissionHandler
```

Two knobs and two recordings:

| Member | What it does |
|---|---|
| `defaultStatus` | what `check` answers for an unscripted permission — a fresh install by default |
| `setStatus(permission, status)` | pins the status for one permission |
| `scriptRequest(permission, outcome)` | what a `request` resolves to, standing in for the user's tap |
| `checks` / `requests` | every permission passed to `check` / `request`, oldest first, as a snapshot |
| `openAppSettingsCount` | how many settings trips your code asked for |
| `settingsAvailable` | what `openAppSettings()` reports; set `false` for the device that cannot open it |
| `clearRecordings()` | drops the recordings, keeps the scripted statuses |

It models the OS in two ways that matter, both asserted by its own tests:

- **A request changes the status.** The `check` that follows a `request` agrees with what the request
  returned, exactly as the OS behaves after the user answers a dialog.
- **A request the OS would not show is not shown here either.** With the status already `Granted` or
  `PermanentlyDenied`, `request` returns that status and ignores the script.

A fixture that got either of those wrong would let a whole suite pass while the real handlers
behaved differently.

**Not thread-safe**, matching both real handlers' own contract. Drive it from one coroutine.

## The four scenarios worth covering

### Granted — the happy path

```kotlin
@Test
fun `tapping record starts recording when the microphone is granted`() = runTest {
    val handler = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)
    val presenter = RecorderPresenter(handler)

    presenter.onRecordTapped()

    assertTrue(presenter.isRecording)
}
```

### Denied once — your rationale must appear

```kotlin
@Test
fun `a first refusal asks the screen for a rationale`() = runTest {
    val handler = RecordingPermissionHandler()
    handler.setStatus(Permission.MICROPHONE, PermissionStatus.Denied(shouldShowRationale = true))
    val presenter = RecorderPresenter(handler)

    presenter.onRecordTapped()

    assertEquals(PermissionFlowState.AwaitingRationale, presenter.permissionState.value)
    assertTrue(handler.requests.isEmpty(), "no dialog before the user has seen the rationale")
}
```

### Permanently denied — your settings prompt must appear, and the button must not be dead

```kotlin
@Test
fun `a permanently denied microphone offers system settings instead of another prompt`() = runTest {
    val handler = RecordingPermissionHandler()
    handler.setStatus(Permission.MICROPHONE, PermissionStatus.PermanentlyDenied)
    val presenter = RecorderPresenter(handler)

    presenter.onRecordTapped()
    presenter.onOpenSettingsTapped()

    assertEquals(1, handler.openAppSettingsCount)
    assertTrue(handler.requests.isEmpty())
}
```

### Revoked while backgrounded — the one everybody forgets

```kotlin
@Test
fun `a permission revoked while the app was away disables the feature on resume`() = runTest {
    val handler = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)
    val presenter = RecorderPresenter(handler)
    presenter.onRecordTapped()

    handler.setStatus(Permission.MICROPHONE, PermissionStatus.NotDetermined)
    presenter.onScreenResumed()

    assertFalse(presenter.isRecordButtonEnabled)
}
```

The same shape covers the settings trip: set the status to `Granted` between `openSettings()` and
`refresh()`, and assert your screen enables itself.

## Assert states, not strings

Assert on `PermissionFlowState` and on what your presenter exposes, never on copy. The module ships
no text, so a test that asserts wording is testing your own strings — which belongs in your
localization tests, not here.

## Testing an Android handler directly

You almost never need to, but if you are wrapping `createPermissionHandler`, the module's own
`androidUnitTest` suite shows the shape: Robolectric plus a `PermissionRequestHost` stub whose
answer — and whose ability to answer at all — the test dictates. The cases worth copying are the two
failure modes a real device produces and a happy-path test never reaches: a host that cannot launch,
and a host that throws. Neither may be recorded as a refusal, or a launcher bug turns a permission
permanently denied.

## What the module tests itself

For reference when judging whether your own coverage is enough — 97 tests:

- **50 in `commonTest`**, run on both Android and iOS: every row of the flow's transition table, the
  no-op behavior of every method outside its state, the `Requesting` lock, the revoked-while-away
  paths, and the key derivation.
- **29 in `androidUnitTest`** under Robolectric: the status logic against a real `PackageManager`,
  the asked flag's lifecycle, the notifications branch on both sides of API 33, the platform string
  each permission maps to, the settings intent, and the assertion that the merged library manifest
  contributes no permission at all.
- **18 in `kmptoolkit-permission-testing`**: the fixture's own contract, including the two places it
  claims to model the OS.
