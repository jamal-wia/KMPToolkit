package io.github.jamal_wia.kmptoolkit.logging.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jamal_wia.kmptoolkit.logging.LogLevel

/**
 * Draws [content] and, while [LogOverlayState.isVisible] holds, the log panel on top of it.
 *
 * Wrap your app's UI once, as high in the tree as you can:
 *
 * ```kotlin
 * LogOverlayHost(state = overlayState) { AppNavigation() }
 * ```
 *
 * The panel covers the whole area the host occupies and swallows touches on itself, so the content
 * underneath is not interactive while the panel is open — the point being to read logs, not to use
 * the app through them. Hide it with the close button or [LogOverlayState.hide].
 *
 * **Only call this in a debug build.** In release, call `content()` directly; see
 * `docs/kmptoolkit-logging-overlay/01-overview.md`.
 *
 * @param state the buffer to display. Create and own it outside composition.
 * @param modifier applied to the box wrapping [content] and the panel.
 * @param labels the overlay's own chrome text; every string is replaceable.
 * @param content your UI.
 */
@Composable
public fun LogOverlayHost(
    state: LogOverlayState,
    modifier: Modifier = Modifier,
    labels: LogOverlayLabels = LogOverlayLabels(),
    content: @Composable () -> Unit,
) {
    val isVisible: Boolean by state.isVisible.collectAsState()
    Box(modifier = modifier.fillMaxSize()) {
        content()
        if (isVisible) {
            LogOverlayPanel(
                state = state,
                modifier = Modifier.fillMaxSize(),
                labels = labels,
            )
        }
    }
}

/**
 * The log panel on its own, without the [LogOverlayHost] wrapper.
 *
 * Use it when you want to place the list yourself — inside your own bottom sheet, a dedicated
 * developer screen, or a split pane — instead of overlaying the whole app. It ignores
 * [LogOverlayState.isVisible]: showing or hiding it is then your composition's decision.
 *
 * @param state the buffer to display.
 * @param modifier applied to the panel surface.
 * @param labels the overlay's own chrome text; every string is replaceable.
 */
@Composable
public fun LogOverlayPanel(
    state: LogOverlayState,
    modifier: Modifier = Modifier,
    labels: LogOverlayLabels = LogOverlayLabels(),
) {
    val records: List<LogRecord> by state.records.collectAsState()
    // Which record's stack trace is expanded. Deliberately composition-local rather than part of
    // LogOverlayState: it is a view detail, and two panels showing the same buffer should be able
    // to expand different rows.
    val expandedId: MutableState<Long?> = remember { mutableStateOf(null) }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${labels.title} (${records.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    if (records.isNotEmpty()) {
                        TextButton(onClick = state::clear) {
                            Text(text = labels.clear, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = state::hide) {
                        Text(text = labels.close)
                    }
                }
            }

            HorizontalDivider()

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(EMPTY_STATE_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labels.empty,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn {
                    // Newest first: the reason to open this panel is almost always the thing that
                    // just happened.
                    items(items = records.asReversed(), key = LogRecord::id) { record ->
                        LogRecordRow(
                            record = record,
                            isExpanded = expandedId.value == record.id,
                            onClick = {
                                expandedId.value = if (expandedId.value == record.id) null else record.id
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = HORIZONTAL_PADDING))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRecordRow(record: LogRecord, isExpanded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(LEVEL_DOT_SIZE)
                    .background(color = record.level.indicatorColor(), shape = CircleShape),
            )
            Spacer(modifier = Modifier.width(LEVEL_DOT_GAP))
            Column {
                Text(
                    text = "[${record.tag}] ${record.message}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${record.level.name} · ${formatElapsed(record.elapsedMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val throwableText: String? = record.throwableText
        if (isExpanded && throwableText != null) {
            Spacer(modifier = Modifier.height(DETAIL_GAP))
            Text(
                text = throwableText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogLevel.indicatorColor(): Color = when (this) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.INFO -> MaterialTheme.colorScheme.primary
    LogLevel.DEBUG, LogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Renders an elapsed duration as `+12s` or `+3m 07s`. A format, not copy — there is nothing here to
 * translate, and it stays readable regardless of the app's locale.
 */
private fun formatElapsed(millis: Long): String {
    val totalSeconds: Long = millis / MILLIS_PER_SECOND
    val minutes: Long = totalSeconds / SECONDS_PER_MINUTE
    val seconds: Long = totalSeconds % SECONDS_PER_MINUTE
    return if (minutes > 0) "+${minutes}m ${seconds.toString().padStart(2, '0')}s" else "+${seconds}s"
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

private val HORIZONTAL_PADDING = 16.dp
private val ROW_VERTICAL_PADDING = 10.dp
private val EMPTY_STATE_HEIGHT = 120.dp
private val LEVEL_DOT_SIZE = 10.dp
private val LEVEL_DOT_GAP = 10.dp
private val DETAIL_GAP = 8.dp
