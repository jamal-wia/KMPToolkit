package docssamples

import io.github.jamal_wia.kmptoolkit.storage.*
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Generated from docs/kmptoolkit-storage by hand and kept equal to it by DocsSamplesDriftTest: edit the document, then the
// text between the sample:begin and sample:end lines, never only one of them. The helpers the samples call are in SampleStubs.kt.

// sample:begin guide-launch-counter
class LaunchCounter(private val storage: KeyValueStorage) {
    private val mutex = Mutex()

    suspend fun increment(): Int = mutex.withLock {
        val next: Int = storage.getIntOr("launch_count", 0) + 1
        storage.putInt("launch_count", next).also(::logIfFailure)
        next
    }
}
// sample:end guide-launch-counter

// sample:begin guide-config
val appStore: StorageConfig = StorageConfig(name = null)                       // Android, iOS: derived from your application id
val sessionStore: StorageConfig = StorageConfig(name = "com.example.session")  // an independent store; required on desktop
// sample:end guide-config

// sample:begin guide-handle-errors
suspend fun handle(storage: EncryptedKeyValueStorage, result: StorageResult<String?>) {
    if (result is StorageResult.Failure) {
        when (val error: StorageError = result.error) {
            // Nothing is lost. Retry later (after the first unlock following a reboot, typically), and do not
            // destroy state on the strength of it.
            is StorageError.Unavailable -> scheduleRetry(error.cause)

            // A dismissed prompt, or a key you supplied that does not open the store. Retry only when the user acts.
            is StorageError.AccessDenied -> askAgainOnUserAction()

            // This environment cannot run the store. `error.reason` names the one fix.
            is StorageError.Unsupported -> fallBackExplicitly(error.reason)

            // Plaintext is gone for good. A named key is removed; a null key is the whole store.
            is StorageError.Undecryptable -> {
                when (val key: String? = error.key) {
                    null -> storeLost()
                    else -> storage.remove(key).also(::logIfFailure)
                }
                reacquire()
            }

            // `platformCode` carries an iOS OSStatus where there is one and `cause` the exception where the platform
            // threw; both are for your logs, not for a user.
            is StorageError.OperationFailed -> report(error)

            // The same value fails the same way: drop it or fix the caller.
            is StorageError.InvalidValue -> dropValue(error.key)
        }
    }
}
// sample:end guide-handle-errors

// sample:begin guide-one-record
data class Session(val accessToken: String, val refreshToken: String, val userId: String)

// Use whatever serialization you already have (kotlinx.serialization, say); this one only has to round-trip.
fun Session.encode(): String = listOf(accessToken, refreshToken, userId).joinToString("\t")

fun decodeSession(text: String): Session? {
    val parts: List<String> = text.split("\t")
    return if (parts.size == 3) Session(parts[0], parts[1], parts[2]) else null
}

suspend fun saveSession(secrets: EncryptedKeyValueStorage, session: Session): StorageResult<Unit> =
    secrets.put("session", session.encode())
// sample:end guide-one-record

// sample:begin recipe-migrate
interface LegacySecretFile {
    suspend fun read(key: String): String?       // the app's own decryption
    suspend fun erase(key: String): Boolean      // one key at a time, never the whole file
    suspend fun eraseKeyMaterial(): Boolean      // the old key that sat next to the ciphertext
}

/** Returns true when every record is in [target] and the old file and its key are gone; a rerun is always safe. */
suspend fun migrateSecrets(old: LegacySecretFile, target: EncryptedKeyValueStorage, keys: List<String>): Boolean {
    var complete = true
    for (key in keys) {
        // 1. The new store is the truth.
        when (val existing: StorageResult<String?> = target.get(key)) {
            is StorageResult.Success -> if (existing.value != null) {
                if (!old.erase(key)) complete = false
                continue
            }
            is StorageResult.Failure -> if (existing.error !is StorageError.Undecryptable) {
                complete = false
                continue
            }
        }
        // 2. Copy, then read it back equal.
        val value: String = old.read(key) ?: continue
        if (target.put(key, value) !is StorageResult.Success) {
            complete = false
            continue
        }
        val readBack: StorageResult<String?> = target.get(key)
        if (readBack !is StorageResult.Success || readBack.value != value) {
            target.remove(key).also(::logIfFailure)
            complete = false
            continue
        }
        // 3. Erase the old copy last.
        if (!old.erase(key)) complete = false
    }
    // 4. The old key material goes only when nothing it protected is left.
    if (complete && !old.eraseKeyMaterial()) complete = false
    return complete
}
// sample:end recipe-migrate

