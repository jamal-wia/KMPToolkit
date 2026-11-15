package io.github.jamal_wia.kmptoolkit.logging

/**
 * One lock for the whole process: every [platformLogSink] instance shares it because standard error is
 * one stream per process. Only the write of an already rendered string happens under it, so no caller
 * code (a `message`, `toString` or `cause` override) ever runs while it is held.
 */
private val consoleLock: Any = Any()

/**
 * Writes to standard error as `"<L>/<tag>: <message>"` — where `<L>` is the level's initial, as logcat
 * prints it — followed by the throwable's stack trace, cause chain included, on its own lines when there
 * is one. This is the desktop JVM counterpart of the Android and iOS sinks.
 *
 * Standard error rather than standard output so that a desktop app which pipes its output elsewhere
 * keeps its log lines out of that data stream. `System.err` is read at every event, so an app that
 * redirects it with `System.setErr` is followed. The trace is rendered with `stackTraceToString()`,
 * which prints `CIRCULAR REFERENCE` for a repeated throwable instead of looping and renders a chain
 * with an `UnknownHostException` in full. An app that needs files, rotation or a logging framework
 * writes a five-line [LogSink] over it and installs that instead.
 *
 * Each event, its line and its stack trace, is written in one step under a lock shared by every
 * instance, so events logged from several threads never interleave; output written to standard error by
 * anything else (`System.err` elsewhere, a logging framework) does not take that lock and is not ordered
 * against it.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    val header: String = "${level.name.first()}/$tag: $message"
    // The platform's separator throughout, as the trace itself uses it. The rendered trace ends with one of
    // its own; println adds the one that ends the event.
    val text: String =
        if (throwable == null) header else header + System.lineSeparator() + throwable.stackTraceToString().trimEnd()
    synchronized(consoleLock) {
        System.err.println(text)
    }
}
