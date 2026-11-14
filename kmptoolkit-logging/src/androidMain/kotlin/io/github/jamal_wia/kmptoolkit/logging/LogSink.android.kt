package io.github.jamal_wia.kmptoolkit.logging

import android.util.Log
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * True when `android.util.Log` must not be handed [throwable] and the sink has to render its trace itself:
 * the cause chain holds an `UnknownHostException` (the platform renders such a chain as an empty string), or it
 * is cyclic (the platform's own walk of the chain never ends on a cycle, and the logging call would hang).
 *
 * The chain is walked to its end with an identity-based visited set, so no fixed depth can miss an
 * `UnknownHostException` that sits late in a long chain, and a cycle is detected the moment a throwable repeats.
 * A `getCause()` that never ends (it builds a fresh throwable on every call) is not supported.
 */
private fun mustRenderHere(throwable: Throwable): Boolean {
    val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = throwable
    while (current != null) {
        if (current is UnknownHostException) return true
        if (!seen.add(current)) return true
        current = current.cause
    }
    return false
}

/**
 * Writes to logcat via `android.util.Log`, mapping [LogLevel] onto the priority of the same name
 * (`VERBOSE` -> `Log.v`, ..., `ERROR` -> `Log.e`) and passing the `Throwable` through so logcat
 * renders a real stack trace rather than a `toString()`.
 *
 * Two kinds of throwable cannot be passed through. `Log.getStackTraceString` deliberately returns an
 * empty string for any throwable that has an `UnknownHostException` in its cause chain, wherever in
 * the chain it sits (so that a flaky network does not flood logcat), which would leave the event with
 * its message and no trace at all; and it walks a cyclic cause chain without end, which would hang the
 * logging call. For both the trace is rendered here with `stackTraceToString()` (which prints
 * `CIRCULAR REFERENCE` for a repeated throwable instead of looping) and appended to the message
 * instead, so the chain is shown in full and never swallowed.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    // The platform's own rule, without rendering the trace to test it: `Log.getStackTraceString`
    // gives up on a chain with an `UnknownHostException` in it and never returns on a cyclic one.
    val unrendered: Boolean = throwable != null && mustRenderHere(throwable)
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
