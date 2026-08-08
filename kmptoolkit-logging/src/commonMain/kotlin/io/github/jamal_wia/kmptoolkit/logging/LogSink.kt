package io.github.jamal_wia.kmptoolkit.logging

/**
 * A destination for log events — the module's single extension point.
 *
 * This library deliberately ships no logging backend of its own beyond [platformLogSink]. Bridging
 * to Kermit, Timber, `os_log`, a crash reporter's non-fatal API, or an in-memory buffer for tests
 * is a one-expression `LogSink { level, tag, message, throwable -> ... }`, and keeps the choice of
 * logging framework with the app rather than with the library.
 *
 * **Contract for implementors:**
 * - [log] is called only for events that already passed the logger's level threshold; a sink does
 *   not need to filter again (though it may, e.g. to route `ERROR` somewhere extra).
 * - [log] is called on the caller's thread, synchronously, inside the caller's call stack. Do not
 *   block in it; hand off to your own queue if the destination is slow.
 * - Implementations must be safe to call from several threads at once, because the logger they are
 *   installed in is.
 * - A sink that throws does not break the caller and does not stop the other sinks — see
 *   [createLoggerFactory] for exactly what happens.
 */
public fun interface LogSink {

    /**
     * Writes one already-filtered log event.
     *
     * @param level severity the event was emitted at.
     * @param tag the emitting [Logger]'s tag.
     * @param message the fully materialized message — the lazy lambda has already been evaluated.
     * @param throwable the error the event describes, or `null` if there is none.
     */
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * The platform's own console sink: `android.util.Log` on Android, `println` on iOS (Xcode's console
 * and `NSLog`-style device logs both pick standard output up).
 *
 * Returns a new, stateless instance on every call. See `docs/kmptoolkit-logging/05-platform-notes.md`
 * for the exact per-platform output format and its limits.
 */
public expect fun platformLogSink(): LogSink
