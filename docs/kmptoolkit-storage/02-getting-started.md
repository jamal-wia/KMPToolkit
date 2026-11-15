# kmptoolkit-storage — Getting started

## 1. Add the dependency

<!-- build-script -->
```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-storage:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-storage-testing:<version>")
        }
    }
}
```

The version is whatever `kmptoolkit.version` says in this repository's `gradle.properties`; with
the BOM, omit it. On the `jvm` target the module brings `kmptoolkit-files` along (its atomic write and
per-file lock); Android and iOS do not. There is no permission to declare and nothing to add to `Info.plist` for the
default configuration; see [`05-platform-notes.md`](05-platform-notes.md). The minimums are Android API 24, iOS 15 and
JDK 21 on desktop.

## 2. Create the store in your platform layer

Android:

<!-- sample: android-create-store -->
```kotlin
class MyApplication : Application() {
    lateinit var storage: KeyValueStorage

    override fun onCreate() {
        super.onCreate()
        storage = createKeyValueStorage(this)
    }
}
```

iOS:

<!-- sample: ios-create-store -->
```kotlin
fun makeStorage(): KeyValueStorage = createKeyValueStorage()
```

Desktop (`jvm`):

<!-- sample: desktop-create-stores -->
```kotlin
fun appDataDirectory(appName: String): File {
    val os: String = System.getProperty("os.name").lowercase()
    val home: String = System.getProperty("user.home")
    return when {
        os.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", appName)
        os.contains("mac") -> File(home, "Library/Application Support/$appName")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", appName)
    }
}

fun createDesktopStorage(): KeyValueStorage = createKeyValueStorage(
    directory = appDataDirectory("MyApp"),
    config = StorageConfig("com.example.myapp.settings"),
)
```

The factories differ because the platforms do: Android needs a `Context`, iOS needs nothing, and desktop needs a directory
and a name, because it has no application id to derive either from (`StorageConfig.name` is **required** on desktop). This is the
only place a factory name appears; everything downstream takes the interface. Construction is cheap and cannot fail on the
device's state, so an app delegate or `onCreate` is the right place for it. Choose a directory under the user's local
application data (`%LOCALAPPDATA%` on Windows, not the roaming profile), and keep it owner-only: the library creates
the directories it makes `0700` and its files owner-only, and never changes a directory that already exists.

## 3. Use it from shared code

<!-- sample: getting-started-onboarding -->
```kotlin
class OnboardingState(private val storage: KeyValueStorage) {

    suspend fun hasSeenOnboarding(): Boolean = storage.getStringOrNull(KEY) == "true"

    suspend fun markSeen(): Boolean = storage.put(KEY, "true").isSuccess

    private companion object {
        const val KEY = "onboarding.seen"
    }
}
```

Everything in this module, the factories included, is in the package `io.github.jamal_wia.kmptoolkit.storage`.

