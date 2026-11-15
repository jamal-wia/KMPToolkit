package docssamples

import io.github.jamal_wia.kmptoolkit.storage.*
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Generated from docs/kmptoolkit-storage by hand and kept equal to it by DocsSamplesDriftTest: edit the document, then the
// text between the sample:begin and sample:end lines, never only one of them. The helpers the samples call are in SampleStubs.kt.

// sample:begin migrate-exhaustive-when
suspend fun react(storage: EncryptedKeyValueStorage, result: StorageResult.Failure) {
    when (val error: StorageError = result.error) {
        is StorageError.Unavailable -> retryLater()
        is StorageError.AccessDenied -> askAgainOnUserAction()          // never loop, never delete
        is StorageError.Unsupported -> fallBackExplicitly(error.reason)  // e.g. keep the session in memory
        is StorageError.Undecryptable -> when (val key: String? = error.key) {
            null -> storeLost()                       // the store cannot be read: sign in again (clear() then works, see 05-platform-notes)
            else -> storage.remove(key).also(::logIfFailure)
        }
        is StorageError.OperationFailed -> log(error)                   // retryable I/O
        is StorageError.InvalidValue -> dropValue(error.key)            // never retry the same value
    }
}
// sample:end migrate-exhaustive-when

// sample:begin migrate-operation-when
fun describe(operation: StorageOperation): String = when (operation) {
    StorageOperation.GET -> "read"
    StorageOperation.PUT -> "write"
    StorageOperation.REMOVE -> "remove"
    StorageOperation.CLEAR -> "clear"
    else -> "key management, or an operation added later"
}
// sample:end migrate-operation-when

// sample:begin migrate-delegation
class LoggingSecureStorage(private val delegate: SecureKeyValueStorage) : SecureKeyValueStorage by delegate {
    override suspend fun put(key: String, value: String): StorageResult<Unit> {
        log("put $key")
        return delegate.put(key, value)
    }
}
// sample:end migrate-delegation
