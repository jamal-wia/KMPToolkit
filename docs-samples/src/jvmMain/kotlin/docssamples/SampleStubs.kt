package docssamples

import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What an app writes itself: the documentation samples call these by name and do not define them. Each one records its
 * call in [SampleLog], so the run test (kmptoolkit-storage's jvmTest) can assert which reaction a sample chose.
 */
object SampleLog {
    private val recorded: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()

    /** The reactions so far, in order, as `name` or `name:argument`. */
    val events: List<String> get() = recorded.toList()

    fun record(event: String) {
        recorded.add(event)
    }

    fun clear() {
        recorded.clear()
    }
}

/** The app's own dialog that asks the user for a passphrase. */
fun interface PassphraseDialog {
    suspend fun await(): CharArray
}

fun resume(token: String) = SampleLog.record("resume:$token")

fun refresh(token: String) = SampleLog.record("refresh:$token")

fun signIn() = SampleLog.record("signIn")

fun retryLater() = SampleLog.record("retryLater")

fun scheduleRetry(cause: Throwable?) = SampleLog.record("scheduleRetry:${cause?.message}")

fun askAgainOnUserAction() = SampleLog.record("askAgainOnUserAction")

fun showUnlockDialogAgain() = SampleLog.record("showUnlockDialogAgain")

fun fallBackExplicitly(reason: UnsupportedReason) = SampleLog.record("fallBackExplicitly:$reason")

fun storeLost() = SampleLog.record("storeLost")

fun reacquire() = SampleLog.record("reacquire")

fun dropValue(key: String) = SampleLog.record("dropValue:$key")

fun report(error: StorageError) = SampleLog.record("report:${error::class.simpleName}")

fun log(error: StorageError) = SampleLog.record("log:${error::class.simpleName}")

fun log(message: String) = SampleLog.record("log:$message")

fun logIfFailure(result: StorageResult<*>) {
    if (result is StorageResult.Failure) SampleLog.record("logIfFailure:${result.error::class.simpleName}")
}

fun keepInMemoryOrOfferPassphrase(error: StorageError): SecureKeyValueStorage? {
    SampleLog.record("keepInMemoryOrOfferPassphrase:${error::class.simpleName}")
    return null
}
