package io.github.jamal_wia.kmptoolkit.logging

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
 * Each event, its line and its stack trace, is written to standard error with one `print(String)` call, the
 * line terminator included, so events logged from several threads never interleave: that call is one write
 * under the stream's own lock. The sink takes no lock of its own on purpose. `Throwable.printStackTrace` holds
 * the lock of standard error while it calls the throwable's `getMessage` and `toString`, so a second lock taken
 * before the stream's would deadlock against a throwable whose getter logs. Output written to standard error
 * by anything else (`System.err` elsewhere, a logging framework) is not ordered against the sink's events.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    val header: String = "${level.name.first()}/$tag: $message"
    // The platform's separator throughout, as the trace itself uses it. The rendered trace ends with one of
    // its own, trimmed here; the terminator that ends the event is added to the same string so that one
    // print(String) writes the whole event.
    val text: String =
        if (throwable == null) header else header + System.lineSeparator() + throwable.stackTraceToString().trimEnd()
    System.err.print(text + System.lineSeparator())
}
