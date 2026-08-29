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

The Android side additionally pulls in `kmptoolkit-storage`, for the one flag per permission that
Android's own API cannot report. It arrives transitively; you only name it if you construct it
yourself, which the next step does.

## 2. Declare the permission — in **your** manifest and **your** `Info.plist`

This library declares nothing on your behalf. Without this step the Android dialog never appears and
iOS terminates your app the moment you request.

```xml
<!-- androidMain/AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

```xml
<!-- iosApp/Info.plist -->
<key>NSMicrophoneUsageDescription</key>
<string>We record your voice so you can send an audio message.</string>
```

The full table of what each `Permission` needs is in
[`05-platform-notes.md`](05-platform-notes.md).

## 3. Android — implement the host and build the handler

The system dialog is launched by an `ActivityResultLauncher`, which belongs to an activity and must
be registered before it resumes. That is your ten lines; everything after the user taps is the
library's.

```kotlin
class MainActivity : ComponentActivity(), PermissionRequestHost {

    private var pending: ((Boolean) -> Unit)? = null

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pending?.invoke(granted)
        pending = null
    }

    override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean {
        pending = onResult
        return runCatching { launcher.launch(androidPermission) }.isSuccess
    }
}
```

```kotlin
class MyApplication : Application() {

    lateinit var storage: KeyValueStorage
        private set

    override fun onCreate() {
        super.onCreate()
        storage = createKeyValueStorage(this)
    }
}

// In the activity, once — it is cheap and holds nothing that needs releasing:
val handler: PermissionHandler = createPermissionHandler(
    context = this,
    host = this,
    storage = app.storage,
)
```

There is no DI framework here and no global. Hold the handler wherever you already hold your
dependencies — see [`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework).

## 4. iOS — one line

```kotlin
val handler: PermissionHandler = createPermissionHandler()
```

No context, no activity, no storage: iOS reports "not determined" itself, so there is nothing for
the library to remember.

## 5. Use it from shared code

```kotlin
class RecorderPresenter(handler: PermissionHandler) {

    private val microphone = PermissionRequestFlow(Permission.MICROPHONE, handler)

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

## What you did **not** have to write

- Remembering that the dialog was already shown once, so a permanent denial can be told apart from
  a first run.
- The API 33 branch for notifications.
- The knowledge that iOS never shows a second dialog, so its denial is permanent immediately.
- A settings intent with the right action, data URI and task flags, and a fallback for when there is
  no resumed activity to launch it from.

## Read next

- [`03-guide.md`](03-guide.md) — the state machine in full, lifecycle, and the mistakes to avoid
- [`05-platform-notes.md`](05-platform-notes.md) — the manifest/`Info.plist` table
- [`06-testing.md`](06-testing.md) — testing your own screen against `RecordingPermissionHandler`
