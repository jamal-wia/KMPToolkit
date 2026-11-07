package io.github.jamal_wia.kmptoolkit.logging

import kotlin.coroutines.cancellation.CancellationException

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
 * Whether a sink's failure is one the logger contains: every `Exception`, plus the Errors a broken or
 * unfinished sink raises (`AssertionError`, `NotImplementedError`, and on the JVM a `LinkageError`
 * such as `NoSuchMethodError`). Any other Error (`StackOverflowError`, `OutOfMemoryError`) is not.
 */
internal fun Throwable.isContainedSinkFailure(): Boolean =
    this is Exception || this is AssertionError || this is NotImplementedError || this.isLinkageError()

/** The JVM's `LinkageError` (a library version mismatch); never true where the platform has none. */
internal expect fun Throwable.isLinkageError(): Boolean

/**
 * Whether this is the JVM's `InterruptedException`; never true where the platform has none. A sink's
 * blocking write woken by the caller's interrupt throws it and the throw clears the thread's interrupt
 * flag, so the logger has to put the flag back after containing it.
 */
internal expect fun Throwable.isInterruptSignal(): Boolean

/** Re-raises the current thread's interrupt flag where the platform has one; a no-op elsewhere. */
internal expect fun restoreInterruptFlag()

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
 *   sinks still receive the event. That covers every `Exception` and the Errors a broken sink raises
 *   for its own reasons: an `AssertionError`, a `TODO()` sink's `NotImplementedError`, and on Android
 *   a `LinkageError` such as `NoSuchMethodError`. Two kinds reach the caller: a
 *   `CancellationException` is rethrown, but only after every sink has been offered the event, and
 *   any other `Error` (`StackOverflowError`, `OutOfMemoryError`) is rethrown at once, because
 *   swallowing it would hide a process that is already failing. A sink must not log through the factory it is installed in:
 *   there is no re-entrancy guard, so it recurses until the stack overflows and that error reaches
 *   the caller. A logging call is not part of the behavior a caller is trying to
 *   execute, so a broken log destination must not turn into a crash in unrelated code — and there
 *   is nowhere to report the failure to that is not itself a sink. A sink's `InterruptedException`
 *   (a blocking write woken by the caller's interrupt) is contained too, but the interrupt flag that
 *   throw cleared is set on the caller's thread again once every sink has had the event (JVM and
 *   Android), so a worker or blocking section that logs still sees its interrupt.
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
        // The first cancellation a sink raised: rethrown once every sink has had the event, so a
        // cancelling sink costs the sinks after it nothing.
        var cancellation: CancellationException? = null
        var interrupted = false
        sinks.forEach { sink ->
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            try {
                sink.log(level, tag, text, throwable)
            } catch (e: CancellationException) {
                // Never absorbed: a sink that touches a cancellable API inside a cancelled
                // coroutine must not have that cancellation eaten here, or logging would silently
                // break the caller's structured concurrency — the exact opposite of the guarantee
                // this catch exists to provide.
                if (cancellation == null) cancellation = e
            } catch (e: Throwable) {
                // Deliberate: see createLoggerFactory's KDoc. A failing destination must not
                // propagate into the caller, and must not cost the other sinks their event. That
                // includes the Errors a broken or unfinished sink raises for its own reasons: an
                // AssertionError, a TODO() sink's NotImplementedError, a NoSuchMethodError from a
                // mismatched logging library.
                //
                // Any other Error is rethrown: it means the process is already in trouble
                // (StackOverflowError from a sink that logs into itself, OutOfMemoryError) and
                // swallowing it would turn a loud, findable bug into an invisible one.
                if (!e.isContainedSinkFailure()) throw e
                if (e.isInterruptSignal()) interrupted = true
            }
        }
        // After the loop, not inside it: restoring at once would make every later blocking sink
        // throw straight away and lose its event.
        if (interrupted) restoreInterruptFlag()
        cancellation?.let { throw it }
    }
}
