package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger

/**
 * This logger, or a wrapper around it that swallows what it throws. The logger is consumer code that the
 * uploader calls from its drain coroutine, its transports, its wake schedulers and its outcome journal;
 * none of them may be stopped by it. Already wrapped loggers are returned as they are.
 */
internal fun Logger.nonThrowing(): Logger = this as? NonThrowingLogger ?: NonThrowingLogger(this)

/**
 * Forwards to [delegate] and swallows every throwable it throws, a `CancellationException` included. The logger
 * is consumer code the engine calls from its only drain coroutine and from the caller's own coroutine in
 * `enqueue`; neither may be broken by it. Logging never suspends, and cancellation reaches a coroutine only at a
 * suspension point or through `ensureActive()`, so a `CancellationException` thrown by a sink is the sink's own
 * failure and never the caller's cancellation, which the uploader still observes at its next suspension point.
 */
internal class NonThrowingLogger(private val delegate: Logger) : Logger {
    override val tag: String
        get() = try { delegate.tag } catch (_: Throwable) { "" }

    override fun isLoggable(level: LogLevel): Boolean =
        try { delegate.isLoggable(level) } catch (_: Throwable) { false }

    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        try {
            delegate.log(level, throwable, message)
        } catch (_: Throwable) {
            // A failing sink loses the line, never the work.
        }
    }
}
