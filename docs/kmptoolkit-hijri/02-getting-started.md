# kmptoolkit-hijri — Getting started

Five minutes to a date on screen.

## 1. Add the dependency

With the BOM (see [`00-getting-started.md`](../00-getting-started.md) for how it is wired):

```kotlin
commonMain.dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-hijri")
}
```

Or pinned directly:

```kotlin
commonMain.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-hijri:<version>")
}
```

It brings `kotlinx-datetime` with it, as an `api` dependency — the conversion takes a `LocalDate`,
so you get the type without declaring it twice.

## 2. Convert a date

```kotlin
import io.github.jamal_wia.kmptoolkit.hijri.HijriDate
import io.github.jamal_wia.kmptoolkit.hijri.toHijriDate

val today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
val hijri: HijriDate = today.toHijriDate()

println("${hijri.dayOfMonth}/${hijri.month}/${hijri.year}")   // 5/4/1448
```

No initialization, no platform object to hold, nothing to release. The function reads the tables the
platform already has.

## 3. Turn it into something a reader can read

The module returns numbers on purpose, so the month name comes from your own resources. `month` is
`1..12` with Muharram first, so it indexes an array directly:

```kotlin
val monthNames: List<String> = listOf(
    "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani",
    "Jumada al-Ula", "Jumada al-Akhirah", "Rajab", "Sha'ban",
    "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah",
)

val label: String = "${hijri.dayOfMonth} ${monthNames[hijri.month - 1]}, ${hijri.year}"
// 5 Rabi' al-Thani, 1448
```

In a real app those names are localized strings, not a literal list.

## 4. Decide which day it is, before you convert

The conversion is date-to-date. Which civil day it currently is depends on a time zone, and that
decision is yours:

```kotlin
val zone: TimeZone = TimeZone.currentSystemDefault()
val hijriHere: HijriDate = Clock.System.todayIn(zone).toHijriDate()
```

## Next

- [`03-guide.md`](03-guide.md) — letting the reader correct the date by a day, and why the
  correction moves the civil date rather than the Hijri one.
- [`05-platform-notes.md`](05-platform-notes.md) — what each platform computes with, and the
  minimum Android API level.
