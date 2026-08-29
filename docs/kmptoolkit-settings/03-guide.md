# kmptoolkit-settings — guide

Scenarios in the order they come up: defining your own font-scale steps, listing languages in a
picker, deciding what a load problem means, keeping the language applied, replacing the omitted
logout hook, and testing all of it.

## Defining font-scale steps

`FontScale` is a range, not a list. If your settings screen offers three buttons, the enum is
yours:

```kotlin
enum class TextSize(val scale: FontScale, val labelRes: StringResource) {
    STANDARD(FontScale.DEFAULT, Res.string.text_size_standard),
    LARGE(FontScale(1.15f), Res.string.text_size_large),
    EXTRA_LARGE(FontScale(1.30f), Res.string.text_size_extra_large);

    companion object {
        fun of(scale: FontScale): TextSize =
            entries.minBy { kotlin.math.abs(it.scale.multiplier - scale.multiplier) }
    }
}
```

`of` matches the nearest step rather than requiring equality, so a value written by a build with
different steps — or by a slider — still selects a sensible button instead of falling through to
"none selected". That is the whole reason the stored value is a number and not the name of a step:
your steps can change between releases without invalidating what users already chose.

If you offer a **slider** instead, take the value straight from it and clamp:

```kotlin
settings.setFontScale(FontScale.coerced(sliderValue))
```

And if you want to mirror the OS text-size setting as your starting point, `coerced` again — the
platform value can exceed this library's range on both platforms.

## Listing languages in a picker

The library stores tags and knows no display names, because a display name is copy. Producing one
is a platform one-liner:

```kotlin
// androidMain
actual fun LanguageTag.displayName(): String =
    java.util.Locale.forLanguageTag(value).getDisplayName(java.util.Locale.forLanguageTag(value))

// iosMain
actual fun LanguageTag.displayName(): String =
    NSLocale(localeIdentifier = value).localizedStringForLocaleIdentifier(value) ?: value
```

