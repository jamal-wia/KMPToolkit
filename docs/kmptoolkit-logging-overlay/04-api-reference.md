# kmptoolkit-logging-overlay — API reference

Package: `io.github.jamal_wia.kmptoolkit.logging.overlay`

This file mirrors the committed ABI dump at `kmptoolkit-logging-overlay/api/`. If they disagree, the
dump is authoritative and this file is a bug.

## `const val DEFAULT_MAX_RECORDS: Int`

```kotlin
public const val DEFAULT_MAX_RECORDS: Int = 200
```

The default value of `LogOverlayState.maxRecords`. Public so a consumer can reason about the bound
without hardcoding the number.

## `class LogOverlayState`

```kotlin
public class LogOverlayState(
    public val maxRecords: Int = DEFAULT_MAX_RECORDS,
    public val minLevel: LogLevel = LogLevel.VERBOSE,
)
```

The overlay's backing store. Plain Kotlin — no Compose types, no dispatcher, no main-thread
requirement — so it is directly unit-testable.

**You create and own it.** Not `remember`ed: it is meant to outlive every composition, so a
configuration change does not discard the history you were about to read.

| Constructor parameter | Contract |
|---|---|
| `maxRecords` | How many records to retain. Must be `>= 1`; otherwise `IllegalArgumentException` at construction |
| `minLevel` | Severity below which an event is dropped instead of recorded. Independent of the `LoggerFactory`'s own threshold |

### Properties

| Property | Type | Contract |
|---|---|---|
| `maxRecords` | `Int` | The bound, as constructed. Immutable — resizing means a new state |
| `minLevel` | `LogLevel` | The threshold, as constructed. Immutable for the same reason |
| `records` | `StateFlow<List<LogRecord>>` | Retained records, **oldest first**, never longer than `maxRecords`. Every emission is a fresh immutable list |
| `isVisible` | `StateFlow<Boolean>` | Whether `LogOverlayHost` draws the panel. Starts `false` |

### `fun record(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)`

Records one event.

- Drops the event outright when `level < minLevel` — no memory, no record id consumed.
- Evicts the **oldest** record when the buffer is already at `maxRecords` (FIFO). No record is
  exempt: an `ERROR` is evicted exactly like a `VERBOSE`.
- `tag` and `message` are stored verbatim, including empty strings.
- `throwable` is converted to text via `stackTraceToString()` **on the calling thread**, and only
  the text is kept. The `Throwable` itself is not retained, so a record never holds an exception's
  captured frames alive.
- Callable from any thread. The append is atomic: concurrent calls neither lose a record nor produce
  a torn list. The relative order of two genuinely concurrent calls is unspecified.

### `fun clear()`

Drops every retained record. Leaves `isVisible` untouched. Id numbering restarts at `1`.

### `fun show()` / `fun hide()` / `fun toggle()`

Set `isVisible` to `true`, `false`, or its negation. Idempotent (`show()` twice stays visible).
Callable from any thread. This module ships no trigger that calls them — see
[`03-guide.md`](03-guide.md#triggering-it-from-outside-the-ui).

### `fun asLogSink(): LogSink`

A [`LogSink`](../kmptoolkit-logging/04-api-reference.md#fun-interface-logsink) that delegates
straight to `record`, so it honors this state's `minLevel` and bound. It never throws, so the
logger's error containment never has to engage.

Each call returns a new instance; all of them feed the same buffer. Install it **alongside**
`platformLogSink()`, not instead of it:

```kotlin
createLoggerFactory(sinks = listOf(platformLogSink(), overlayState.asLogSink()))
```

## `data class LogRecord`

```kotlin
public data class LogRecord(
    public val id: Long,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val throwableText: String?,
    public val elapsedMillis: Long,
)
```

One retained event — an immutable snapshot taken at record time. Nothing in it is recomputed later,
which is what makes it a stable Compose parameter.

| Property | Contract |
|---|---|
| `id` | Recording position, starting at `1`, `+1` per **retained** record. Unique among the records one state currently holds, so it works as a `LazyColumn` key. Increases across eviction; restarts after `clear()`. A filtered-out event does not consume one |
| `level` | The severity the event was recorded at |
| `tag` | The emitting logger's tag, verbatim |
| `message` | The materialized message text, verbatim |
| `throwableText` | The recorded `Throwable`'s stack trace as text, or `null` |
| `elapsedMillis` | Milliseconds between the owning state's construction and this event, from a monotonic time source. Non-negative, non-decreasing within one state. Deliberately **not** a wall-clock timestamp: it cannot be correlated with anything outside the process, and it does not jump when the device clock does |

## `data class LogOverlayLabels`

```kotlin
public data class LogOverlayLabels(
    public val title: String = "Logs",
    public val clear: String = "Clear",
    public val close: String = "Close",
    public val empty: String = "No records",
)
```

Every string the overlay draws that is not a log record. KMPToolkit modules ship no user-facing copy;
a UI module cannot honor that literally, so the chrome is reduced to these four and all of them are
parameters.

| Property | Where it appears |
|---|---|
| `title` | Panel heading, rendered as `"<title> (<record count>)"` |
| `clear` | Button calling `LogOverlayState.clear()`. Shown only while at least one record is retained |
| `close` | Button calling `LogOverlayState.hide()`. Always shown |
| `empty` | Shown in place of the list while no record is retained |

Elapsed time renders as `+12s` / `+3m 07s`. That is a numeric format, not copy, and is not
configurable.

## `@Composable fun LogOverlayHost`

```kotlin
@Composable
public fun LogOverlayHost(
    state: LogOverlayState,
    modifier: Modifier = Modifier,
    labels: LogOverlayLabels = LogOverlayLabels(),
    content: @Composable () -> Unit,
)
```

Draws `content`, and — while `state.isVisible` holds — the log panel on top of it, filling the host.

- Call inside your `MaterialTheme`: the panel uses the theme's `colorScheme` and `typography`.
- While the panel is visible it consumes touches, so the content underneath is not interactive.
- Recomposes on `isVisible` and on the record list; `content` is not affected by either.
- **Debug builds only.** In release, call `content()` directly.

## `@Composable fun LogOverlayPanel`

```kotlin
@Composable
public fun LogOverlayPanel(
    state: LogOverlayState,
    modifier: Modifier = Modifier,
    labels: LogOverlayLabels = LogOverlayLabels(),
)
```

The panel on its own, for placing inside your own bottom sheet, developer screen, or split layout.

- **Ignores `state.isVisible`** — whether it is composed is your decision. Its close button still
  calls `hide()`, so drive your container from `isVisible` and the two stay consistent.
- Renders records **newest first**.
- A row whose record has a `throwableText` expands to show it on tap and collapses on a second tap.
  Which row is expanded is composition-local state, not part of `LogOverlayState`, so two panels
  over the same buffer can expand different rows.

## Level colors

Not configurable, and taken from the ambient `MaterialTheme.colorScheme` so they follow your theme:

| Level | Color role |
|---|---|
| `ERROR` | `error` |
| `WARN` | `tertiary` |
| `INFO` | `primary` |
| `DEBUG`, `VERBOSE` | `onSurfaceVariant` |

## Thread safety, in one table

| Member | Safe off the main thread? |
|---|---|
| `LogOverlayState` constructor | Yes |
| `record`, `clear`, `show`, `hide`, `toggle`, `asLogSink` | Yes |
| `records.value`, `isVisible.value` | Yes |
| `LogOverlayHost`, `LogOverlayPanel` | No — composables, like any other |
