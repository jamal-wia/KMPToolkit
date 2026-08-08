package io.github.jamal_wia.kmptoolkit.logging

import android.util.Log

/**
 * Writes to logcat via `android.util.Log`, mapping [LogLevel] onto the priority of the same name
 * (`VERBOSE` -> `Log.v`, ..., `ERROR` -> `Log.e`) and passing the `Throwable` through so logcat
 * renders a real stack trace rather than a `toString()`.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    when (level) {
        LogLevel.VERBOSE -> Log.v(tag, message, throwable)
        LogLevel.DEBUG -> Log.d(tag, message, throwable)
        LogLevel.INFO -> Log.i(tag, message, throwable)
        LogLevel.WARN -> Log.w(tag, message, throwable)
        LogLevel.ERROR -> Log.e(tag, message, throwable)
    }
}