Note both render the name **in its own language** ("Português (Brasil)", not "Portuguese
(Brazil)") — which is what a language picker should do, since a user who cannot read the current
language has to find their own.

The list itself is `config.supportedLanguages` plus a "System" row that sets `null`:

```kotlin
val options: List<LanguageTag?> = listOf(null) + config.supportedLanguages.sortedBy { it.value }
```

That works when you declared a set. Under the **default** config `supportedLanguages` is empty,
which means "accept anything" rather than "nothing is available" — `config.supports(tag)` is `true`
for every well-formed tag there, and the list of what to *show* has to come from your own
translation catalogue, since the library has no way to know it.

## What to do with a load problem

`createAppSettings` returns `SettingsLoad(settings, problems)`. Each problem tells you something
different:

| Problem | What happened | A reasonable reaction |
|---|---|---|
| `ReadFailed` | The store could not be read. The stored value, if any, is still there. | Log it. Do **not** try to write the default back — that would destroy a value that is merely unreachable right now. |
| `UnreadableValue` | The entry holds something this version cannot parse — an older format, a dropped range, a partial restore. | Log it. The entry is harmless: it is overwritten by the next value the user actually picks. |
| `UnsupportedLanguage` | The stored language is no longer in `supportedLanguages`, usually because an update dropped a translation. | Log it, and consider telling the user once that their language is no longer available — this is the one problem a user can act on. |

A stale entry cannot be "repaired" by writing the loaded value back: the flow already holds it, so
`settings.setThemeMode(settings.themeMode.value)` is a no-op that returns `Success` and writes
nothing (see [`04-api-reference.md`](04-api-reference.md#appsettings)). That is deliberate — a
setting the user never touched should not cause a write on every launch. If you genuinely want the
entry gone, remove the key through the same `KeyValueStorage` you passed in:
`storage.remove(config.themeModeKey)`.

Ignoring `problems` entirely is a defensible choice for a small app. Assigning it to `_` is not
the same as never having seen it, which is the point of returning it.

## Keeping the language applied

`AppSettings` records the choice; `LanguageApplier` makes the platform act on it. Wire them once,
at start-up, so the persisted language is applied on every launch and every later change follows:

```kotlin
scope.launch { settings.language.collect(applier::apply) }
```

`StateFlow` replays its current value to a new collector, so this applies the loaded language
immediately and then each change. Below Android 13, and on iOS, that start-up application is what
makes the choice survive a restart.

**On Android 13+ it is not free, and one case needs care.** The system stores the language itself
and lists your app in Settings → Apps → *your app* → Language, so a user can change it *outside*
your UI. `AppSettings` does not hear about that, and the start-up collect above would then write
your stale value back through `LocaleManager` — reverting the user's choice and recreating your
activities during start-up. If you expose the language in your own settings screen and are on
API 33+, reconcile at start-up before wiring the collector:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    val system: String? = context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales?.toLanguageTags()?.takeIf { it.isNotEmpty() }
    val chosen: LanguageTag? = system?.substringBefore(',')?.let(LanguageTag::ofOrNull)
    if (chosen != settings.language.value) settings.setLanguage(chosen)
}
scope.launch { settings.language.collect(applier::apply) }
```

The library does not do this for you because reading the system value back is an Android-only
concept with no iOS counterpart, and `LanguageApplier` is deliberately write-only — see
[`05-platform-notes.md`](05-platform-notes.md).

**Do not** call `applier.apply(...)` from your settings screen *instead* of collecting: a language
that is applied where it was picked but not at start-up is a language that reverts on the next
launch, and the bug is invisible in the session you test it in. Read
[`05-platform-notes.md`](05-platform-notes.md) for what "applied" means and when the user sees it.

## Replacing the omitted logout hook

The module this was ported from also cleared a biometric opt-in on logout. That is out of scope
here (see [`01-overview.md`](01-overview.md#what-this-is-not)), and it is three lines in your own
logout code:

```kotlin
suspend fun onLogout(settings: AppSettings) {
    settings.setLanguage(null)
}
```

Whether you want that at all is worth a moment's thought: a font scale is an accessibility
setting, and resetting it on logout takes a large-text choice away from the person who needs it
most. On a shared or kiosk device, resetting the **language** is usually right and resetting the
**font scale** usually is not.

## Two independent settings stores

`SettingsConfig.keyPrefix` is what keeps two of these apart inside one `KeyValueStorage`:

```kotlin
val app = createAppSettings(storage, SettingsConfig(keyPrefix = "com.example.app"))
val kids = createAppSettings(storage, SettingsConfig(keyPrefix = "com.example.app.kids"))
```

The same lever also reads keys an older version of your app wrote — point `keyPrefix` at your old
namespace and the values load without a migration, as long as the encodings match (a font scale is
the multiplier as a decimal string, a theme mode is the enum constant's name, a language is the
canonical tag, and `""` means "follow the system").

## Testing

There is no `kmptoolkit-settings-testing` artifact, and there does not need to be. The double you
need is a store, which `kmptoolkit-storage-testing` already ships:

```kotlin
val storage = InMemoryKeyValueStorage()
val settings: AppSettings = createAppSettings(storage).settings

settings.setThemeMode(ThemeMode.DARK)
assertEquals("DARK", storage.contents["io.github.jamal_wia.kmptoolkit.settings.theme_mode"])
```

Testing the failure paths is the same fixture:

```kotlin
storage.failNextOperationWith = StorageError.Unavailable()
val result: SettingsResult = settings.setThemeMode(ThemeMode.LIGHT)

assertTrue(result is SettingsResult.Failure)
assertEquals(ThemeMode.DARK, settings.themeMode.value) // unchanged, because the write failed
```

And `LanguageApplier` is a `fun interface`, so a test double is a lambda:

```kotlin
val applied = mutableListOf<LanguageTag?>()
val applier = LanguageApplier { applied += it }
```

Next: [`04-api-reference.md`](04-api-reference.md).
