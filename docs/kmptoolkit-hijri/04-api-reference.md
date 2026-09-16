# kmptoolkit-hijri — API reference

Package `io.github.jamal_wia.kmptoolkit.hijri`. Two public symbols, both listed here.

## `HijriDate`

```kotlin
public data class HijriDate(
    public val year: Int,
    public val month: Int,
    public val dayOfMonth: Int,
)
```

A date on the Umm al-Qura calendar.

| Property | Contract |
|---|---|
| `year` | The Hijri year, e.g. `1448`. Positive for every date the platform calendars accept. |
| `month` | `1..12`, **Muharram first**. One-based on both platforms, so it indexes a twelve-name array as `names[month - 1]`. |
| `dayOfMonth` | `1..30`. A Hijri month has 29 or 30 days, and which one is a published fact, not a formula — do not assume 30 exists in a given month. |

Value type: equality is by all three components, and it carries no platform handle, so it is safe
to hold, compare, and pass between threads.

The class deliberately has no formatting, no month name, and no comparison operators. Names are
localized text and belong to the consuming app; ordering, if you need it, is a comparison of the
three numbers in order, and adding a `Comparable` implementation nobody asked for would be a
compatibility promise with no reader.

## `LocalDate.toHijriDate`

```kotlin
public expect fun LocalDate.toHijriDate(): HijriDate
```

Converts this civil (Gregorian) date to Umm al-Qura.

**Contract**

- Total: every `LocalDate` the platform calendars accept converts; the function does not throw and
  returns no null. Dates far outside the published tables extrapolate rather than fail — both
  platforms behave that way, and neither reports where the tables end.
- Date-to-date: no hour, no time zone, no clock reading. The same input returns the same output on
  every run, on both platforms, forever.
- Pure: no state between calls, no lock, no I/O. Callable from any thread and from several at once.
- Injective and monotonic over consecutive days: day *n+1* converts to the next Hijri day, or to
  the first of the next month, and never backwards.

**Cost**

One platform calendar object per call and a table lookup. No allocation worth pooling and no
caching inside — a shared calendar instance would be mutable state in a function whose whole value
is being pure.

**Platform implementations**

| Platform | Backed by |
|---|---|
| Android | `android.icu.util.IslamicCalendar` with `CalculationType.ISLAMIC_UMALQURA`, read in UTC |
| iOS | `NSCalendar(NSCalendarIdentifierIslamicUmmAlQura)`, read in UTC |

See [`05-platform-notes.md`](05-platform-notes.md) for why the calculation type is pinned and why
the zone is fixed.

## What the module does not declare

No permission, no manifest entry, no `Info.plist` key, no initialization call, no DI binding. Adding
the dependency is the whole setup.
