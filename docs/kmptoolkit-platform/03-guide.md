# kmptoolkit-platform — Guide

Each seam in practice, and the decisions worth making deliberately.

## Reacting to connectivity

`status` is a `StateFlow`, so a late collector gets the current value immediately:

```kotlin
class OfflineBannerPresenter(connectivity: ConnectivityObserver) {

    // UNKNOWN is grouped with ONLINE on purpose: it means "not reported yet", or "the app has no
    // ACCESS_NETWORK_STATE permission". Showing an offline banner in either case would be wrong.
    val showBanner: Flow<Boolean> =
        connectivity.status.map { it == ConnectivityStatus.OFFLINE }.distinctUntilChanged()
}
```

Do not gate a request on it. Reachability is a hint the OS gives you about interfaces, and a
request can fail on a validated network or succeed while the OS is still catching up. Use it to
explain a failure, not to prevent an attempt.

**One observer per process.** The constructor registers a system callback; a per-screen observer
means one system callback per screen. Call `close()` only when you are tearing the whole graph
down.

## Device facts and form factor

```kotlin
val diagnostics: String = buildString {
    append(device.osName).append(' ').append(device.osVersion)
    append(" · ").append(device.model)
    device.currentCountry()?.let { country -> append(" · ").append(country) }
}
```

`currentCountry()` is a function because it is read live — the user can change the region in
Settings mid-session, and a value cached at startup would be silently wrong from then on. It
returns `null` for a device with no region set, and for a locale whose region is not an ISO 3166-1
alpha-2 code (`es-419` is a real locale; `419` is not a country).

`formFactor` answers a hardware question. It is right for an analytics dimension or a default
camera choice, and wrong for layout — a phone in landscape, a tablet in a narrow multi-window pane
and a foldable mid-fold all defeat the assumption that form factor implies width. Use window size
classes for layout.

## Respecting reduced motion

```kotlin
val transition: Transition =
    if (reducedMotion.isReducedMotionEnabled()) Transition.CrossFade else Transition.SharedAxis
```

Swap the *style* of the transition rather than removing it: the movement is what tells the user
what just happened, and a screen that changes with no transition at all is harder to follow, not
easier.

