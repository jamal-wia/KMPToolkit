package io.github.jamal_wia.kmptoolkit.logging

import java.io.PrintStream

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
 * line terminator included, so events logged from several threads never interleave. When standard error is the
 * JDK's own `PrintStream` that call is one write under the stream's own lock and the sink adds nothing. When an
 * app has replaced it with a subclass, which may hand a text on line by line (a line-buffering or capturing stream,
 * the kind Gradle's test worker installs), the sink holds the stream's own monitor for the call, so the lines of
 * two events cannot interleave there either. The monitor taken is the one `Throwable.printStackTrace` takes on
 * such a stream while it calls the throwable's `getMessage` and `toString`, so there is a single lock and no
 * order to invert: a throwable whose getter logs does not deadlock. The sink never takes a lock of its own, which
 * would. For a subclass the sink locks exactly as `System.err.println` does (the stream's monitor, then whatever
 * the stream's `print` takes), so it can deadlock only where `System.err.println` on the same stream would: for
 * example a subclass whose `print` forwards to another stream on which a trace whose getter logs is being
 * printed. Output written to standard error by anything else (`System.err` elsewhere, a logging framework) is
 * not ordered against the sink's events.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    val header: String = "${level.name.first()}/$tag: $message"
    // The platform's separator throughout, as the trace itself uses it. The rendered trace ends with one of
    // its own, trimmed here; the terminator that ends the event is added to the same string so that one
    // print(String) writes the whole event.
    val text: String =
        if (throwable == null) header else header + System.lineSeparator() + throwable.stackTraceToString().trimEnd()
    val err: PrintStream = System.err
    val event: String = text + System.lineSeparator()
    if (err.javaClass == PrintStream::class.java) {
        // The JDK's own class writes a whole String under its own lock, with no monitor visible to us.
        err.print(event)
    } else {
        // A subclass may split the text; its own monitor is the lock printStackTrace takes, so this adds no order.
        synchronized(err) { err.print(event) }
    }
}
