# kmptoolkit-hijri — Platform notes

## Android

Backed by `android.icu.util.IslamicCalendar`, part of the Android runtime since API 24 — which is
this suite's `minSdk`, so there is no API-level branch in the module and no desugaring to arrange.

**The calculation type is pinned, not inherited from the locale.**

```kotlin
val calendar = IslamicCalendar(IcuTimeZone.GMT_ZONE, ULocale.ROOT)
calendar.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
```

`IslamicCalendar` supports four calculation types, and which one a *locale* resolves to has changed
between ICU versions. A locale-derived calendar therefore gives a date that can shift under the
reader when the OS updates — a bug that appears months after the code was written, on some devices
only, and reads as data corruption rather than a calendar setting. `ULocale.ROOT` plus an explicit
type removes the variable.

**ICU counts months from zero.** The implementation adds one before returning, so the public
contract is one-based on both platforms. If you call ICU directly elsewhere in your app, remember
that your numbers and this module's are not the same number.

**Unit tests need Robolectric.** `android.icu` is part of the Android runtime, not of the JVM, so a
plain JVM unit test answers `Method setCalculationType in android.icu.util.IslamicCalendar not
mocked` instead of a date. The module's own Android tests run under `RobolectricTestRunner` for
exactly this reason; expect the same in yours.

## iOS

Backed by `NSCalendar(calendarIdentifier: NSCalendarIdentifierIslamicUmmAlQura)` from Foundation.
Available on every iOS version this suite supports, and counts months from one already.

**The user's calendar setting is not consulted.** iOS lets a person choose a calendar in Settings,
and `Calendar.current` follows it. This module does not use `Calendar.current` — it names the
Umm al-Qura calendar explicitly, so the answer is the same regardless of what the device is set to.
That is what makes the two platforms agree; if you want to follow the device setting instead, that
is a different function and it belongs in your app.

## Desktop (jvm)

Backed by `java.time.chrono.HijrahDate`, part of the JDK.

**Nothing to pin here.** The JDK ships exactly one Hijrah variant and it is `Hijrah-umalqura` — the
same published tables the other two platforms read. Where the Android half has to name its
calculation type explicitly, this one has no choice to get wrong.

**No time zone is involved at all.** `HijrahDate.from(LocalDate)` converts a date to a date. That is
what the other two platforms go to the trouble of reading in UTC to achieve.

## Both

**The conversion is read in UTC.** The input is a date with no hour, and the calendar is asked in a
zone with no offset. That is not an approximation — it is what makes the conversion date-to-date. A
local zone would mean midnight in one place is the previous evening in another, and the same
`LocalDate` would convert to two different Hijri dates depending on where the phone is.

Deciding which civil day it currently is *does* depend on a zone, and that decision stays with the
caller: `Clock.System.todayIn(zone)`.

**Dates outside the published tables extrapolate.** The Umm al-Qura tables cover roughly 1300–1600
AH. Both platforms continue past the ends with arithmetic rather than reporting a limit, and they
do not necessarily agree out there. In the range any app actually shows, they agree — the module's
tests assert that over two full years on both platforms.
