package io.github.jamal_wia.kmptoolkit.platform.crash

/**
 * One recorded uncaught exception, written as the process was dying and read back on the next
 * launch.
 *
 * The point of persisting it is that the interesting crash is the one nobody saw: the app died
 * before any reporter could upload anything, and on the next start there is no trace of it. A
 * record here lets you log it, show it in a debug screen, or attach it to the next report.
 *
 * @property timestampMs when the crash was recorded, in milliseconds since the Unix epoch, from
 *   the device's wall clock. The user can move that clock, so treat it as a rough ordering hint,
 *   not as a trustworthy time.
 * @property threadName the thread that threw — `"main"` for the vast majority of Android crashes,
 *   `"native"` on iOS, where Kotlin/Native's unhandled-exception hook does not name the thread.
 * @property message the exception's message, or its class name when it carried no message. Never
 *   empty.
 * @property stackTrace the full stack trace as text. Unsymbolicated on iOS release builds unless
 *   you keep the `.dSYM`.
 */
public data class CrashRecord(
    val timestampMs: Long,
    val threadName: String,
    val message: String,
    val stackTrace: String,
)