// sample:begin recipe-passphrase
fun createVault(directory: File, unlockDialog: PassphraseDialog): AppKeyEncryptedKeyValueStorage =
    createAppKeyEncryptedKeyValueStorage(
        directory,
        StorageConfig("com.example.myapp.vault"),
        AppEncryptionKey.Passphrase(provider = { unlockDialog.await() }),
    )

/** The unlock screen: true when the vault is open. */
suspend fun unlock(vault: AppKeyEncryptedKeyValueStorage): Boolean =
    when (val opened: StorageResult<Unit> = vault.ensureKey()) {
        is StorageResult.Success -> true
        is StorageResult.Failure -> {
            when (opened.error) {
                is StorageError.AccessDenied -> showUnlockDialogAgain()   // a wrong passphrase: ask again, on a user action
                is StorageError.Unavailable -> retryLater()               // the provider threw: try later
                else -> report(opened.error)
            }
            false
        }
    }

/** "Change passphrase": re-encrypts under a fresh data key. Copies taken earlier stay readable with the old passphrase. */
suspend fun changePassphrase(vault: AppKeyEncryptedKeyValueStorage, newPassphraseDialog: PassphraseDialog): StorageResult<Unit> =
    vault.changeKey(AppEncryptionKey.Passphrase(provider = { newPassphraseDialog.await() }))
// sample:end recipe-passphrase

// sample:begin recipe-probe
enum class SecretsPlan { OsKeyStore, PassphraseStore, InMemoryOnly, TryLater, FixRuntime }

fun planFor(probe: StorageResult<DesktopKeyStore>): SecretsPlan = when (probe) {
    // Offer "Remember me", explain the prompt, then call ensureKey().
    is StorageResult.Success -> SecretsPlan.OsKeyStore
    is StorageResult.Failure -> when (val error: StorageError = probe.error) {
        is StorageError.Unsupported -> when (error.reason) {
            // A developer message: add kmptoolkit-storage-jna or run on JDK 22+.
            UnsupportedReason.UNSUPPORTED_RUNTIME -> SecretsPlan.FixRuntime
            // No keyring or no user session: keep the session in memory only, or offer the passphrase store.
            UnsupportedReason.NO_KEY_STORE, UnsupportedReason.NO_USER_SESSION -> SecretsPlan.PassphraseStore
            else -> SecretsPlan.InMemoryOnly
        }
        // The keychain is locked and no dialog is allowed, or a service did not answer: keep in memory, retry later.
        is StorageError.Unavailable -> SecretsPlan.TryLater
        is StorageError.AccessDenied, is StorageError.OperationFailed,
        is StorageError.Undecryptable, is StorageError.InvalidValue -> SecretsPlan.InMemoryOnly
    }
}
// sample:end recipe-probe

// sample:begin recipe-headless
fun createHeadlessStores(directory: File, keyFile: File): Pair<SecureKeyValueStorage, AppKeyEncryptedKeyValueStorage> = Pair(
    createSecureKeyValueStorage(
        directory,
        StorageConfig("com.example.agent.os"),
        DesktopSecureStorageOptions(allowUserInteraction = false),
    ),
    // The provider returns exactly 32 bytes, fresh on every call; any other length is AccessDenied.
    createAppKeyEncryptedKeyValueStorage(
        directory,
        StorageConfig("com.example.agent.raw"),
        AppEncryptionKey.Raw(provider = { keyFile.readBytes() }),
    ),
)
// sample:end recipe-headless
