package io.github.jamal_wia.kmptoolkit.logging

import kotlin.time.TimedValue
import kotlin.time.measureTimedValue

/**
 * A tagged logger — the type your code depends on.
 *
 * Obtain one from a [LoggerFactory] (`factory.logger("Auth")`) and hold it in the class that logs.
 * The message is a lambda, so building it costs nothing when the event is filtered out: an
 * implementation must evaluate it only after [isLoggable] would have returned `true`, and then at
 * most once regardless of how many sinks are installed.
 *
 * Implementations are thread-safe and hold no per-call state.
 *
 * Prefer the [v], [d], [i], [w] and [e] extensions over calling [log] directly; [log] exists for
 * code that has a [LogLevel] in a variable.
 */
public interface Logger {

    /** The tag every event from this logger carries. */
    public val tag: String

    /**
     * Whether an event at [level] would reach at least one sink.
     *
     * Use it to guard work that is expensive beyond building the message string — walking a
     * collection, serializing a payload — which a lazy message lambda alone cannot avoid.
     */
    public fun isLoggable(level: LogLevel): Boolean

    /**
     * Emits one event, evaluating [message] only if [isLoggable] holds for [level].
     *
     * @param throwable the error the event describes, or `null`.
     */
    public fun log(level: LogLevel, throwable: Throwable?, message: () -> String)
}

/** Emits [message] at [LogLevel.VERBOSE]. */
public fun Logger.v(message: () -> String): Unit = log(LogLevel.VERBOSE, null, message)

/** Emits [message] at [LogLevel.DEBUG]. */
public fun Logger.d(message: () -> String): Unit = log(LogLevel.DEBUG, null, message)

/** Emits [message] at [LogLevel.INFO]. */
public fun Logger.i(message: () -> String): Unit = log(LogLevel.INFO, null, message)

/** Emits [message] — and optionally [throwable] — at [LogLevel.WARN]. */
public fun Logger.w(throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.WARN, throwable, message)

/** Emits [message] — and optionally [throwable] — at [LogLevel.ERROR]. */
public fun Logger.e(throwable: Throwable? = null, message: () -> String): Unit =
    log(LogLevel.ERROR, throwable, message)

/**
 * Runs [block], then logs how long it took as `"<label> [<duration>]"` at [level].
 *
 * The measurement itself is unconditional — only the resulting log event is filtered — so the
 * duration reported is the real one and not an artifact of a disabled logger. [block]'s result is
 * returned unchanged, and an exception thrown by [block] propagates without a log event.
 *
 * Declared `inline` so [block] may suspend and may `return` non-locally — timing a suspending call
 * is the common case, and a non-inline version silently cannot do it.
 */
public inline fun <T> Logger.logTimed(
    label: String,
    level: LogLevel = LogLevel.DEBUG,
    block: () -> T,
): T {
    val timed: TimedValue<T> = measureTimedValue(block)
    log(level, null) { "$label [${timed.duration}]" }
    return timed.value
}

/**
 * A logger that discards everything and never evaluates a message lambda.
 *
 * Useful as a default parameter value (`logger: Logger = NoopLogger`) so a consumer can opt into
 * logging without every call site having to null-check. Its [tag] is the empty string.
 */
public object NoopLogger : Logger {
    override val tag: String = ""
    override fun isLoggable(level: LogLevel): Boolean = false
    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String): Unit = Unit
}
