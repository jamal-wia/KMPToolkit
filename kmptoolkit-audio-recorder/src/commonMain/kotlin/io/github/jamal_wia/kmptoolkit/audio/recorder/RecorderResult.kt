package io.github.jamal_wia.kmptoolkit.audio.recorder

/**
 * The outcome of an [AudioRecorder] operation: either a value or a typed [RecorderError].
 *
 * This exists instead of `kotlin.Result` because `Result` can only carry a `Throwable`, and none of
 * [RecorderError]'s cases are exceptional in the language's sense — an illegal transition or a
 * missing permission is an expected outcome a caller handles, not something to throw. Wrapping
 * them in synthetic exceptions would invite `getOrThrow()` and put the crash back.
 */
public sealed interface RecorderResult<out T> {

    /** The operation succeeded, producing [value]. */
    public data class Success<out T>(public val value: T) : RecorderResult<T>

    /** The operation failed with [error] and had no effect beyond what the error documents. */
    public data class Failure(public val error: RecorderError) : RecorderResult<Nothing>
}

/** The value on success, `null` on failure. */
public fun <T> RecorderResult<T>.getOrNull(): T? = when (this) {
    is RecorderResult.Success -> value
    is RecorderResult.Failure -> null
}

/** The error on failure, `null` on success. */
public fun <T> RecorderResult<T>.errorOrNull(): RecorderError? = when (this) {
    is RecorderResult.Success -> null
    is RecorderResult.Failure -> error
}

/** Whether this is a [RecorderResult.Success]. */
public val RecorderResult<*>.isSuccess: Boolean
    get() = this is RecorderResult.Success
