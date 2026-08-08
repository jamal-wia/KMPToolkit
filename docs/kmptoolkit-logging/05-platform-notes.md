# kmptoolkit-logging — Platform notes

Everything in this module is common Kotlin except one declaration: `platformLogSink()`, the default
console sink. This page is what differs behind it.

## Permissions and manifest entries

**None.** The module declares no Android permission and needs no `Info.plist` entry. Consistent with
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
  calling it throws `"not mocked"`. Tests should use their own sink or an empty sink list — this
  module's own tests never touch `platformLogSink()` for exactly this reason.

## iOS

`platformLogSink()` writes to standard output with `println`:

```
D/UserRepository: loading user 42
```

The prefix is the level's initial (`V`, `D`, `I`, `W`, `E`), then the tag, then the message. A
non-null `Throwable` is followed by `throwable.stackTraceToString()` on its own lines.

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

## Behavior that is identical on both platforms

Everything else: level filtering, lazy message evaluation, fan-out order across sinks, the
containment of a throwing sink, and the propagation of a throwing message lambda. All of it lives
in common code and is covered by one shared test suite that runs on both targets.

Thread safety is a design property rather than a tested one: a `Logger` holds only immutable
references (its tag, threshold, and a defensively copied sink list), so there is no mutable state
to race on. What that guarantee does **not** cover is your own `LogSink` — if it accumulates
state, making it thread-safe is your responsibility.
