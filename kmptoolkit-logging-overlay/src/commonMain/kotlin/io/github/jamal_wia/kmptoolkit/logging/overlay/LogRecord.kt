package io.github.jamal_wia.kmptoolkit.logging.overlay

import io.github.jamal_wia.kmptoolkit.logging.LogLevel

/**
 * One log event retained by a [LogOverlayState] and drawn by [LogOverlayHost].
 *
 * A record is an immutable snapshot taken at the moment the event was recorded — nothing in it is
 * recomputed later, so it is safe to hold and safe to pass through Compose as a stable parameter.
 *
 * @property id position in the recording order, starting at `1` and increasing by one per retained
 *   record. Unique among the records a single [LogOverlayState] currently holds, which is what
 *   makes it usable as a `LazyColumn` key. Numbering restarts after [LogOverlayState.clear].
 * @property level severity the event was emitted at.
 * @property tag tag of the [io.github.jamal_wia.kmptoolkit.logging.Logger] that emitted it, verbatim.
 * @property message the materialized message text.
 * @property throwableText the recorded `Throwable`'s stack trace, or `null` if the event carried
 *   none. Captured as text at record time rather than holding the `Throwable` itself, so a record
 *   never keeps an exception's captured frames — and everything they reference — alive.
 * @property elapsedMillis milliseconds between the owning [LogOverlayState]'s construction and this
 *   event. Measured with a monotonic time source, so it is unaffected by wall-clock changes and is
 *   deliberately *not* a timestamp you can correlate with anything outside the process.
 */
public data class LogRecord(
    public val id: Long,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val throwableText: String?,
    public val elapsedMillis: Long,
)
