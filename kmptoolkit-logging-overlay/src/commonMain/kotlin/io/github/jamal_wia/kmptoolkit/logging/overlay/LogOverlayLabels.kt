package io.github.jamal_wia.kmptoolkit.logging.overlay

/**
 * Every piece of text the overlay draws that is not a log record.
 *
 * KMPToolkit modules ship no user-facing copy (`docs/01-architecture.md`), and a UI module cannot
 * quite honor that literally — a button with no label is not a button. This type is the compromise:
 * the chrome is reduced to four short English strings, all of them parameters, so relabeling or
 * localizing the whole overlay is one object.
 *
 * ```kotlin
 * LogOverlayHost(
 *     state = overlayState,
 *     labels = LogOverlayLabels(title = stringResource(R.string.dev_logs), clear = "…"),
 * ) { AppContent() }
 * ```
 *
 * The record rows themselves carry no copy: they show the tag, message, level name and stack trace
 * you logged, which is data rather than interface text.
 *
 * @property title heading of the panel. The record count is appended in parentheses.
 * @property clear label of the button that calls [LogOverlayState.clear]. Shown only while at least
 *   one record is retained.
 * @property close label of the button that calls [LogOverlayState.hide].
 * @property empty text shown in place of the list while no record is retained.
 */
public data class LogOverlayLabels(
    public val title: String = "Logs",
    public val clear: String = "Clear",
    public val close: String = "Close",
    public val empty: String = "No records",
)
