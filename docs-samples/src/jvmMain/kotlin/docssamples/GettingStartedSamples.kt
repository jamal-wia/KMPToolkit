package docssamples

import io.github.jamal_wia.kmptoolkit.storage.*
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Generated from docs/kmptoolkit-storage by hand and kept equal to it by DocsSamplesDriftTest: edit the document, then the
// text between the sample:begin and sample:end lines, never only one of them. The helpers the samples call are in SampleStubs.kt.

// sample:begin desktop-create-stores
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
// sample:end desktop-create-stores

// sample:begin getting-started-onboarding
class OnboardingState(private val storage: KeyValueStorage) {

    suspend fun hasSeenOnboarding(): Boolean = storage.getStringOrNull(KEY) == "true"

    suspend fun markSeen(): Boolean = storage.put(KEY, "true").isSuccess

    private companion object {
        const val KEY = "onboarding.seen"
    }
}
// sample:end getting-started-onboarding

// sample:begin getting-started-device-id
suspend fun deviceIdHeader(storage: KeyValueStorage): Pair<String, String> =
    "X-Device-Id" to DeviceIdProvider(storage).current()
// sample:end getting-started-device-id

// sample:begin getting-started-token
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
// sample:end getting-started-token

// sample:begin desktop-first-run
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
// sample:end desktop-first-run
