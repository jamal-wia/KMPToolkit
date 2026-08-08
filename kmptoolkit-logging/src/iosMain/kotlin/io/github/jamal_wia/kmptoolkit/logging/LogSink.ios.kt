package io.github.jamal_wia.kmptoolkit.logging

/**
 * Writes to standard output as `"<L>/<tag>: <message>"` — where `<L>` is the level's initial —
 * followed by the throwable's stack trace on its own lines when there is one.
 *
 * `println` rather than `NSLog`/`os_log` on purpose: `NSLog` takes a C format string, so routing a
 * caller-supplied message through it either needs every `%` escaped or risks a format-specifier
 * being interpreted, and `os_log` would mean an interop dependency in a module whose selling point
 * is having none. Standard output is what Xcode's console shows while debugging; an app that needs
 * unified-logging (visible in Console.app, retained on device) writes a five-line [LogSink] over
 * `os_log` and installs that instead.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    println("${level.name.first()}/$tag: $message")
    if (throwable != null) {
        println(throwable.stackTraceToString())
    }
}
