# kmptoolkit-logging — Platform notes

Everything in this module is common Kotlin except one declaration: `platformLogSink()`, the default
console sink (and four internal helpers for the JVM-only facts of exceptions and interrupts). This
page is what differs behind it.

## Permissions and manifest entries

**None.** The module declares no Android permission and needs no `Info.plist` entry; the desktop JVM
needs nothing at all. Consistent with
[`../01-architecture.md`](../01-architecture.md#android-manifests), a library manifest here adds
nothing to a consumer's merged manifest.

## Android

`platformLogSink()` writes to logcat through `android.util.Log`, mapping the level onto the priority
of the same name and passing the `Throwable` through so logcat renders a real stack trace instead of
a `toString()`:

| `LogLevel` | Call |
|---|---|
| `VERBOSE` | `Log.v(tag, message, throwable)` |
| `DEBUG` | `Log.d(tag, message, throwable)` |
| `INFO` | `Log.i(tag, message, throwable)` |
| `WARN` | `Log.w(tag, message, throwable)` |
| `ERROR` | `Log.e(tag, message, throwable)` |

Things to know:

- **A chain with an `UnknownHostException` is rendered by the module, not by logcat.**
  `android.util.Log.getStackTraceString` returns an empty string for any throwable that has an
  `UnknownHostException` anywhere in its cause chain, so a plain `Log.e(tag, message, throwable)`
  prints the message and no trace. For such a throwable the sink appends
  `throwable.stackTraceToString()` (the whole chain, causes included) to the message on its own
  lines and passes no `Throwable` to `android.util.Log`; every other throwable is passed through
  unchanged. The whole chain is searched however long it is, and a cyclic chain (a throwable that is
  its own indirect cause, which `initCause` can build) is rendered the same way, because
  `android.util.Log.getStackTraceString` never returns on a cycle and `stackTraceToString()` prints
  `CIRCULAR REFERENCE` for the repeated throwable and stops. The trace is then part of the message
  text, so logcat shows it on several lines and, like any long message, it is subject to logcat's
  per-entry length limit.
- **Logcat has its own filter.** `android.util.Log` applies the device's per-tag log level on top of
  this module's `minLevel`. An event this module passes can still be dropped by the platform —
  historically `VERBOSE` and `DEBUG` were suppressed unless enabled with
  `adb shell setprop log.tag.<tag> DEBUG`. If a line you expect is missing, check there before
  suspecting the sink.
- **Tag length.** Before API 26 the platform truncated tags longer than 23 characters. `minSdk` here
  is 24, so two API levels are affected; keep tags short if you support them.
- **Rate limiting.** Logcat's buffer is a fixed-size ring and chatty logging drops *other* apps'
  lines as well as your own. A `VERBOSE` line inside a per-frame loop is a real cost, even though
  building its message is not.
- **`android.util.Log` is not available on a plain JVM.** In a local unit test without Robolectric,
  calling it throws `"not mocked"`. That is a property of the Android sink only: the desktop `jvm`
  target has its own sink over `System.err` (see [Desktop JVM](#desktop-jvm)). Tests should use their
  own sink or an empty sink list — this module's common tests never touch `platformLogSink()` for
  exactly this reason. The tests that
  create it are the Android host tests (`PlatformLogSinkStackTraceTest`, `PlatformLogSinkLongChainTest`),
  which run under Robolectric, where `android.util.Log` is a working shadow that records what the sink
  passes to it, and the iOS tests: `PlatformLogSinkIosChainTest` calls it and checks only that it
  returns, `PlatformLogSinkIosConcurrencyTest` redirects standard output to a file and reads what it wrote.

## iOS

`platformLogSink()` writes to standard output with `println`:

```
D/UserRepository: loading user 42
```

The prefix is the level's initial (`V`, `D`, `I`, `W`, `E`), then the tag, then the message. A
non-null `Throwable` is followed by `throwable.stackTraceToString()` on its own lines.

Each event, its line and its stack trace, is written in one step under a lock shared by every
instance of the sink, so loggers on several threads never glue lines together or separate a trace
from its event. Anything else that writes to standard output (`println` elsewhere in the app,
`NSLog`) does not take that lock, so its output is not ordered against the sink's.

**Why `println` and not `NSLog` or `os_log`:**

- `NSLog` takes a C format string. Passing a caller-supplied message through it means either
  escaping every `%` or risking a stray `%s` being interpreted as a format specifier — a
  correctness and safety problem in a library that has no idea what its consumer will log.
- `os_log` would mean an interop dependency in a module whose entire selling point is having none.

The consequence: output goes to the Xcode debug console, and it is **not** captured by the unified
logging system — so it does not appear in Console.app, is not retained on device, and is not
collected in a sysdiagnose. If you need any of that, write a sink over `os_log` in your iOS layer
and install it alongside (or instead of) the platform one:

```kotlin
createLoggerFactory(minLevel = LogLevel.INFO, sinks = listOf(osLogSink))
```

The sink interface exists precisely so this stays your decision rather than the library's.

## Desktop JVM

The `jvm` target is real, not a stub. The suite publishes it because `Logger` is a `commonMain`
parameter of other modules, so a consumer's shared code that also builds for desktop would not
compile without it (the bar for a desktop target is in
[`../01-architecture.md`](../01-architecture.md#desktop-targets)). It adds no dependency.

`platformLogSink()` writes to standard error (`System.err`) with the same shape as the iOS sink:

```
D/UserRepository: loading user 42
```

The prefix is the level's initial (`V`, `D`, `I`, `W`, `E`), then the tag, then the message. A
non-null `Throwable` is followed by `throwable.stackTraceToString()` on its own lines, the cause
chain included, with no blank line after it. The platform's line separator is used between the
line and the trace.

Things to know:

- **Standard error, not standard output.** A desktop app that pipes its output elsewhere keeps its
  log lines out of that data stream. `System.err` is read at every event, so an app that redirects
  it with `System.setErr` is followed.
- **One write per event, no lock of the sink's own.** Each event, its line and its stack trace, is
  written with a single `print(String)` call on standard error, so loggers on several threads never
  glue lines together or separate a trace from its event. When standard error is the JDK's own
  `PrintStream`, that call is one write under the stream's own lock. When an app has replaced it with
  a subclass that hands a text on line by line (a line-buffering or capturing stream; Gradle's test
  worker installs one), the sink holds that stream's own monitor for the call, so the lines of two
  events do not interleave there either. That monitor is the one `Throwable.printStackTrace` holds on
  such a stream while it runs the throwable's `getMessage`, so there is only one lock and a throwable
  whose getter logs does not deadlock against a concurrent event. The sink takes no lock of its own,
  because a second lock taken before the stream's would. Output written to standard error by anything
  else (`System.err` elsewhere, a logging framework) is not ordered against the sink's events. A
  `message`, `toString` or `cause` override never runs while the sink's own text is being written.
- **A chain with an `UnknownHostException` is rendered by the module, not by logcat.**
  `android.util.Log.getStackTraceString` returns an empty string for any throwable that has an
  `UnknownHostException` anywhere in its cause chain, so a plain `Log.e(tag, message, throwable)`
  prints the message and no trace. For such a throwable the sink appends
  `throwable.stackTraceToString()` (the whole chain, causes included) to the message on its own
  lines and passes no `Throwable` to `android.util.Log`; every other throwable is passed through
  unchanged. The whole chain is searched however long it is, and a cyclic chain (a throwable that is
  its own indirect cause, which `initCause` can build) is rendered the same way, because
  `android.util.Log.getStackTraceString` never returns on a cycle and `stackTraceToString()` prints
  `CIRCULAR REFERENCE` for the repeated throwable and stops. The trace is then part of the message
  text, so logcat shows it on several lines and, like any long message, it is subject to logcat's
  per-entry length limit.
- **Logcat has its own filter.** `android.util.Log` applies the device's per-tag log level on top of
  this module's `minLevel`. An event this module passes can still be dropped by the platform —
  historically `VERBOSE` and `DEBUG` were suppressed unless enabled with
  `adb shell setprop log.tag.<tag> DEBUG`. If a line you expect is missing, check there before
  suspecting the sink.
- **Tag length.** Before API 26 the platform truncated tags longer than 23 characters. `minSdk` here
  is 24, so two API levels are affected; keep tags short if you support them.
- **Rate limiting.** Logcat's buffer is a fixed-size ring and chatty logging drops *other* apps'
  lines as well as your own. A `VERBOSE` line inside a per-frame loop is a real cost, even though
  building its message is not.
- **`android.util.Log` is not available on a plain JVM.** In a local unit test without Robolectric,
  calling it throws `"not mocked"`. That is a property of the Android sink only: the desktop `jvm`
  target has its own sink over `System.err` (see [Desktop JVM](#desktop-jvm)). Tests should use their
  own sink or an empty sink list — this module's common tests never touch `platformLogSink()` for
  exactly this reason. The tests that
  create it are the Android host tests (`PlatformLogSinkStackTraceTest`, `PlatformLogSinkLongChainTest`),
  which run under Robolectric, where `android.util.Log` is a working shadow that records what the sink
  passes to it, and the iOS tests: `PlatformLogSinkIosChainTest` calls it and checks only that it
  returns, `PlatformLogSinkIosConcurrencyTest` redirects standard output to a file and reads what it wrote.

## iOS

`platformLogSink()` writes to standard output with `println`:

```
D/UserRepository: loading user 42
```

The prefix is the level's initial (`V`, `D`, `I`, `W`, `E`), then the tag, then the message. A
non-null `Throwable` is followed by `throwable.stackTraceToString()` on its own lines.

Each event, its line and its stack trace, is written in one step under a lock shared by every
instance of the sink, so loggers on several threads never glue lines together or separate a trace
from its event. Anything else that writes to standard output (`println` elsewhere in the app,
`NSLog`) does not take that lock, so its output is not ordered against the sink's.

**Why `println` and not `NSLog` or `os_log`:**

- `NSLog` takes a C format string. Passing a caller-supplied message through it means either
  escaping every `%` or risking a stray `%s` being interpreted as a format specifier — a
  correctness and safety problem in a library that has no idea what its consumer will log.
- `os_log` would mean an interop dependency in a module whose entire selling point is having none.

The consequence: output goes to the Xcode debug console, and it is **not** captured by the unified
logging system — so it does not appear in Console.app, is not retained on device, and is not
collected in a sysdiagnose. If you need any of that, write a sink over `os_log` in your iOS layer
and install it alongside (or instead of) the platform one:

```kotlin
createLoggerFactory(minLevel = LogLevel.INFO, sinks = listOf(osLogSink))
```

The sink interface exists precisely so this stays your decision rather than the library's.

## Desktop JVM

The `jvm` target is real, not a stub. The suite publishes it because `Logger` is a `commonMain`
parameter of other modules, so a consumer's shared code that also builds for desktop would not
compile without it (the bar for a desktop target is in
[`../01-architecture.md`](../01-architecture.md#desktop-targets)). It adds no dependency.

`platformLogSink()` writes to standard error (`System.err`) with the same shape as the iOS sink:

```
D/UserRepository: loading user 42
```

The prefix is the level's initial (`V`, `D`, `I`, `W`, `E`), then the tag, then the message. A
non-null `Throwable` is followed by `throwable.stackTraceToString()` on its own lines, the cause
chain included, with no blank line after it. The platform's line separator is used between the
line and the trace.

Things to know:

- **Standard error, not standard output.** A desktop app that pipes its output elsewhere keeps its
  log lines out of that data stream. `System.err` is read at every event, so an app that redirects
  it with `System.setErr` is followed.
- **One write per event, no lock of the sink's own.** Each event, its line and its stack trace, is
  written with a single `print(String)` call on standard error, so loggers on several threads never
  glue lines together or separate a trace from its event: that call is one write under the stream's
  own lock. The sink takes no second lock on purpose, because `Throwable.printStackTrace` holds the
  stream's lock while it runs the throwable's `getMessage`, and a throwable whose getter logs would
  otherwise deadlock against a concurrent event. Output written to standard error by anything else
  (`System.err` elsewhere, a logging framework) is not ordered against the sink's events. A `message`,
  `toString` or `cause` override never runs while the sink's own text is being written.
- **A chain with an `UnknownHostException` is rendered in full**, unlike on Android: the JDK's own
  stack trace rendering has no such rule. A cyclic cause chain prints `CIRCULAR REFERENCE` for the
  repeated throwable and stops.
- **Interrupts behave as on Android.** Writing to `System.err` does not clear the caller's
  interrupt flag. A sink's `InterruptedException` is contained by the logger and the flag is set
  again afterwards, exactly as described for the logger in [`04-api-reference.md`](04-api-reference.md).
- **No files, rotation or framework bridge.** For those, write a [`LogSink`](04-api-reference.md)
  over your logging framework and install it instead of, or next to, the platform one.

The tests are the desktop `jvmTest` suite: `PlatformLogSinkJvmTest` redirects `System.err` to a
buffer of its own, checks the rendered text (levels, cause chain, `UnknownHostException`, cycle),
races several writers on separate sink instances against a stream whose write is deliberately not
atomic, and covers the interrupt and `LinkageError` containment of the JVM actuals.

## Limitation: a throwable that cannot render itself (every platform sink)

A throwable whose `toString()` or `getCause()` throws costs the whole event on that platform sink,
header and message included, not only the trace; the logger contains the error (for the failures it
contains, see the `LogSink` KDoc) and the other sinks still get the event.

## Behavior that is identical on every platform

Everything else: level filtering, lazy message evaluation, fan-out order across sinks, the
containment of a throwing sink, and the propagation of a throwing message lambda. All of it lives
in common code and is covered by one shared test suite that runs on every target.

Thread safety is a design property rather than a tested one: a `Logger` holds only immutable
references (its tag, threshold, and a defensively copied sink list), so there is no mutable state
to race on. What that guarantee does **not** cover is your own `LogSink` — if it accumulates
state, making it thread-safe is your responsibility.
