package io.github.jamal_wia.kmptoolkit.logging

/**
 * Severity of a single log event, ordered from least to most severe.
 *
 * The enum's natural ordering *is* the severity ordering, so a threshold check is a plain
 * comparison: an event passes when `level >= minLevel`. Every entry is a level an event can
 * actually be emitted at — there is deliberately no `NONE`/`OFF` entry, because "log nothing" is
 * expressed by installing no [LogSink] (see [createLoggerFactory]) rather than by a level that
 * would be legal in one position and meaningless in the other.
 */
public enum class LogLevel {
    /** Fine-grained tracing, normally off outside local debugging. */
    VERBOSE,

    /** Developer-facing detail about normal operation. */
    DEBUG,

    /** A notable, expected event worth keeping in a release log. */
    INFO,

    /** Something recoverable that should not have happened. */
    WARN,

    /** A failure — usually paired with the `Throwable` that caused it. */
    ERROR,
}
