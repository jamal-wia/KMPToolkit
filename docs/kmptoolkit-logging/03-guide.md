# kmptoolkit-logging — Guide

Scenarios, from the one you need on day one to the ones you hit later.

## One factory, many loggers

A `LoggerFactory` is the configuration; a `Logger` is a tag bound to it. Create the factory once,
ask it for a logger wherever you log:

```kotlin
class SyncEngine(loggerFactory: LoggerFactory) {
    private val log: Logger = loggerFactory.logger("Sync")
}
```

`logger(tag)` returns a new instance each call — the type holds two immutable references and nothing
else, so there is no cache to hit and no shared state between two loggers with the same tag. Keep
one in a property anyway, for the same reason you would keep any collaborator: so the tag is spelled
once.

Note what is *not* here: no global `Log.d(...)`. The factory travels through your object graph like
any other dependency. That is what lets a library and the app embedding it log at different levels
into different destinations without either one reaching into a process-wide singleton the other also
owns.

## Choosing a level

`createLoggerFactory(minLevel = ...)` sets the floor; everything at or above it reaches the sinks.
A typical split:

```kotlin
val loggerFactory: LoggerFactory = createLoggerFactory(
    minLevel = if (isDebugBuild) LogLevel.DEBUG else LogLevel.WARN,
)
```

`VERBOSE` is for tracing you would not want on even in a debug build by default — it exists so that
a developer chasing one problem can drop the floor for a session without deleting anything.

## Why the message is a lambda

```kotlin
log.d { "resolved ${items.size} items for ${request.id}" }
```

The string is built only if the event passes the threshold. That is what makes it safe to leave
debug logging in shipped code: at `minLevel = WARN` the line above costs a level comparison plus,
because this lambda captures `userId`, one `Function0` allocation — but not the concatenation. A
non-capturing lambda is a singleton and costs only the comparison.

It does **not** make the *arguments* free. This is still evaluated in full every call:

```kotlin
log.d { summarize(items) }        // summarize() runs only if DEBUG passes — fine
log.d { "count=" + expensive() }  // expensive() runs only if DEBUG passes — also fine
val summary = summarize(items)    // WRONG: runs unconditionally
log.d { summary }
```

For work that is expensive beyond building the message — walking a large collection, serializing a
payload — guard it explicitly:

```kotlin
if (log.isLoggable(LogLevel.DEBUG)) {
    val report: String = items.joinToString { it.diagnose() }
    log.d { report }
}
```

## Writing a sink

`LogSink` is a `fun interface`, so a sink is a lambda:

```kotlin
val memorySink = LogSink { level, tag, message, throwable ->
    buffer += "$level/$tag: $message" + (throwable?.let { " (${it::class.simpleName})" } ?: "")
}
```

Install as many as you want; each receives every event that passes the threshold, in list order:

```kotlin
val loggerFactory: LoggerFactory = createLoggerFactory(
    minLevel = LogLevel.DEBUG,
    sinks = listOf(platformLogSink(), memorySink),
)
```

The message lambda is evaluated once per event no matter how many sinks are installed.

Three rules for a sink implementation:

1. **Do not block.** The sink runs synchronously on the thread that logged. If the destination is
   slow — a file, a network call — enqueue and return.
2. **Be thread-safe.** The logger is; a sink shared across threads must be too. The `memorySink`
   above is *not*, and would need a lock or a concurrent buffer in real use.
3. **Do not log from a sink** through the same factory. There is no re-entrancy guard, and a sink
   that logs into itself recurses until the stack ends.

## Bridging to an existing logging framework

This module ships no adapters on purpose — each one is a few lines you keep in your own app, where
the version of the target library is your choice, not the toolkit's.

Timber (Android):

```kotlin
val timberSink = LogSink { level, tag, message, throwable ->
    val priority: Int = when (level) {
        LogLevel.VERBOSE -> Log.VERBOSE
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }
    Timber.tag(tag).log(priority, throwable, message)
}
```

Kermit (multiplatform):

```kotlin
val kermitSink = LogSink { level, tag, message, throwable ->
    val severity: Severity = when (level) {
        LogLevel.VERBOSE -> Severity.Verbose
        LogLevel.DEBUG -> Severity.Debug
        LogLevel.INFO -> Severity.Info
        LogLevel.WARN -> Severity.Warn
        LogLevel.ERROR -> Severity.Error
    }
    co.touchlab.kermit.Logger.log(severity, tag, throwable, message)
}
```

