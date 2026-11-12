package io.github.jamal_wia.kmptoolkit.files

/**
 * The outcome of a [FileAccess] call that can fail: [Success] with a value or [Failure] with a typed [FileError].
 *
 * There is deliberately no `getOrThrow`: the failures are expected outcomes of working with files, and a throwing
 * accessor would put the crash back.
 *
 * @since 5.0.0
 */
public sealed interface FileResult<out T> {

    /** The operation succeeded and produced [value]. */
    public data class Success<out T>(public val value: T) : FileResult<T>

    /** The operation failed with [error] and had no effect beyond what the function's KDoc documents. */
    public data class Failure(public val error: FileError) : FileResult<Nothing>
}

/** The value of a [FileResult.Success], or `null` for a [FileResult.Failure]. @since 5.0.0 */
public fun <T> FileResult<T>.getOrNull(): T? = (this as? FileResult.Success<T>)?.value

/** The error of a [FileResult.Failure], or `null` for a [FileResult.Success]. @since 5.0.0 */
public fun <T> FileResult<T>.errorOrNull(): FileError? = (this as? FileResult.Failure)?.error

/** Whether this is a [FileResult.Success]. @since 5.0.0 */
public val FileResult<*>.isSuccess: Boolean
    get() = this is FileResult.Success<*>
