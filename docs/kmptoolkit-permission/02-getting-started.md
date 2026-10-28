# kmptoolkit-permission — Getting started

Five minutes to a microphone permission your shared code can ask for.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-permission")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-permission-testing")
        }
    }
}
```

The Android side additionally pulls in `kmptoolkit-storage`, for the two flags per permission that
Android's own API cannot report, `kmptoolkit-activity`, for the resumed activity the dialog is shown
from, and `androidx.activity`. All of them arrive transitively.

## 2. Declare the permission — in **your** manifest and **your** `Info.plist`

This library declares nothing on your behalf. Without this step the permission reads `Unavailable`,
a request shows nothing, and the controller logs which declaration is missing.

```xml
<!-- androidMain/AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```xml
<!-- iosApp/Info.plist -->
<key>NSMicrophoneUsageDescription</key>
<string>We record your voice so you can send an audio message.</string>
```

The full table of what each entry needs is in [`05-platform-notes.md`](05-platform-notes.md).

## 3. Android — build the controller in `Application.onCreate`

```kotlin
class MyApplication : Application() {

    lateinit var permissions: PermissionController
        private set

    override fun onCreate() {
        super.onCreate()
        permissions = createPermissionController(
            context = this,
            storage = createKeyValueStorage(this),
        )
    }
}
```

That is the whole Android setup. The controller shows its dialogs from whichever `ComponentActivity`
is resumed — every `AppCompatActivity` and every Compose activity is one — through that activity's
`ActivityResultRegistry`, so there is no launcher to register and no host to implement. A dialog that
is on screen while the activity is recreated — a rotation — still delivers its answer.

Build it in `Application.onCreate` for the same reason as any activity tracker: it has to be
listening before your first activity resumes. Hold it wherever you already hold your dependencies —
there is no DI framework here and no global; see
[`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework).

Already have an `ActivityAccess` from `kmptoolkit-activity`, your own `registerForActivityResult`
plumbing, or a kiosk launcher? Every collaborator is a parameter with a default — see
[`04-api-reference.md`](04-api-reference.md#android-factory).

## 4. iOS — one line

```kotlin
val permissions: PermissionController = createPermissionController()
```

No context, no activity, no storage: iOS reports "not determined" itself, so there is nothing for
the library to remember. Pass `IosPermissionOptions` to change what a notification request asks for.

## 5. Use it from shared code

```kotlin
class RecorderPresenter(controller: PermissionController) {

    private val microphone = PermissionRequestFlow(CommonPermission.Microphone, controller)

    val permissionState: StateFlow<PermissionFlowState> = microphone.state

    suspend fun onRecordTapped() {
        if (microphone.start() == PermissionFlowState.Granted) startRecording()
        // Anything else is already reflected in permissionState; render it.
    }

    suspend fun onRationaleAccepted() {
        if (microphone.rationaleAcknowledged() == PermissionFlowState.Granted) startRecording()
    }

    fun onRationaleDismissed() {
        microphone.rationaleDismissed()
    }

    fun onOpenSettingsTapped() {
        microphone.openSettings()
    }

    /** Call from your screen's resume: the user may have changed everything while away. */
    suspend fun onScreenResumed() {
        if (microphone.refresh() == PermissionFlowState.Granted) enableTheRecordButton()
    }
}
```

Your UI observes `permissionState` and renders its own dialog for `AwaitingRationale` and
`AwaitingSettings`. The library never supplies the words.

A screen that only needs to show whether a feature is available, without asking, collects the
controller directly:

```kotlin
val cameraUsable: StateFlow<Boolean> = controller.observe(CommonPermission.Camera)
    .map { status -> status.isUsable }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)
```

## 6. A special access, on Android

Exact alarms, drawing over other apps, battery-optimization exemption — an Android special access is
asked for through the same controller. The request opens its Settings screen and returns when the
user is back in the app, with the status re-read:

```kotlin
// androidMain
when (permissions.request(AndroidPermission.ExactAlarm)) {
    PermissionStatus.Granted -> scheduleExact()
    else -> scheduleInexact()
}
```

See [`03-guide.md`](03-guide.md#special-access-roles-and-device-admins).

## What you did **not** have to write

- Remembering that the dialog was already shown once, so a permanent denial can be told apart from
  a first run.
- The API 33 branch for notifications, the API 33 and 34 branches for photos, and the API 31 branch
  for Bluetooth.
- An `ActivityResultLauncher` per permission, registered before resume and re-attached after a
  rotation.
- The knowledge that iOS never shows a second dialog, so its denial is permanent immediately — and
  that it terminates an app requesting without a purpose string.
- A Settings intent with the right action, data URI and task flags for each special access, and a
  fallback for when a device lacks the app's own page.

## Read next

- [`03-guide.md`](03-guide.md) — the state machine in full, batches, lifecycle, the mistakes to avoid
- [`05-platform-notes.md`](05-platform-notes.md) — the manifest / `Info.plist` table
- [`06-testing.md`](06-testing.md) — testing your own screen against `FakePermissionController`
