# kmptoolkit-storage — Getting started

## 1. Add the dependency

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
the BOM, omit it. There is no permission to declare and nothing to add to `Info.plist` — see
[`05-platform-notes.md`](05-platform-notes.md).

## 2. Create the store in your platform layer

Android:

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

```kotlin
val storage: KeyValueStorage = createKeyValueStorage()
```

The factories differ because the platforms do — Android needs a `Context`, iOS needs nothing. This
is the only place either name appears; everything downstream takes the interface. Construction is
cheap and cannot fail, so an app delegate or `onCreate` is the right place for it.

## 3. Use it from shared code

```kotlin
class OnboardingState(private val storage: KeyValueStorage) {

    fun hasSeenOnboarding(): Boolean = storage.getStringOrNull(KEY) == "true"

    fun markSeen() {
        storage.put(KEY, "true")
    }

    private companion object {
        const val KEY = "onboarding.seen"
    }
}
```

`getStringOrNull` is the shorthand for "absent and unreadable are the same to me", which is true of
a flag. It is not true of a session token — step 5.

## 4. Give it a device id

```kotlin
val deviceId: String = DeviceIdProvider(storage).current()
httpClient.header("X-Device-Id", deviceId)
```

The first call generates a UUID and stores it; every later call returns the same one, including
after a restart, an app update, and a logout. Clearing app data resets it.

## 5. Store a token in the encrypted store

```kotlin
val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(context) // no argument on iOS

secrets.put("refresh_token", token)

when (val stored = secrets.get("refresh_token")) {
    is StorageResult.Success -> stored.value?.let(::refresh) ?: signIn()
    is StorageResult.Failure -> when (stored.error) {
        // The key that encrypted it is gone — a changed device lock, a restore to a new phone.
        // The value is not coming back; get a new one.
        is StorageError.Undecryptable -> signIn()
        // The store is not readable right now — before first unlock after a reboot, for instance.
        // Try again rather than throwing the session away.
        is StorageError.Unavailable -> retryLater()
        is StorageError.OperationFailed -> report(stored.error)
    }
}
```

Handling `Undecryptable` is not defensive programming for an exotic case: a user changing their
screen lock triggers it on Android, and it is the single most common way a "why am I logged out"
report starts.

## 6. Two stores in one app

```kotlin
val session: KeyValueStorage = createKeyValueStorage(context, StorageConfig("com.example.session"))
val drafts: KeyValueStorage = createKeyValueStorage(context, StorageConfig("com.example.drafts"))
```

Different names mean different files, different suites, different Keychain services. `drafts.clear()`
cannot touch the session, and neither can a key name they happen to share.

## Where to go next

- [`03-guide.md`](03-guide.md) — the contract in practice, and how to choose between the two stores
- [`06-testing.md`](06-testing.md) — testing the code you just wrote without a device