A crash reporter usually wants only the top of the range, which is a filter inside the sink:

```kotlin
val crashSink = LogSink { level, tag, message, throwable ->
    if (level >= LogLevel.WARN) {
        crashReporter.recordNonFatal(tag, message, throwable)
    }
}
```

Sinks compose, so the usual production setup is `listOf(platformLogSink(), crashSink)` — everything
to the console, the serious half also to the reporter.

## A sink that fails

A logging call is not part of the work the caller is trying to do, so a broken destination must not
turn into a crash somewhere unrelated. If a sink throws:

- the throwable is swallowed;
- the sinks after it in the list still receive the event;
- the caller returns normally.

There is deliberately no "report the failure somewhere" behavior, because everywhere it could be
reported to is itself a sink.

The mirror image is *not* true: if your **message lambda** throws, the exception propagates to you.
That is a defect in the calling code — a null-hostile `toString()`, an index computed wrong — and
hiding it inside the logger would make it unfindable.

## Turning logging off

Pass no sinks:

```kotlin
val loggerFactory: LoggerFactory = createLoggerFactory(sinks = emptyList())
```

Every `isLoggable` returns `false`, no message lambda is ever evaluated, and call sites do not
change. This is the supported off switch; there is no separate `LogLevel.NONE`, because a level that
is legal as a threshold but impossible as an event is a trap in one of the two positions.

For a single collaborator rather than the whole app, `NoopLogger` does the same for one injection
point:

```kotlin
class Parser(private val log: Logger = NoopLogger)
```

## Reconfiguring at runtime

You cannot — by design. A factory's level and sinks are fixed at construction, so no log call can
race a reconfiguration. If you need a runtime toggle (a hidden developer switch, say), keep the
mutable part in your own code and make the *sink* the thing that reads it:

```kotlin
val gatedSink = LogSink { level, tag, message, throwable ->
    if (developerSettings.loggingEnabled) delegate.log(level, tag, message, throwable)
}
```

Build the factory at the lowest level you might ever want, and let the sink decide. The cost is that
message lambdas are evaluated even while the gate is closed — which is exactly the trade you are
asking for.

## Timing a block

```kotlin
val page: Page = log.logTimed("renderPage") { renderer.render(index) }
```

Logs `renderPage [12.4ms]` at `DEBUG` (pass `level` for another) and returns the block's result
unchanged. The measurement runs whether or not the event passes the threshold, so the number is
real; an exception from the block propagates without a log line.

It is a debugging convenience, not instrumentation — if you want percentiles across sessions, emit a
metric, don't parse logs.

## Testing code that logs

Two options, depending on what the test is about.

**The test doesn't care about logging** — pass a disabled factory, so nothing pollutes the test
output:

```kotlin
val repository = UserRepository(createLoggerFactory(sinks = emptyList()))
```

**The test asserts on logging** — because a warning on a fallback path is part of the behavior you
promised — record into a list:

```kotlin
class RecordingSink : LogSink {
    val events: MutableList<String> = mutableListOf()
    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        events += "$level/$tag: $message"
    }
}

@Test
fun `falling back to cache is logged as a warning`() {
    val sink = RecordingSink()
    val repository = UserRepository(createLoggerFactory(LogLevel.VERBOSE, listOf(sink)))

    repository.load(offline = true)

    assertEquals(listOf("WARN/UserRepository: network unavailable, serving cache"), sink.events)
}
```

Note `LogLevel.VERBOSE` in the test factory: assert against a threshold that lets everything
through, or you are testing your test's configuration.

This module ships no `-testing` artifact — the fixture above is six lines and every codebase wants
it shaped slightly differently.

## Common mistakes

| Mistake | What happens | Fix |
|---|---|---|
| Building the message outside the lambda | The cost is paid even when filtered out | Move the interpolation inside `{ }` |
| One factory per class | Each gets its own configuration; changing the level misses some | One factory, `logger(tag)` per class |
| A slow sink | Every logging call blocks the caller | Enqueue inside the sink |
| Logging from inside a sink | Unbounded recursion | Never route a sink's own diagnostics through the same factory |
| Logging PII | It ends up in logcat, and possibly in a crash reporter | Log ids and structure, never emails, phone numbers, tokens, or message bodies |
| Asserting on logs at the production threshold | The test passes or fails depending on build configuration | Use `LogLevel.VERBOSE` in the test factory |

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — what `platformLogSink()` does per platform
