# kmptoolkit-platform — API reference

Every public declaration. Package root: `io.github.jamal_wia.kmptoolkit.platform`.

## `…platform.connectivity`

```kotlin
interface ConnectivityObserver {
    val status: StateFlow<ConnectivityStatus>
    fun close()
}

enum class ConnectivityStatus { ONLINE, OFFLINE, UNKNOWN }
```

| Member | Notes |
| --- | --- |
| `status` | starts at `UNKNOWN`; replaced by the platform's first report |
| `close()` | idempotent; unregisters the platform callback, `status` keeps its last value |

**Factories**

```kotlin
// androidMain — needs ACCESS_NETWORK_STATE in the consumer's manifest
fun createConnectivityObserver(context: Context, logger: Logger = NoopLogger): ConnectivityObserver
// iosMain
fun createConnectivityObserver(): ConnectivityObserver
```

Android retains only the application context. Without the permission, registration is refused,
`status` stays `UNKNOWN`, and a warning goes to `logger`.

## `…platform.device`

```kotlin
interface DeviceInfo {
    val osName: String       // "Android" | "iOS"
    val osVersion: String    // never empty; not guaranteed numeric
    val model: String        // never empty; "unknown" if the platform says nothing
    val formFactor: FormFactor
    fun currentCountry(): String?  // uppercase ISO 3166-1 alpha-2, or null; read live
}

enum class FormFactor { PHONE, TABLET, UNKNOWN }
```

**Factories**

```kotlin
fun createDeviceInfo(context: Context): DeviceInfo  // androidMain
fun createDeviceInfo(): DeviceInfo                  // iosMain
```

## `…platform.accessibility`

```kotlin
interface ReducedMotionProbe {
    fun isReducedMotionEnabled(): Boolean  // read live; never throws; false when unknown
}
```

**Factories**

```kotlin
fun createReducedMotionProbe(context: Context): ReducedMotionProbe  // androidMain
fun createReducedMotionProbe(): ReducedMotionProbe                  // iosMain
```

## `…platform.url`

```kotlin
interface UrlOpener {
    fun open(url: String): UrlOpenResult  // never throws
}

enum class UrlOpenResult { OPENED, INVALID_URL, NO_HANDLER, FAILED }
```

| Result | Means |
| --- | --- |
| `OPENED` | handed to the platform; says nothing about what the user did next |
| `INVALID_URL` | not an absolute URL — rejected before the platform saw it |
| `NO_HANDLER` | nothing on the device opens it |
| `FAILED` | the platform refused for some other reason |

**Factories**

```kotlin
fun createUrlOpener(context: Context, logger: Logger = NoopLogger): UrlOpener  // androidMain
fun createUrlOpener(logger: Logger = NoopLogger): UrlOpener                    // iosMain
```

## `…platform.files`

```kotlin
interface FilePicker {
    suspend fun pick(mimeTypes: List<String> = emptyList()): PickResult
}

data class FilePickerConfig(val maxBytes: Long = DEFAULT_MAX_BYTES) {
    companion object { const val DEFAULT_MAX_BYTES: Long = 25L * 1024 * 1024 }
}

sealed interface PickResult {
    data class Picked(val file: PickedFile) : PickResult
    data object Cancelled : PickResult
    data class TooLarge(val sizeBytes: Long, val maxBytes: Long) : PickResult
    data object Unavailable : PickResult
    data class Failed(val cause: Throwable?) : PickResult
}

class PickedFile(val name: String, val mimeTypeHint: String, val bytes: ByteArray) {
    val sizeBytes: Long
    // equals/hashCode compare bytes by content; toString omits them
}
```

`FilePickerConfig` rejects a non-positive `maxBytes` with `IllegalArgumentException`.

**Factories**

```kotlin
// androidMain
interface FilePickerHost {
    fun launch(mimeTypes: Array<String>, onResult: (Uri?) -> Unit): Boolean
}
fun createFilePicker(
    context: Context,
    host: FilePickerHost,
    config: FilePickerConfig = FilePickerConfig(),
    logger: Logger = NoopLogger,
): FilePicker

// iosMain
fun createFilePicker(
    config: FilePickerConfig = FilePickerConfig(),
    logger: Logger = NoopLogger,
): FilePicker
```

`FilePickerHost.launch` must call `onResult` exactly once when it returns `true`, and not at all
when it returns `false`.

## `…platform.wakelock`

```kotlin
interface ScreenWakeLock {
    fun setKeepScreenOn(enabled: Boolean): WakeLockResult  // never throws; idempotent
}

enum class WakeLockResult { APPLIED, NO_ACTIVE_WINDOW, FAILED }
```

**Factories**

```kotlin
fun createScreenWakeLock(activityAccess: ActivityAccess, logger: Logger = NoopLogger): ScreenWakeLock  // androidMain
fun createScreenWakeLock(): ScreenWakeLock                                                             // iosMain
```

iOS always reports `APPLIED`; `NO_ACTIVE_WINDOW` is Android-only, and the request is reapplied to
the next activity that resumes.

## `…platform.crash`

```kotlin
data class CrashRecord(
    val timestampMs: Long,
    val threadName: String,
    val message: String,
    val stackTrace: String,
)

interface CrashLogStore {
    fun write(record: CrashRecord)          // synchronous, never throws
    fun readAndClear(): List<CrashRecord>   // never null; clears as it reads
}

data class CrashLogConfig(
    val fileName: String = DEFAULT_FILE_NAME,
    val directoryPath: String? = null,      // null = platform app-private default
) {
    companion object { const val DEFAULT_FILE_NAME: String = "kmptoolkit_crash_log.txt" }
}

interface CrashHandlerInstallation {
    fun uninstall()  // idempotent
}
```

`CrashLogConfig` rejects a blank `fileName` with `IllegalArgumentException`.

**Factories**

```kotlin
// androidMain — default directory: Context.filesDir
fun createCrashLogStore(context: Context, config: CrashLogConfig = CrashLogConfig()): CrashLogStore
fun installCrashHandler(store: CrashLogStore): CrashHandlerInstallation

// iosMain — default directory: the app's Documents directory
fun createCrashLogStore(config: CrashLogConfig = CrashLogConfig()): CrashLogStore
fun installCrashHandler(store: CrashLogStore): CrashHandlerInstallation
```

## `…platform.build`

```kotlin
expect val isPlatformDebugBuild: Boolean
expect val platformBuildVariant: String
```

Both describe **this library's** binary, not your app's. Consumed as a published artifact they
report the configuration the artifact was published with. See
[`05-platform-notes.md`](05-platform-notes.md#build-variant-reporting).

## `…platform.activity` (Android only)

```kotlin
interface ActivityAccess {
    fun <R> withActivity(block: (Activity) -> R): R?
    fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription
    fun release()
}

interface ActivitySubscription {
    fun cancel()
}

fun createActivityTracker(application: Application): ActivityAccess
```

| Member | Notes |
| --- | --- |
| `withActivity` | `null` when nothing is resumed, or the activity is finishing/destroyed; runs on the calling thread |
| `addOnActivityResumedListener` | fires immediately if one is already resumed, then on every resume; **do not capture the activity** |
| `release()` | unregisters, clears the reference, drops all listeners; idempotent |

The tracker holds the activity through a `WeakReference` and clears it on pause or destroy,
whichever comes first.

## Types this module puts on your compile classpath

Both are `api` dependencies, because they appear in the signatures above:

- `kotlinx.coroutines.flow.StateFlow` — `ConnectivityObserver.status`.
- `io.github.jamal_wia.kmptoolkit.logging.Logger` / `NoopLogger` — the optional `logger` parameter
  on several factories.
