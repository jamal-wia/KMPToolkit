# kmptoolkit-logging-overlay — Overview

An on-screen log viewer for Compose Multiplatform: a bounded buffer you fill through
[`kmptoolkit-logging`](../kmptoolkit-logging/01-overview.md)'s `LogSink`, and a composable that
draws it over your UI.

## Read this part first

**This is a development tool. It must not ship in a release build.** Three concrete reasons, none of
them theoretical:

- **It retains log records in memory.** Every recorded event is held until it is evicted or cleared.
  The buffer is bounded (200 records by default, `maxRecords` on `LogOverlayState`) precisely
  because an unbounded one is a memory leak in a long session — but bounded still means resident,
  and a stack trace captured as text is not small.
- **It has no PII filtering.** It faithfully paints whatever you logged: tokens, emails, request
  bodies, user ids. On a device, on a screen, in front of whoever is holding the phone or standing
  behind them. Deciding what belongs in a log is your code's job — the library cannot tell a user id
  from an email address.
- **It costs work on the logging thread.** Recording a `Throwable` converts its stack trace to text
  synchronously, in the caller's stack.

Gate both halves — the sink and the composable — behind your own debug-build flag:

```kotlin
val sinks = if (BuildConfig.DEBUG) listOf(platformLogSink(), overlayState.asLogSink()) else listOf(platformLogSink())
```

This module ships no build-type detection of its own. A library cannot reliably tell what kind of
build it ended up in, and one that guesses wrong either silently disables itself in the build you
were debugging or silently enables itself in the one you shipped. Your app already knows.

## The problem it solves

Reading a device's logs normally means a cable and a terminal. That is fine at your desk and useless
everywhere else: a tester's phone across the country, a tablet locked in kiosk mode, an iPad in
someone's hands during a demo, a bug that only reproduces on the train. The information you need is
already being logged — you just cannot see it.

This module puts that same log stream on the device's own screen, without changing how your code
logs. `kmptoolkit-logging` already fans one event out to as many destinations as you install, so the
overlay is one more destination:

```kotlin
val overlayState = LogOverlayState()
val loggerFactory = createLoggerFactory(sinks = listOf(platformLogSink(), overlayState.asLogSink()))
```

Everything already logging through that factory now also lands in the overlay. Nothing at a call
site changes.

## What this is **not**

- **Not for release builds.** See above. It is the single most important thing on this page.
- **Not a PII filter, a redactor, or a compliance control.** It shows what you logged, verbatim.
- **Not persistent.** Records live in memory and die with the process. There is no file, no
  database, no export, no "logs from the previous run". A crash takes the buffer with it — if you
  need post-mortem logs, that is a crash reporter's job, not this module's.
- **Not unbounded.** The oldest record is evicted once the buffer is full. If a burst of logging
  matters more to you than the last 200 events, raise `maxRecords` deliberately and accept the
  memory.
- **Not a log *source*.** It records nothing on its own; it only displays what a `LogSink` hands it.
  Events emitted before you installed the sink are not there.
- **Not a gesture or a dev menu.** It ships no shake detector, no hidden long-press, no floating
  button. `show()`, `hide()` and `toggle()` are yours to wire to whatever developer trigger your app
  already has — any gesture this module picked would collide with some app's real one.
- **Not a themed, designed UI.** It draws with your `MaterialTheme`'s colors and typography and
  makes no attempt to look like part of your product. It is a debug panel.
- **Not localized, and not a place for your copy.** The four strings it draws — title, clear, close,
  empty — are parameters on `LogOverlayLabels` with English defaults, so you can replace all of them
  in one object. See [`03-guide.md`](03-guide.md#relabeling-the-chrome).
- **Not a replacement for logcat or `os_log`.** Install `platformLogSink()` alongside it; the
  overlay is an extra view of the same stream, not a substitute for the platform's own.

## What it is made of

| Type | Role |
|---|---|
| `LogOverlayState` | The buffer plus the visibility flag. Plain Kotlin, no Compose, you create and own it. |
| `LogRecord` | One retained event: id, level, tag, message, stack-trace text, elapsed millis. |
| `LogOverlayHost` | Composable: draws your content, and the panel on top of it while visible. |
| `LogOverlayPanel` | The panel alone, for placing in your own sheet or developer screen. |
| `LogOverlayLabels` | The four chrome strings, all replaceable. |

`LogOverlayState` is deliberately not a `remember`ed value: it is created once at app start,
installed into your `LoggerFactory`, and outlives every composition — so an Android configuration
change does not throw away the history you were about to read.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — wired up in five minutes
- [`03-guide.md`](03-guide.md) — filtering, sizing the buffer, triggers, relabeling, testing
- [`04-api-reference.md`](04-api-reference.md) — every public symbol and its contract
