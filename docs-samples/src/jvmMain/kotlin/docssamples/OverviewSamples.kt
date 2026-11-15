package docssamples

import io.github.jamal_wia.kmptoolkit.storage.*
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Generated from docs/kmptoolkit-storage by hand and kept equal to it by DocsSamplesDriftTest: edit the document, then the
// text between the sample:begin and sample:end lines, never only one of them. The helpers the samples call are in SampleStubs.kt.

// sample:begin overview-resume-session
// Shared code: it takes the interface, never a factory. Every call is a main-safe suspend function.
suspend fun resumeSession(secrets: SecureKeyValueStorage) {
    when (val token: StorageResult<String?> = secrets.get("refresh_token")) {
        is StorageResult.Success -> token.value?.let(::resume) ?: signIn()
        is StorageResult.Failure -> when (val error: StorageError = token.error) {
            is StorageError.Unavailable -> retryLater()                    // nothing is lost; try again
            is StorageError.AccessDenied -> askAgainOnUserAction()         // never loop, never delete
            is StorageError.Unsupported -> fallBackExplicitly(error.reason) // e.g. keep the session in memory
            is StorageError.Undecryptable -> signIn()                      // the stored session is gone for good
            is StorageError.OperationFailed -> log(error)                  // retryable I/O
            is StorageError.InvalidValue -> dropValue(error.key)           // never retry the same value
        }
    }
}
// sample:end overview-resume-session

// sample:begin overview-require-isolation
fun requireAppIsolation(storage: EncryptedKeyValueStorage) {
    require(storage.protection == SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED) {
        "this secret needs a store that other programs of the user cannot open"
    }
}
// sample:end overview-require-isolation
