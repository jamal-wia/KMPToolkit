package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.TimeSource

// Limits of the stack-trace rendering of a throwable (see describeThrowable): nesting depth and total nodes.
private const val MAX_RENDERED_THROWABLE_DEPTH: Int = 64
private const val MAX_RENDERED_THROWABLES: Int = 1024

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
        val throwableText: String? = throwable?.let(::describeThrowable)
        _records.update { current ->
            // Read inside the transform: a record that wins its compare-and-set after another one read
            // the list after that one's commit, so its stamp is never smaller; read outside, a writer
            // that loses a round keeps an older stamp. A clock read is no side effect on shared state,
            // so the lambda stays safe to re-run.
            val elapsedMillis: Long = startMark.elapsedNow().inWholeMilliseconds
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

    // A throwable whose own toString()/message throws (a message built lazily from a null field) makes
    // stackTraceToString() throw. The sink promises never to throw and the message is still worth
    // showing, so the record keeps a placeholder naming the class instead of losing the event.
    //
    // A throwable whose cause graph is deeper or larger than the limits below is not handed to
    // stackTraceToString(): its trace dump recurses once per cause and would overflow the stack, and
    // nothing here catches a StackOverflowError (or any Error) to contain it. The graph is measured
    // iteratively first, and an over-limit one is summarised, also iteratively.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun describeThrowable(throwable: Throwable): String =
        try {
            if (isWithinRenderLimits(throwable)) throwable.stackTraceToString() else summarize(throwable)
        } catch (failure: Exception) {
            "${throwable::class.simpleName ?: "Throwable"} (stack trace unavailable: ${failure::class.simpleName ?: "Exception"})"
        }

    /** Whether the cause/suppressed graph under [root] stays within the depth and node limits. Never recurses. */
    private fun isWithinRenderLimits(root: Throwable): Boolean {
        val visited: HashSet<Throwable> = hashSetOf(root)
        val pending: ArrayDeque<Pair<Throwable, Int>> = ArrayDeque()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (node: Throwable, depth: Int) = pending.removeFirst()
            val children: List<Throwable> = listOfNotNull(node.cause) + node.suppressedExceptions
            for (child: Throwable in children) {
                if (!visited.add(child)) continue
                if (depth + 1 > MAX_RENDERED_THROWABLE_DEPTH || visited.size > MAX_RENDERED_THROWABLES) return false
                pending.addLast(child to depth + 1)
            }
        }
        return true
    }

    /** The throwable's own text, its first [MAX_RENDERED_THROWABLE_DEPTH] causes and a count of the rest; no frames. */
    private fun summarize(root: Throwable): String {
        val seen: HashSet<Throwable> = hashSetOf(root)
        val lines: MutableList<String> = mutableListOf(root.toString())
        var next: Throwable? = root.cause
        var shown = 0
        while (next != null && shown < MAX_RENDERED_THROWABLE_DEPTH && seen.add(next)) {
            lines += "Caused by: $next"
            shown++
            next = next.cause
        }
        var more = 0
        while (next != null && more <= MAX_RENDERED_THROWABLES && seen.add(next)) {
            more++
            next = next.cause
        }
        if (more > 0) {
            lines += if (more > MAX_RENDERED_THROWABLES) {
                "... more than $MAX_RENDERED_THROWABLES more causes not shown"
            } else {
                "... $more more causes not shown"
            }
        }
        return lines.joinToString("\n")
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
     * satisfies the `LogSink` contract without the logger's error containment having to kick in. A
     * throwable whose cause chain is too deep to render (more than 64 nested causes) is recorded as
     * its first 64 causes, without stack frames.
     * Each call returns a new sink instance; they are interchangeable, since all of them delegate
     * to this same state.
     */
    public fun asLogSink(): LogSink = LogSink { level, tag, message, throwable ->
        record(level = level, tag = tag, message = message, throwable = throwable)
    }
}
