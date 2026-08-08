# kmptoolkit-logging-overlay — Guide

Scenarios beyond the five-minute setup in [`02-getting-started.md`](02-getting-started.md).

## Keeping the overlay out of release builds

Say it once, in the place that builds your logging:

```kotlin
object AppLogging {
    val overlayState: LogOverlayState? = if (BuildConfig.DEBUG) LogOverlayState() else null

    val factory: LoggerFactory = createLoggerFactory(
        sinks = listOfNotNull(platformLogSink(), overlayState?.asLogSink()),
    )
}
```

A `null` state in release means the composable cannot be called at all, which is stronger than a
runtime flag: there is nothing to accidentally show. At the UI:

```kotlin
val state: LogOverlayState? = AppLogging.overlayState
if (state != null) LogOverlayHost(state = state) { AppContent() } else AppContent()
```

R8/ProGuard will also see the branch as dead in release and drop it.

## Showing less on screen than you log

The overlay has its own threshold, independent of the factory's. Use it when logcat should stay
verbose but the panel should only surface things that went wrong:

```kotlin
val overlayState = LogOverlayState(minLevel = LogLevel.WARN)
val factory = createLoggerFactory(
    minLevel = LogLevel.DEBUG,                       // logcat gets DEBUG and up
    sinks = listOf(platformLogSink(), overlayState.asLogSink()),  // the panel gets WARN and up
)
```

An event below the overlay's `minLevel` is dropped before it is recorded — it costs no memory and
does not consume a record id.

Filtering by *tag* is not built in. If you need it, wrap the sink:

```kotlin
val noisyTags = setOf("Render", "Touch")
val filtered = LogSink { level, tag, message, throwable ->
    if (tag !in noisyTags) overlayState.record(level, tag, message, throwable)
}
```

## Sizing the buffer

`maxRecords` defaults to 200 — roughly a screen's worth of history, small enough not to think about.
Change it when your situation actually differs:

```kotlin
LogOverlayState(maxRecords = 1000)   // a long tester session you will scroll back through
LogOverlayState(maxRecords = 30)     // a kiosk device where memory is tight and only "just now" matters
```

Eviction is FIFO: at capacity, appending record *n+1* drops the oldest. There is no way to pin a
record, and an `ERROR` is evicted exactly like a `VERBOSE` — raise `minLevel` instead if errors are
what you want to keep.

Records are held as text, including a `Throwable`'s full stack trace. A 1000-record buffer of
exception traces is not free; that is the trade you are making when you raise the bound.

## Placing the panel yourself

`LogOverlayHost` covers your whole UI while visible. When you would rather put the list inside a
bottom sheet, a dedicated developer screen, or one half of a tablet layout, use `LogOverlayPanel`
directly:

```kotlin
ModalBottomSheet(onDismissRequest = overlayState::hide) {
    LogOverlayPanel(state = overlayState, modifier = Modifier.fillMaxHeight(0.7f))
}
```

`LogOverlayPanel` ignores `isVisible` — whether it is on screen becomes your composition's decision.
Its `Close` button still calls `hide()`, so keep driving your container from `isVisible` and the two
stay consistent.

Two panels may share one state; each keeps its own expanded row.

## Relabeling the chrome

Everything the overlay writes that is not a log record lives in `LogOverlayLabels`:

```kotlin
LogOverlayHost(
    state = overlayState,
    labels = LogOverlayLabels(
        title = stringResource(R.string.dev_logs),
        clear = stringResource(R.string.dev_clear),
        close = stringResource(R.string.dev_close),
        empty = stringResource(R.string.dev_no_logs),
    ),
) { AppContent() }
```

There are exactly four strings, and the defaults are English. The record rows carry no copy at all —
tag, message, level name and stack trace are the data you logged.

One thing is not a label: elapsed time renders as `+12s` / `+3m 07s`. That is a numeric format
rather than translatable copy, and it is deliberately not a wall-clock timestamp — see
[`04-api-reference.md`](04-api-reference.md#data-class-logrecord).

## Triggering it from outside the UI

`show()`, `hide()` and `toggle()` are plain thread-safe calls on a plain object, so anything can
drive them — a debug menu, a hardware key handler, a shake detector, a deep link, an ADB broadcast
receiver in a debug-only source set:

```kotlin
// Android, debug source set only
class ShowLogsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppLogging.overlayState?.show() ?: Unit
}
```

The module deliberately ships none of these: any gesture it chose would collide with some app's real
one, and any receiver it registered would need a manifest entry in every consumer's app.

## Recording without a Logger

`record()` is public, so a code path that has no `Logger` — a crash handler, a native callback, a
platform delegate — can still put something on screen:

```kotlin
overlayState.record(LogLevel.ERROR, tag = "Startup", message = "config missing", throwable = null)
```

It applies the same `minLevel` filter and the same bound as the sink path, because the sink is a
one-line delegate to it.

## Testing against the overlay

`LogOverlayState` is plain Kotlin — no Compose, no dispatcher, no main thread. Assert on
`records.value` directly:

```kotlin
@Test
fun `a failed sync is surfaced to the overlay`() {
    val state = LogOverlayState()
    val factory = createLoggerFactory(sinks = listOf(state.asLogSink()))

    SyncEngine(factory).sync()

    assertEquals(listOf("sync failed"), state.records.value.map(LogRecord::message))
}
```

This also makes the overlay a convenient recording sink in tests that are not about the overlay at
all — though `kmptoolkit-logging`'s own docs show a two-line `LogSink` if all you want is a list.

## Common mistakes

| Mistake | What happens | Fix |
|---|---|---|
| `remember { LogOverlayState() }` | The buffer is discarded on configuration change — exactly when you wanted to read it | Create it at app scope |
| Installing the sink but not `platformLogSink()` | Nothing reaches logcat/`os_log` anymore | Install both |
| Calling `LogOverlayHost` outside `MaterialTheme` | The panel falls back to default Material colors and looks foreign | Wrap inside your theme |
| Expecting logs from before startup | The sink only sees events emitted after it was installed | Install it as early as possible |
| Expecting logs to survive a crash | The buffer is in memory | Use a crash reporter for post-mortem data |
| Shipping it in release | Retained log data painted on a user's screen | Gate on your own debug flag |
