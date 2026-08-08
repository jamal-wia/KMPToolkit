# kmptoolkit-logging — API reference

Package: `io.github.jamal_wia.kmptoolkit.logging`

This file mirrors the committed ABI dump at `kmptoolkit-logging/api/`. If they disagree, the dump is
authoritative and this file is a bug.

## `enum class LogLevel`

Severity of one event, ordered least to most severe. The declaration order **is** the severity
order, and the threshold check is a plain `level >= minLevel`, so the ordering is part of the public
contract (`LogLevelTest` pins it).

| Entry | Meaning |
|---|---|
| `VERBOSE` | Fine-grained tracing, normally off outside local debugging |
| `DEBUG` | Developer-facing detail about normal operation |
| `INFO` | A notable, expected event worth keeping in a release log |
| `WARN` | Something recoverable that should not have happened |
| `ERROR` | A failure — usually paired with the `Throwable` that caused it |

There is deliberately no `NONE`/`OFF` entry: "log nothing" is expressed by installing no sinks
(see [`createLoggerFactory`](#fun-createloggerfactory)), so every entry here is a level an event can
actually be emitted at.

## `fun interface LogSink`

```kotlin
public fun interface LogSink {
    public fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}
```

A destination for events. The module's only extension point.

| Parameter | Contract |
|---|---|
| `level` | The severity the event was emitted at. Already past the logger's threshold |
| `tag` | The emitting `Logger`'s tag, verbatim — including an empty string if that is what was asked for |
| `message` | The **materialized** message; the lazy lambda has already run |
| `throwable` | The error the event describes, or `null` |

**Contract for implementors:**

- Called synchronously, on the thread that logged, inside the caller's stack. Do not block; hand off
  to your own queue if the destination is slow.
- Must be thread-safe — the `Logger` calling it is.
- Must not log through the same `LoggerFactory`: there is no re-entrancy guard.
- May throw. The logger contains it — see [`createLoggerFactory`](#fun-createloggerfactory).

Being a `fun interface`, a sink is usually a lambda:
`LogSink { level, tag, message, throwable -> ... }`.

## `expect fun platformLogSink(): LogSink`

```kotlin
public expect fun platformLogSink(): LogSink
```

The platform's own console sink — `android.util.Log` on Android, `println` on iOS. The returned sink
is stateless, so **identity is unspecified**: a call may return a fresh instance or a shared one,
and it differs by target (Kotlin/Native collapses the non-capturing lambda into a singleton, the JVM
does not). Never compare the result by identity or use it as a map key. See
[`05-platform-notes.md`](05-platform-notes.md) for the per-platform output format and its limits.

## `interface Logger`

```kotlin
public interface Logger {
    public val tag: String
    public fun isLoggable(level: LogLevel): Boolean
    public fun log(level: LogLevel, throwable: Throwable?, message: () -> String)
}
```

The type your code depends on. Obtain one from a [`LoggerFactory`](#interface-loggerfactory).

| Member | Contract |
|---|---|
| `tag` | The tag stamped onto every event this logger emits |
| `isLoggable(level)` | `true` if an event at `level` would reach at least one sink. Use it to guard work more expensive than building the message |
| `log(level, throwable, message)` | Emits one event. `message` is evaluated **only** if `isLoggable(level)` holds, and then **at most once** regardless of sink count |

Implementations returned by this module are immutable and thread-safe.

`throwable` has no default here — an interface method's default argument cannot be overridden
meaningfully, and the [extensions](#logging-extensions) below supply the ergonomic defaults. Call
`log` directly only when you hold a `LogLevel` in a variable.

## Logging extensions

Extension functions on `Logger`, one import each.

| Signature | Emits at |
|---|---|
| `fun Logger.v(message: () -> String)` | `VERBOSE`, no throwable |
| `fun Logger.d(message: () -> String)` | `DEBUG`, no throwable |
| `fun Logger.i(message: () -> String)` | `INFO`, no throwable |
| `fun Logger.w(throwable: Throwable? = null, message: () -> String)` | `WARN` |
| `fun Logger.e(throwable: Throwable? = null, message: () -> String)` | `ERROR` |

Only `w` and `e` take a `Throwable`: an error attached to a debug or info line is almost always a
sign the line should have been a warning.

### `fun <T> Logger.logTimed(label: String, level: LogLevel = LogLevel.DEBUG, block: () -> T): T`

Runs `block`, then logs `"<label> [<duration>]"` at `level`, and returns `block`'s result unchanged.

- The measurement is **unconditional** — only the resulting event is filtered — so the reported
  duration is real even when the logger later drops the line.
- `block` runs exactly once.
- An exception from `block` propagates, and no event is logged.

A debugging convenience, not instrumentation: for numbers you intend to track over time, emit a
metric instead of parsing logs.

## `object NoopLogger : Logger`

A logger that discards everything.

| Member | Value |
|---|---|
| `tag` | `""` |
| `isLoggable(level)` | `false` at every level |
| `log(...)` | Does nothing; never evaluates `message` |

Intended as a default parameter value — `class Parser(private val log: Logger = NoopLogger)` — so a
consumer can opt into logging without every call site null-checking. `logTimed` on it still runs the
block and returns its result; only the log line disappears.

## `interface LoggerFactory`

```kotlin
public interface LoggerFactory {
    public fun logger(tag: String): Logger
}
```

Creates loggers that share one threshold and one sink list. This is the object an app configures at
startup and injects; there is no global logger state in this module.

`logger(tag)` returns a **new** instance per call — there is no per-tag cache, because a logger holds
only immutable references. Two loggers with the same tag are indistinguishable in behavior.

## `fun createLoggerFactory(...)`

```kotlin
public fun createLoggerFactory(
    minLevel: LogLevel = LogLevel.DEBUG,
    sinks: List<LogSink> = listOf(platformLogSink()),
): LoggerFactory
```

| Parameter | Default | Meaning |
|---|---|---|
| `minLevel` | `LogLevel.DEBUG` | Lowest severity that reaches the sinks |
| `sinks` | one `platformLogSink()` | Destinations, invoked in list order |

Guaranteed behavior of the returned factory's loggers:

| Situation | Behavior |
|---|---|
| `level >= minLevel` and `sinks` non-empty | `isLoggable` is `true`; the message is built once and passed to every sink in order |
| `level < minLevel` | Nothing is delivered; the message lambda is never evaluated |
| `sinks` is empty | Logging is off: `isLoggable` is `false` at every level, no lambda is evaluated. This is the supported off switch |
| A sink throws | The throwable is swallowed, the remaining sinks still receive the event, and the caller returns normally |
| The message lambda throws | The exception propagates to the caller and no sink is called — a caller-side defect must stay visible |
| `sinks` mutated after the call | No effect; the list is copied at construction |

The configuration is fixed at construction — there is no `setMinLevel`/`addSink`, so no log call can
race a reconfiguration. Reconfiguring means building another factory, or gating inside a sink (see
[`03-guide.md`](03-guide.md#reconfiguring-at-runtime)).

## Thread safety summary

| Type | Guarantee |
|---|---|
| `LoggerFactory` / `Logger` from `createLoggerFactory` | Immutable; safe to share across threads |
| `NoopLogger` | Stateless |
| `platformLogSink()` | Stateless; the underlying platform APIs are themselves thread-safe |
| Your own `LogSink` | **Your** responsibility |
