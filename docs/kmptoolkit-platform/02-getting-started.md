# kmptoolkit-platform — Getting started

## Add the dependency

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-platform:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-platform-testing:<version>")
        }
    }
}
```

It brings `kotlinx-coroutines-core` and `kmptoolkit-logging` with it, and nothing else. No Compose,
no Media3, no DI framework.

Before shipping, read [`05-platform-notes.md`](05-platform-notes.md) — one seam needs a permission
your app must declare, and three behave differently than you might expect without one.

## Construct the seams once, at the platform entry point

Shared code takes the interfaces as constructor parameters and never names a factory. Only your
`Application` / app delegate does.

### Android

```kotlin
class MyApp : Application() {

    lateinit var platform: PlatformSeams
        private set

    override fun onCreate() {
        super.onCreate()

        // First, so a crash during the rest of startup is still recorded.
        val crashLog: CrashLogStore = createCrashLogStore(this)
        installCrashHandler(crashLog)

        val activities: ActivityAccess = createActivityTracker(this)

        platform = PlatformSeams(
            connectivity = createConnectivityObserver(this),
            device = createDeviceInfo(this),
            reducedMotion = createReducedMotionProbe(this),
            urls = createUrlOpener(this),
            wakeLock = createScreenWakeLock(activities),
            crashLog = crashLog,
        )

        reportPreviousCrashes(crashLog.readAndClear())
    }
}
```

`PlatformSeams` is your own class — this module ships no aggregate on purpose, so you take only the
seams you use.

The Android file picker needs one more piece, because the activity-result API must be registered on
an `Activity`; see [`03-guide.md`](03-guide.md#the-android-file-picker-host).

### iOS

```kotlin
fun createPlatformSeams(): PlatformSeams {
    val crashLog: CrashLogStore = createCrashLogStore()
    installCrashHandler(crashLog)

    return PlatformSeams(
        connectivity = createConnectivityObserver(),
        device = createDeviceInfo(),
        reducedMotion = createReducedMotionProbe(),
        urls = createUrlOpener(),
        wakeLock = createScreenWakeLock(),
        filePicker = createFilePicker(),
        crashLog = crashLog,
    )
}
```

Call it from your app delegate and hold the result for the process lifetime.

## Use them from shared code

```kotlin
class ProfilePresenter(
    private val connectivity: ConnectivityObserver,
    private val device: DeviceInfo,
    private val urls: UrlOpener,
) {
    val isOffline: Flow<Boolean> =
        connectivity.status.map { it == ConnectivityStatus.OFFLINE }

    val supportFooter: String = "${device.osName} ${device.osVersion} · ${device.model}"

    fun onPrivacyClicked() {
        when (urls.open("https://example.com/privacy")) {
            UrlOpenResult.OPENED -> Unit
            else -> showCouldNotOpenLink()
        }
    }
}
```

## Two things to remember

**Create observers once.** `ConnectivityObserver` registers a system callback in its constructor.
One per process, held for the process lifetime — one per screen means one system callback per
screen. Call `close()` if you ever tear the graph down.

**Release the wake lock yourself.** Nothing does it for you when a screen goes away:

```kotlin
override fun onDispose() {
    wakeLock.setKeepScreenOn(false)
}
```
