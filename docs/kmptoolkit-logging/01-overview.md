# kmptoolkit-logging — Overview

A four-type logging seam: a `Logger` your shared code calls, a `LogLevel` threshold, a `LogSink`
extension point, and a `LoggerFactory` your app configures once at startup.

## The problem it solves

Shared Kotlin code needs to log, but a *library* that logs must not decide **where**. The moment a
shared module depends on Kermit, Timber, or `os_log`, every app consuming it inherits that choice —
including apps that already standardized on something else, and apps that must route logs into a
crash reporter rather than a console.

This module separates the two halves of that problem:

- **Emitting** an event — a tag, a level, a lazily-built message, an optional `Throwable` — is what
  your code does, against `Logger`.
- **Delivering** it is what a `LogSink` does, and you write or install that.

```kotlin
class SyncEngine(loggerFactory: LoggerFactory) {
    private val log: Logger = loggerFactory.logger("Sync")

    fun sync(itemCount: Int) {
        log.i { "sync started, $itemCount items" }
    }
}
```

The message is a lambda, so a filtered-out event costs a level comparison and — for a lambda that
captures — one allocation, but never the string itself: concatenation for a `VERBOSE` line does
not happen in a release build configured at `INFO`.

The module has **no dependencies at all** beyond the Kotlin standard library. Adding it to a
consumer's graph adds nothing to their app but a handful of classes.

## What this is **not**

- **Not a logging backend.** It does not format timestamps, rotate files, buffer to disk, batch to
  a server, or talk to a crash reporter. It hands a materialized event to whatever sinks you
  installed and stops there. The only sink it ships is `platformLogSink()` — logcat on Android,
  standard output on iOS.
- **Not a wrapper around Kermit/Timber/`os_log`, and not a dependency on them.** No adapter for any
  third-party logger ships here; writing one is a single lambda
  (see [`03-guide.md`](03-guide.md#bridging-to-an-existing-logging-framework)).
- **Not a global logger.** There is no `Log.d(...)` static entry point and no process-wide
  configuration to install. You create a `LoggerFactory` and pass it around, which is what lets two
  independent components — or a library and the app embedding it — hold different configurations
  without fighting over one singleton.
- **Not a runtime-reconfigurable logger.** A factory's level and sinks are fixed at construction.
  Changing them means constructing another factory; there is no `setMinLevel` to race against a
  concurrent log call.
- **Not asynchronous.** A sink is called synchronously on the calling thread. A sink that talks to
  a slow destination is responsible for its own queue.
- **Not a PII filter.** It will faithfully deliver whatever you put in the message. Deciding that
  emails, phone numbers, tokens, and message bodies do not belong in a log is your code's job — the
  library cannot tell a user id from an email address.
- **Not a crash reporter, metrics pipeline, or tracing library.** `logTimed` measures one block and
  logs the duration; it is a debugging convenience, not instrumentation you should build dashboards
  on.

## When to use it

Use it in shared Kotlin code — especially in a library, or in a KMP module consumed by more than
one app — that needs to log without dictating the destination.

If you are writing a single Android app that already uses Timber everywhere and will never share
the code, you do not need this indirection; call Timber directly.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — configuring per build type, custom sinks, testing, common mistakes
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
- [`05-platform-notes.md`](05-platform-notes.md) — what `platformLogSink()` does on each platform
