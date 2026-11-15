@file:MustUseReturnValues

package io.github.jamal_wia.kmptoolkit.storage

/**
 * The outcome of a [KeyValueStorage] operation: either a value or a typed [StorageError].
 *
 * This exists instead of `kotlin.Result` because `Result` can only carry a `Throwable`, and none of
 * [StorageError]'s cases are exceptional in the language's sense — an entry written by a key the
 * platform has since destroyed is an expected outcome a caller handles, not something to throw.
 * Wrapping them in synthetic exceptions would invite `getOrThrow()` and put the crash back.
 */
public sealed interface StorageResult<out T> {

    /**
     * The operation succeeded, producing [value].
     *
     * [toString] never contains [value], nor anything computed from it (its text, its type, its length):
     * it prints a constant. A result read from an [EncryptedKeyValueStorage] holds a decrypted secret (a
     * token, a PIN, an account number), and logging the whole result, putting it in a collection that is
     * logged, or letting a failed assertion print it must not copy that secret into a log or a crash report.
     * To see the value, read [value] explicitly. [equals], [hashCode], [component1] and [copy] still use
     * [value], so two successes that print alike can differ; a test that compares two results and wants the
     * values in its failure message compares their [value] instead.
     */
    public data class Success<out T>(public val value: T) : StorageResult<T> {
        override fun toString(): String = "Success(value=<redacted>)"
    }

    /** The operation failed with [error] and had no effect beyond what the error documents. */
    public data class Failure(public val error: StorageError) : StorageResult<Nothing>
}

/** The value on success, `null` on failure. */
public fun <T> StorageResult<T>.getOrNull(): T? = when (this) {
    is StorageResult.Success -> value
    is StorageResult.Failure -> null
}

/** The error on failure, `null` on success. */
public fun <T> StorageResult<T>.errorOrNull(): StorageError? = when (this) {
    is StorageResult.Success -> null
    is StorageResult.Failure -> error
}

/** Whether this is a [StorageResult.Success]. */
public val StorageResult<*>.isSuccess: Boolean
    get() = this is StorageResult.Success

/**
 * The stored value, or `null` when the key is absent **or** the read failed.
 *
 * The convenience shorthand for the common case where a missing preference and an unreadable one
 * are handled identically — a feature flag, a cached display name. Prefer [KeyValueStorage.get]
 * whenever the difference matters: this collapses [StorageError.Undecryptable] into "not there",
 * which is the exact distinction a "your session ended, log in again" path needs.
 */
public suspend fun KeyValueStorage.getStringOrNull(key: String): String? = get(key).getOrNull()

/**
 * Deprecated on encrypted stores: a default hides [StorageError.AccessDenied],
 * [StorageError.Unavailable] and [StorageError.Undecryptable] — it turns "unreadable" into "absent",
 * which a "signed out" path then acts on. Use [KeyValueStorage.get] and handle
 * [StorageResult.Failure].
 */
@Deprecated(
    "A default turns an unreadable encrypted store into 'absent'. Use get() and handle StorageResult.Failure.",
    level = DeprecationLevel.WARNING,
)
public suspend fun EncryptedKeyValueStorage.getStringOrNull(key: String): String? {
    val plain: KeyValueStorage = this
    return plain.getStringOrNull(key)
}