Call it when you are about to animate. It reads the setting each time, so a user who changes it
mid-session gets what they asked for on the next screen. There is no observe API — see
[`05-platform-notes.md`](05-platform-notes.md#reduced-motion).

## Opening links

```kotlin
when (urls.open(link)) {
    UrlOpenResult.OPENED -> Unit
    UrlOpenResult.INVALID_URL -> log.w { "the server sent a link that is not absolute" }
    UrlOpenResult.NO_HANDLER, UrlOpenResult.FAILED -> showCopyLinkFallback(link)
}
```

`INVALID_URL` is worth separating from the other two: it means the *string* was wrong — empty,
relative, or without a scheme — so it points at your code or your backend, not at the user's
device. Nothing is guessed on your behalf: `example.com` is rejected rather than turned into
`http://example.com`, because guessing a scheme is how a link meant to be secure ends up not being.

## Picking a file

```kotlin
when (val result = filePicker.pick(listOf("application/pdf", "image/png"))) {
    is PickResult.Picked -> upload(result.file.name, result.file.bytes)
    PickResult.Cancelled -> Unit
    is PickResult.TooLarge -> showTooLarge(result.sizeBytes, result.maxBytes)
    PickResult.Unavailable -> showTryAgainLater()
    is PickResult.Failed -> showCouldNotRead()
}
```

`Cancelled` is the most common outcome in the wild — handle it as "do nothing", never as an error.

The `mimeTypeHint` on a `PickedFile` is what the OS *claimed*, derived from an extension or a
provider column, both of which the user controls. Sniff the bytes before anything security- or
correctness-relevant depends on the type.

The cap in `FilePickerConfig` is enforced before a byte is read whenever the platform reports a
size, and re-checked afterwards for providers that report none or lie. There is no streaming
variant: `pick` returns a `ByteArray`, and the cap is what keeps that affordable.

### The Android file-picker host

Android's activity-result API must be registered on an `Activity` before it resumes, and dies with
it. Rather than hide an `Activity` inside a library-owned object, this module asks you for a
`FilePickerHost` — the registration stays in your activity, where the framework already manages its
lifetime:

```kotlin
class MainActivity : ComponentActivity(), FilePickerHost {

    private var pending: ((Uri?) -> Unit)? = null

    private val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending?.invoke(uri)
        pending = null
    }

    override fun launch(mimeTypes: Array<String>, onResult: (Uri?) -> Unit): Boolean {
        pending = onResult
        return runCatching { launcher.launch(mimeTypes) }.isSuccess
    }
}
```

Then, wherever you assemble dependencies for that screen:

```kotlin
val picker: FilePicker = createFilePicker(context = this, host = this)
```

Hold the picker no longer than you hold the activity. Returning `false` from `launch` — and not
calling `onResult` — is what produces `PickResult.Unavailable`.

iOS needs none of this: `createFilePicker()` presents from the foreground scene's key window by
itself.

## Keeping the screen awake

```kotlin
class RecordingComponent(private val wakeLock: ScreenWakeLock) {

    fun onStart() {
        wakeLock.setKeepScreenOn(true)
    }

    // Not optional. Nothing releases the request for you — not the scope that made it, not the
    // screen going away.
    fun onDestroy() {
        wakeLock.setKeepScreenOn(false)
    }
}
```

`NO_ACTIVE_WINDOW` on Android is not a failure: the request is remembered and applied to the next
activity that resumes. That is also what makes it survive a rotation, which destroys the window
holding the flag and creates a new one from platform defaults.

## Recording and reporting crashes

Install the handler as early as you can, and read the previous run's records right after:

```kotlin
val crashLog: CrashLogStore = createCrashLogStore(context)
installCrashHandler(crashLog)

crashLog.readAndClear().forEach { record ->
    logger.e { "previous session crashed on ${record.threadName}: ${record.message}\n${record.stackTrace}" }
}
```

`readAndClear` is one operation on purpose. Two would invite a caller to read, fail to clear, and
report the same crash on every launch forever.

What it will and will not catch is worth being precise about — see
[`01-overview.md`](01-overview.md#what-this-is-not) and
[`05-platform-notes.md`](05-platform-notes.md#crash-handling). Treat it as a complement to a real
crash reporter, not a replacement.

## Reaching the Android activity without leaking it

`ActivityAccess` is Android-only and exists for the few APIs that need a real `Activity`. There is
no getter — you can only run a block while an activity is resumed:

```kotlin
val dismissed: Boolean = activities.withActivity { activity ->
    activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    true
} ?: false
```

`null` means there is no resumed activity — the app is backgrounded, or between two activities
during a configuration change. It is a normal answer.

To reapply state to each new window, subscribe:

```kotlin
val subscription = activities.addOnActivityResumedListener { activity ->
    activity.window.addFlags(FLAG_SOMETHING)
}
```

The listener fires immediately if an activity is already resumed, and again for every activity that
replaces it. **Never store the activity it is handed** — the listener is held until you cancel the
subscription, so anything it captures lives that long. Use the activity inside the callback and let
it go.

## Wiring it into a DI framework

There is no Koin module here ([why](../01-architecture.md#no-dependency-injection-framework)).
Wrapping the factories is a few lines:

```kotlin
val platformModule = module {
    single<ActivityAccess> { createActivityTracker(androidApplication()) }
    single<ConnectivityObserver> { createConnectivityObserver(androidContext()) }
    single<DeviceInfo> { createDeviceInfo(androidContext()) }
    single<ScreenWakeLock> { createScreenWakeLock(get()) }
}
```
