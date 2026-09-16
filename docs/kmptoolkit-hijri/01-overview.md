# kmptoolkit-hijri — Overview

Two symbols: a `HijriDate` value, and a `LocalDate.toHijriDate()` that fills it in.

```kotlin
val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
val hijri: HijriDate = today.toHijriDate()   // HijriDate(year = 1448, month = 4, dayOfMonth = 5)
```

That call site is the whole point. It does not know that Android answers through ICU's
`IslamicCalendar` and iOS through `NSCalendar`, that one of them counts months from zero and the
other from one, or that leaving the calendar to be resolved from the locale makes the date change
under the reader on an OS upgrade.

## The problem it solves

The Islamic month is 29 or 30 days, and which one is **not a formula**. It comes from tables the
Saudi authority publishes, and both platforms already ship those tables — as does the clock app on
the phone your user is holding.

So the tempting shortcut is the wrong one. Arithmetic of your own drifts from the phone's own
calendar for whole months at a time, and the user notices immediately, because the date is on their
lock screen next to yours. Reading the platform's tables is the only answer that agrees with the
device.

What is individually small and collectively annoying:

- **Android** needs the calculation type pinned explicitly. `IslamicCalendar` resolves a locale's
  Islamic variant differently between ICU versions, so a locale-derived calendar silently changes
  its answer when the OS updates.
- **Android counts months from zero.** iOS counts from one. A conversion that forwards the
  platform's number unchanged puts Safar's name on Muharram's days on exactly one platform.
- **Both** will happily convert a date-plus-time and hand back the neighbouring day when the zone
  and the hour conspire. The conversion has to be pinned to a zone that cannot do that.

## What this is not

- **Not a formatter.** The module returns numbers. Month names are user-facing text, and
  user-facing text is the consuming app's — see `01-architecture.md` for why no module here ships
  any. `month` is `1..12` with Muharram first, so it indexes your own name array directly.
- **Not a sighting.** Umm al-Qura is the Saudi *civil* calendar, computed from tables. A local
  mosque announces a *sighting*, which can legitimately fall a day either side. Apps that display
  the date usually let the reader correct it by a day; that correction is app policy, not a
  calendar fact, so it lives in your code. Shift the civil date before converting rather than the
  day number after — a Hijri month may have 29 days, so "add one to the day number" can land on a
  30th that does not exist.
- **Not the other direction.** There is no `HijriDate.toLocalDate()`. Nothing in the suite needed
  it, and an unused public symbol is a compatibility promise with no reader — ask, and it gets
  added.
- **Not the other Islamic calendars.** Umm al-Qura only. `IslamicCalendar` offers civil and
  astronomical variants too; exposing a choice nobody asked for would mean every consumer has to
  understand the difference before calling.

## Read next

- [`02-getting-started.md`](02-getting-started.md) — a working example in five minutes
- [`03-guide.md`](03-guide.md) — the reader's ±1 day correction, month names, ranges, which day is today
- [`04-api-reference.md`](04-api-reference.md) — both public symbols and their contracts
- [`05-platform-notes.md`](05-platform-notes.md) — ICU on Android, Foundation on iOS, why the zone is UTC

There is no `06-testing.md`: the module ships no test double. `toHijriDate` is a pure function with
no seam to fake — call it in your test and assert the date, the way the module's own tests do.
