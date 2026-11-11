package io.github.jamal_wia.kmptoolkit.logging

import android.util.Log

/**
 * Writes to logcat via `android.util.Log`, mapping [LogLevel] onto the priority of the same name
 * (`VERBOSE` -> `Log.v`, ..., `ERROR` -> `Log.e`) and passing the `Throwable` through so logcat
 * renders a real stack trace rather than a `toString()`.
 *
 * One throwable cannot be passed through: `Log.getStackTraceString` deliberately returns an empty
 * string for any throwable that has an `UnknownHostException` in its cause chain (so that a flaky
 * network does not flood logcat), which would leave the event with its message and no trace at all.
 * For such a chain the trace is rendered here with `stackTraceToString()` and appended to the
 * message instead, so the chain is shown in full and never swallowed.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    // `Log.getStackTraceString` is the platform's own rendering; asking it first keeps the output
    // of every ordinary throwable exactly what a direct `Log.x(tag, message, throwable)` prints.
    val unrendered: Boolean = throwable != null && Log.getStackTraceString(throwable).isEmpty()
    val text: String = if (throwable != null && unrendered) message + "\n" + throwable.stackTraceToString() else message
    val passed: Throwable? = if (unrendered) null else throwable
    when (level) {
        LogLevel.VERBOSE -> Log.v(tag, text, passed)
        LogLevel.DEBUG -> Log.d(tag, text, passed)
        LogLevel.INFO -> Log.i(tag, text, passed)
        LogLevel.WARN -> Log.w(tag, text, passed)
        LogLevel.ERROR -> Log.e(tag, text, passed)
    }
}
