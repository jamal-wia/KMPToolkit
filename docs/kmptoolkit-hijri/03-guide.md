# kmptoolkit-hijri — Guide

## Letting the reader correct the date

Umm al-Qura is computed from tables. A mosque announces a sighting. The two agree most of the time
and differ by a day often enough that an app showing the date should let the reader say so.

**Shift the civil date, then convert. Never shift the Hijri day number.**

```kotlin
fun hijriToday(zone: TimeZone, correctionDays: Int): HijriDate =
    Clock.System.todayIn(zone)
        .plus(DatePeriod(days = correctionDays))
        .toHijriDate()
```

The reason is that a Hijri month has 29 or 30 days and which one is not predictable from the
number. Adding one to `dayOfMonth` can produce a 30th of a month that ends on the 29th — a date
that does not exist, that no name lookup will catch, and that the next day's conversion will
contradict. A shifted civil date always converts to a date that exists.

Keep the correction in your own settings, bounded to something small — one or two days either way
covers every real disagreement.

## Showing the month name

`month` is `1..12`, Muharram first, which is what an indexed lookup wants:

```kotlin
val name: String = monthNames[hijri.month - 1]
```

Those names are user-facing text, so they live in your localized resources, not here — no module in
this suite ships a string meant for display (see [`01-architecture.md`](../01-architecture.md)).

Watch the direction when the name lands in a line with digits: "5 Rabi' al-Thani, 1448" mixes a
right-to-left name with left-to-right numbers, and the Unicode bidi algorithm will reorder the runs
if you build the string by plain concatenation. Isolate the foreign-direction run explicitly.

## Which day is "today"

The conversion takes a date, not an instant, so the zone question is settled before you call:

```kotlin
val zone: TimeZone = TimeZone.currentSystemDefault()
val hijri: HijriDate = Clock.System.todayIn(zone).toHijriDate()
```

This is deliberate. An overload taking an `Instant` would have to pick a zone on your behalf, and
the picked zone is exactly the thing that puts a user one day out when they travel.

If a screen stays open past midnight, re-read the date — nothing here observes the clock for you.

## Converting a range

There is no batched entry point, and none is needed: the conversion is a table lookup with no I/O
and no allocation worth avoiding.

```kotlin
val month: List<HijriDate> = (0 until 30)
    .map { start.plus(DatePeriod(days = it)).toHijriDate() }
```

## Thread safety

`toHijriDate` holds no state between calls and takes no lock. Call it from any thread, including a
UI thread, and from several at once.

On Android it constructs an `IslamicCalendar` per call, which is cheap; on iOS it constructs an
`NSCalendar`. Neither is cached deliberately — a shared mutable calendar instance is exactly the
kind of hidden state that turns a pure conversion into a race.

## What to do about the other Islamic calendars

This module is Umm al-Qura only. If you need the tabular civil calendar or an astronomical variant,
you need the platform APIs directly — `IslamicCalendar.CalculationType` on Android,
`NSCalendarIdentifierIslamic*` on iOS. Exposing the choice here would mean every caller has to know
which of the four they mean before they can ask for a date, and the overwhelmingly common answer is
the one the Saudi civil calendar publishes.
