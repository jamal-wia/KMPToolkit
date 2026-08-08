package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

/** Records retained by default — enough to read back a screen's worth of history, small enough to forget about. */
public const val DEFAULT_MAX_RECORDS: Int = 200

/**
 * The overlay's backing store: a bounded, thread-safe buffer of [LogRecord]s plus the visibility
 * flag [LogOverlayHost] reads.
 *
 * You create it, you own it, and you decide how long it lives — there is no singleton and no
 * framework hook. Create one instance at app start, install [asLogSink] into your
 * [io.github.jamal_wia.kmptoolkit.logging.LoggerFactory], and pass the same instance to
 * [LogOverlayHost]. Creating it inside `remember` would tie the log history to a composition and
 * throw it away on the first configuration change, which is exactly when you want it.
 *
 * **This is a development tool.** Every retained record is memory that is not released until it is
 * evicted or [clear]ed, and every record is painted on a screen someone may be looking at. Nothing
 * here filters personal data — see `docs/kmptoolkit-logging-overlay/01-overview.md`. Gate both the
 * sink and the host behind your own debug-build flag.
 *
 * **Thread safety:** [record], [clear], [show], [hide] and [toggle] may be called from any thread.
 * Appends are atomic, so records never interleave into a torn list, and the recording order of two
 * concurrent calls is whichever the underlying compare-and-set resolves first.
 *
 * @param maxRecords how many records to retain; the oldest is evicted once the buffer is full.
 *   Must be at least `1`.
 * @param minLevel severity below which an event is dropped instead of recorded. Independent of the
 *   `LoggerFactory`'s own threshold — this one narrows what the *overlay* keeps, so a factory can
 *   log `DEBUG` to logcat while the on-screen list shows `WARN` and above.
 * @throws IllegalArgumentException if [maxRecords] is less than `1`.
 */
public class LogOverlayState(
    public val maxRecords: Int = DEFAULT_MAX_RECORDS,
    public val minLevel: LogLevel = LogLevel.VERBOSE,
) {

    init {
        require(maxRecords >= 1) { "maxRecords must be at least 1, was $maxRecords" }
    }

    private val startMark: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()

    private val _records: MutableStateFlow<List<LogRecord>> = MutableStateFlow(emptyList())

    /**
     * The retained records, oldest first, never longer than [maxRecords].
     *
     * Each emission is a new immutable list; the previous one is never mutated behind a collector's
     * back.
     */
    public val records: StateFlow<List<LogRecord>> = _records.asStateFlow()

    private val _isVisible: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Whether [LogOverlayHost] currently draws the log panel over its content. Starts `false`.
     *
     * Drive it from whatever trigger your app already has for developer tools — a dev-menu entry, a
     * long-press, a shake detector. This module deliberately ships no trigger of its own, because
     * any gesture it picked would collide with some app's real one.
     */
    public val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    /**
     * Records one event, dropping it if [level] is below [minLevel], and evicting the oldest record
     * if the buffer is already at [maxRecords].
     *
     * Recording a [throwable] converts its stack trace to text on the calling thread. That is the
     * one genuinely non-trivial cost in this class; it is acceptable for a debug tool and is a
     * reason not to leave the overlay installed in a release build.
     */
    public fun record(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        if (level < minLevel) return
        val elapsedMillis: Long = startMark.elapsedNow().inWholeMilliseconds
        val throwableText: String? = throwable?.stackTraceToString()
        _records.update { current ->
            // Derived from the list rather than a separate counter so the lambda stays pure: under
            // contention `update` re-runs it, and a counter incremented here would skip ids.
            val record = LogRecord(
                id = (current.lastOrNull()?.id ?: 0L) + 1L,
                level = level,
                tag = tag,
                message = message,
                throwableText = throwableText,
                elapsedMillis = elapsedMillis,
            )
            if (current.size < maxRecords) {
                current + record
            } else {
                current.subList(current.size - maxRecords + 1, current.size) + record
            }
        }
    }

    /** Drops every retained record. Id numbering restarts at `1` for the next recorded event. */
    public fun clear(): Unit = _records.update { emptyList() }

    /** Sets [isVisible] to `true`. */
    public fun show() {
        _isVisible.value = true
    }

    /** Sets [isVisible] to `false`. */
    public fun hide() {
        _isVisible.value = false
    }

    /** Flips [isVisible]. Convenient for a single dev-menu toggle. */
    public fun toggle(): Unit = _isVisible.update { visible -> !visible }

    /**
     * A [LogSink] that feeds this state.
     *
     * Install it alongside — not instead of — your real sink, so the same events still reach logcat
     * or `os_log`:
     *
     * ```kotlin
     * val overlayState = LogOverlayState()
     * val loggerFactory = createLoggerFactory(
     *     sinks = if (isDebugBuild) listOf(platformLogSink(), overlayState.asLogSink()) else listOf(platformLogSink()),
     * )
     * ```
     *
     * The returned sink honors this state's [minLevel] (through [record]) and never throws, which
     * satisfies the `LogSink` contract without the logger's error containment having to kick in.
     * Each call returns a new sink instance; they are interchangeable, since all of them delegate
     * to this same state.
     */
    public fun asLogSink(): LogSink = LogSink { level, tag, message, throwable ->
        record(level = level, tag = tag, message = message, throwable = throwable)
    }
}
