package io.github.jamal_wia.kmptoolkit.logging

import platform.Foundation.NSLock

/**
 * One lock for the whole process: every [platformLogSink] instance shares it because standard output is
 * one stream per process. Only the write of an already rendered string happens under it, so no caller
 * code (a `message`, `toString` or `cause` override) ever runs while it is held.
 */
private val consoleLock: NSLock = NSLock()

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
 *
 * Each event, its line and its stack trace, is written in one step under a lock shared by every
 * instance, so events logged from several threads never interleave; output written to standard output by
 * anything else (`println` elsewhere, `NSLog`) does not take that lock and is not ordered against it.
 */
public actual fun platformLogSink(): LogSink = LogSink { level, tag, message, throwable ->
    val header: String = "${level.name.first()}/$tag: $message"
    val text: String = if (throwable == null) header else header + "\n" + throwable.stackTraceToString()
    consoleLock.lock()
    try {
        println(text)
    } finally {
        consoleLock.unlock()
    }
}