Every storage call is a `suspend` function and main-safe: the store moves its own disk and key-store
work off the calling thread, so call it from whatever coroutine you are already in (a
`viewModelScope.launch`, a `LaunchedEffect`, a repository's own suspend function) without a
`withContext` of your own.

`getStringOrNull` is the shorthand for "absent and unreadable are the same to me", which is true of
a flag on a plain store. It is deprecated on an encrypted store, where it is not true of a session token (step 5).

## 4. Give it a device id

<!-- sample: getting-started-device-id -->
```kotlin
suspend fun deviceIdHeader(storage: KeyValueStorage): Pair<String, String> =
    "X-Device-Id" to DeviceIdProvider(storage).current()
```

The first call generates a UUID and stores it; every later call returns the same one, including
after a restart, an app update, and a logout. Clearing app data resets it. `current()` suspends,
like the store it reads, and concurrent first calls all get the one id that was stored.

## 5. Store a token in the encrypted store

Android (`context` is an Android `Context`) and iOS:

<!-- sample: android-secure-store -->
```kotlin
fun createSecrets(context: Context): SecureKeyValueStorage = createSecureKeyValueStorage(context)
```

<!-- sample: ios-secure-store -->
```kotlin
fun makeSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage()
```

Then, in shared code, write and read it:

<!-- sample: getting-started-token -->
```kotlin
suspend fun storeAndRefresh(secrets: SecureKeyValueStorage, token: String) {
    when (val written: StorageResult<Unit> = secrets.put("refresh_token", token)) {
        is StorageResult.Success -> Unit
        is StorageResult.Failure -> report(written.error)
    }

    when (val stored: StorageResult<String?> = secrets.get("refresh_token")) {
        is StorageResult.Success -> stored.value?.let(::refresh) ?: signIn()
        is StorageResult.Failure -> when (val error: StorageError = stored.error) {
            // The key that encrypted it is gone: a restore to a new phone, a removed lock screen (userAuthentication).
            // The value is not coming back; get a new one.
            is StorageError.Undecryptable -> signIn()
            // The store is not readable right now (before first unlock after a reboot, for instance).
            // Try again rather than throwing the session away.
            is StorageError.Unavailable -> retryLater()
            // The user dismissed a prompt, or a key your app supplied does not open the store.
            is StorageError.AccessDenied -> askAgainOnUserAction()
            // This environment cannot run the store at all: say so, or choose another store explicitly.
            is StorageError.Unsupported -> fallBackExplicitly(error.reason)
            is StorageError.OperationFailed -> report(error)
            is StorageError.InvalidValue -> report(error)
        }
    }
}
```

Handling `Undecryptable` is not defensive programming for an exotic case: a backup restored to a
new phone brings the ciphertext without the key, a store opened with `userAuthentication` loses its
key when the lock screen is removed, and on desktop a deleted key item does the same. The full set of reactions is in [`01-overview.md`](01-overview.md#the-errors-and-one-reaction-each).

## 6. Two stores in one app

<!-- sample: android-two-stores -->
```kotlin
fun createSessionAndDrafts(context: Context): Pair<KeyValueStorage, KeyValueStorage> = Pair(
    createKeyValueStorage(context, StorageConfig("com.example.session")),
    createKeyValueStorage(context, StorageConfig("com.example.drafts")),
)
```

Different names mean different files, different suites, different Keychain services. `drafts.clear()`
cannot touch the session, and neither can a key name they happen to share.

## 7. Desktop: a secure store

Desktop is a first-class target. The OS-key store (`createSecureKeyValueStorage(directory, config, options)`) keeps one data key in the
macOS keychain, Windows DPAPI or the Linux Secret Service and the data in an encrypted file. It needs a native binding:

| Runtime | What to add | Result |
|---|---|---|
| JDK 22 or newer | nothing; the binding is inside `kmptoolkit-storage` and calls the OS through the JDK's Foreign Function and Memory API, with no third-party dependency | works |
| JDK 21 (a JetBrains Runtime 21, say) | `implementation("io.github.jamal-wia:kmptoolkit-storage-jna:<version>")` | works through JNA (dependency `net.java.dev.jna:jna-jpms`) |
| JDK 21 without that artifact | | every call answers `Unsupported(UNSUPPORTED_RUNTIME)`; the plain and the app-key stores still work |

Pass `--enable-native-access=ALL-UNNAMED` to the JVM. JDK 22 and newer print a warning for the FFM binding without it
(JEP 454), JDK 24 and newer for JNA (JEP 472), and JDK 21 accepts the flag and ignores it; a runtime started with
`--illegal-native-access=deny` refuses native access and the store answers `Unsupported(NATIVE_ACCESS_UNAVAILABLE)`. In a Compose Desktop app:

<!-- build-script -->
```kotlin
compose.desktop {
    application {
        mainClass = "com.example.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
```

A first run asks the platform what is available and places the one prompt where the user expects it:

<!-- sample: desktop-first-run -->
```kotlin
suspend fun openSecrets(directory: File): SecureKeyValueStorage? {
    val config: StorageConfig = StorageConfig("com.example.myapp.secrets")
    val options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(itemLabel = "My App sign-in")
    when (val probe: StorageResult<DesktopKeyStore> = probeDesktopKeyStore(options)) {
        is StorageResult.Failure -> return keepInMemoryOrOfferPassphrase(probe.error)
        is StorageResult.Success -> Unit
    }
    val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(directory, config, options)
    // Explain the system prompt to the user first, then ask for the key, so the prompt does not arrive at a random write.
    return when (val ready: StorageResult<Unit> = secrets.ensureKey()) {
        is StorageResult.Success -> secrets
        is StorageResult.Failure -> keepInMemoryOrOfferPassphrase(ready.error)
    }
}
```

`probeDesktopKeyStore()` never prompts and never creates anything; it answers which key store a secure store would use
(`DesktopKeyStore`), or the typed reason there is none. The reaction to each answer is in
[`03-guide.md`](03-guide.md#probe-driven-user-experience). When there is no key store, or the user would rather type a
passphrase, `createAppKeyEncryptedKeyValueStorage` needs no operating-system call at all; see
[`03-guide.md`](03-guide.md#recipe-a-passphrase-store). The plain desktop store and both encrypted stores are
described in [`05-platform-notes.md`](05-platform-notes.md#desktop).

If your app also depends on plain `jna` (OSHI, Testcontainers) next to `kmptoolkit-storage-jna`, the classpath holds two copies of
`com.sun.jna`: exclude one of the two artifacts (the recipe is in
[`05-platform-notes.md`](05-platform-notes.md#packaging-and-runtime-desktop)).

## 8. Android and iOS options in brief

Both platforms take an options object whose defaults are the safe choice:

<!-- sample: android-options -->
```kotlin
suspend fun strongBoxSecrets(context: Context): SecureKeyValueStorage {
    val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(
        context,
        StorageConfig("tokens"),
        AndroidSecureStorageOptions(
            keyBacking = AndroidKeyBacking.PREFER_STRONGBOX,
            unlockedDeviceRequired = true,
        ),
    )
    // Which hardware the key landed in; a store this factory did not create answers UNKNOWN.
    val level: StorageResult<AndroidKeySecurityLevel> = secrets.androidKeySecurityLevel()
    if (level is StorageResult.Success) report(level.value)
    return secrets
}
```

<!-- sample: ios-options -->
```kotlin
fun makeWhenUnlockedSecrets(): SecureKeyValueStorage = createSecureKeyValueStorage(
    options = IosSecureStorageOptions(accessibility = KeychainAccessibility.WHEN_UNLOCKED),
)
```

The options of Android (`keyBacking`, `unlockedDeviceRequired`, `userAuthentication`) apply when the key is generated, and an
existing key keeps the properties it has. The options of iOS (`accessibility`, `accessGroup`, `synchronizable`, `userPresence`)
are validated when the object is built. Every option, its default and its limits are in
[`04-api-reference.md`](04-api-reference.md).

## Where to go next

- [`03-guide.md`](03-guide.md): the contract in practice, shared code, error handling and recipes
- [`06-testing.md`](06-testing.md): testing the code you just wrote without a device
