package io.github.jamal_wia.kmptoolkit.logging

/**
 * Creates [Logger]s that share one level threshold and one set of [LogSink]s.
 *
 * This is the object an app configures once, at startup, and injects wherever logging is needed —
 * there is no global logger state anywhere in this module, so two independent components (or a
 * library and the app embedding it) can each hold their own configuration without fighting over a
 * process-wide singleton.
 */
public interface LoggerFactory {

    /** Returns a logger that stamps [tag] onto every event it emits. */
    public fun logger(tag: String): Logger
}

/**
 * Creates a [LoggerFactory] that forwards every event at or above [minLevel] to each of [sinks],
 * in list order.
 *
 * Behavior worth knowing before you rely on it:
 * - **Empty [sinks] disables logging entirely.** `isLoggable` is then `false` at every level and no
 *   message lambda is ever evaluated. That is the supported "logging off" configuration — there is
 *   no separate off switch.
 * - **The message lambda is evaluated at most once per event**, even with several sinks, and never
 *   when the event is filtered out.
 * - **A sink that throws cannot break the caller.** The throwable is swallowed and the remaining
 *   sinks still receive the event. A logging call is not part of the behavior a caller is trying to
 *   execute, so a broken log destination must not turn into a crash in unrelated code — and there
 *   is nowhere to report the failure to that is not itself a sink.
 * - **An exception from the message lambda is *not* swallowed** and propagates to the caller. That
 *   is a defect in the calling code, not in a log destination, and hiding it would make it
 *   unfindable.
 * - [sinks] is copied, so mutating the list afterwards does not change the factory's behavior. The
 *   sink set is fixed at construction; reconfiguring means creating another factory.
 *
 * @param minLevel lowest severity that reaches the sinks; defaults to [LogLevel.DEBUG].
 * @param sinks destinations, defaulting to a single [platformLogSink].
 */
public fun createLoggerFactory(
    minLevel: LogLevel = LogLevel.DEBUG,
    sinks: List<LogSink> = listOf(platformLogSink()),
): LoggerFactory = SinkLoggerFactory(minLevel, sinks.toList())

private class SinkLoggerFactory(
    private val minLevel: LogLevel,
    private val sinks: List<LogSink>,
) : LoggerFactory {

    // Not cached per tag: a TagLogger holds nothing but two immutable references, so a cache would
    // trade a trivial allocation for a concurrent map that has to stay correct across threads.
    override fun logger(tag: String): Logger = TagLogger(tag, minLevel, sinks)
}

private class TagLogger(
    override val tag: String,
    private val minLevel: LogLevel,
    private val sinks: List<LogSink>,
) : Logger {

    override fun isLoggable(level: LogLevel): Boolean = sinks.isNotEmpty() && level >= minLevel

    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        if (!isLoggable(level)) return
        val text: String = message()
        sinks.forEach { sink ->
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                sink.log(level, tag, text, throwable)
            } catch (_: Throwable) {
                // Deliberate: see createLoggerFactory's KDoc. A failing destination must not
                // propagate into the caller, and must not cost the other sinks their event.
            }
        }
    }
}
